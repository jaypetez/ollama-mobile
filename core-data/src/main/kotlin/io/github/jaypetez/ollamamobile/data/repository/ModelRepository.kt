package io.github.jaypetez.ollamamobile.data.repository

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.mapper.toDomain
import io.github.jaypetez.ollamamobile.data.mapper.toEntity
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ServerClientFactory
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything the app could run, split by where it lives.
 *
 * [local] is read from the `models` table and populated by
 * [LocalModelRepository] from what is actually on disk. **An empty [local] is
 * ambiguous and must never be rendered on its own**: on a
 * `-Pollama.nativeSource=none` build it means "this build has no inference
 * engine", and on a native build it means "nothing downloaded yet". The two
 * have different fixes, and showing the second when the first is true sends the
 * user to download a model that will never load. Read
 * [ModelRepository.localInferenceAvailable] alongside it.
 */
data class ModelCatalogue(
    val remote: List<ModelRef> = emptyList(),
    val local: List<ModelRef> = emptyList(),
) {
    /** Local first, then remote, each already sorted by display name. */
    val all: List<ModelRef>
        get() = local + remote

    val isEmpty: Boolean
        get() = local.isEmpty() && remote.isEmpty()
}

/** The outcome of refreshing one server's model list. */
data class ModelRefreshResult(
    val serverId: ServerId,
    val models: List<ModelRef>,
    /** Null on success. A server that is down contributes an error, not an exception. */
    val error: AppError? = null,
)

/**
 * The union of the models every enabled server offers, plus the (empty) set of
 * on-device models.
 *
 * Reads come from the cached table so the picker opens instantly and works
 * offline; [refresh] is what talks to the servers. Cache invalidation is by
 * server: a refresh replaces that server's rows wholesale, because a model
 * deleted on the server has to disappear from the picker and a merge would
 * keep it forever.
 */
@Singleton
class ModelRepository
    @Inject
    constructor(
        private val modelDao: ModelDao,
        private val serverRepository: ServerRepository,
        private val clientFactory: ServerClientFactory,
        private val clock: WallClock,
        /**
         * Consulted for one boolean, [localInferenceAvailable]. The interface
         * comes from `:core-llm-api`, so this repository still knows nothing
         * about llama.cpp and `checkModuleGraph` stays satisfied.
         */
        private val engine: LlamaEngine,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /**
         * Models served by a configured server, whether or not it is reachable
         * right now.
         *
         * Reachability is deliberately not filtered in here: a picker whose
         * contents change while a Pi wakes up is worse than one that shows a
         * model the router then reports as unreachable, with a specific error.
         */
        val remoteModels: Flow<List<ModelRef>> = modelDao
            .observeByOrigin(ModelOriginColumn.REMOTE)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        /**
         * On-device models, written by
         * [LocalModelRepository][io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository].
         *
         * Empty on a build with no engine, and empty on a build that has one
         * but has nothing installed. See [ModelCatalogue] for why a consumer
         * must not present those two as the same thing.
         */
        val localModels: Flow<List<ModelRef>> = modelDao
            .observeByOrigin(ModelOriginColumn.LOCAL)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        val catalogue: Flow<ModelCatalogue> = combine(localModels, remoteModels) { local, remote ->
            ModelCatalogue(remote = remote, local = local)
        }

        /**
         * Whether an on-device engine can run anything at all.
         *
         * Stated once here so that no screen has to infer it from an empty
         * list — which is the mistake this property exists to prevent. It is
         * false for a `-Pollama.nativeSource=none` build, where
         * `StubLlamaEngine` is bound, and a screen that sees `false` must say
         * *this build has no inference engine* rather than showing an empty
         * model list that reads as "nothing downloaded yet".
         */
        val localInferenceAvailable: Boolean = engine.isAvailable

        fun observeModel(id: ModelId): Flow<ModelRef?> = modelDao
            .observe(id.value)
            .map { it?.toDomain() }
            .flowOn(io)

        fun observeForServer(serverId: ServerId): Flow<List<ModelRef>> = modelDao
            .observeForServer(serverId.value)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<ModelRef>> = modelDao
            .observeRecent(limit)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        suspend fun findModel(id: ModelId): ModelRef? = withContext(io) {
            modelDao.find(id.value)?.toDomain()
        }

        /**
         * Which servers currently list a model under [modelName].
         *
         * The router's central question. Matched on the *tag*, not on
         * [ModelId], because the same model on two servers has two ids by
         * construction (`<serverId>/<tag>`) and routing between them is the
         * whole point.
         */
        suspend fun serversServing(modelName: String): Set<ServerId> = withContext(io) {
            modelDao
                .observeByOrigin(ModelOriginColumn.REMOTE)
                .first()
                .filter { it.name == modelName }
                .mapNotNull { it.serverId?.let(::ServerId) }
                .toSet()
        }

        /**
         * Re-reads every enabled server's model list, in parallel.
         *
         * One unreachable server does not fail the refresh; it comes back as a
         * [ModelRefreshResult] with an error and its cached rows are left
         * alone. Deleting them would empty the picker every time a laptop
         * sleeps, and the cached list is still the right answer for "what does
         * this server have" — it just might be stale.
         */
        suspend fun refresh(): List<ModelRefreshResult> = withContext(io) {
            val servers = serverRepository.enabledServers.first()
            coroutineScope {
                servers
                    .map { server -> async { refreshServer(server) } }
                    .map { it.await() }
            }
        }

        /** Refreshes one server. Exposed for a per-server "reload" action. */
        suspend fun refreshServer(server: ServerRef): ModelRefreshResult = withContext(io) {
            val client = clientFactory.clientFor(server).client
            when (val result = client.listModels(server)) {
                is AppResult.Success -> {
                    replaceServerModels(server.id, result.value)
                    ModelRefreshResult(server.id, result.value)
                }

                is AppResult.Failure -> {
                    ModelRefreshResult(server.id, emptyList(), result.error)
                }
            }
        }

        suspend fun markUsed(id: ModelId): Unit = withContext(io) {
            modelDao.markUsed(id.value, clock.nowMillis())
        }

        suspend fun setFavourite(id: ModelId, favourite: Boolean): Unit = withContext(io) {
            modelDao.setFavourite(id.value, favourite)
        }

        /**
         * Replaces a server's cached rows.
         *
         * Delete-then-insert and not upsert: a model removed on the server has
         * to leave the picker, and an upsert has no way to express a deletion.
         * The two statements are not wrapped in a transaction because the
         * intermediate state — this server briefly contributing nothing — is
         * exactly what a concurrent reader should see, and is indistinguishable
         * from the server having been disabled for a moment.
         */
        private suspend fun replaceServerModels(serverId: ServerId, models: List<ModelRef>) {
            modelDao.deleteForServer(serverId.value)
            val remoteOnly = models.filter { it.origin is ModelOrigin.Remote }
            if (remoteOnly.isNotEmpty()) modelDao.upsertAll(remoteOnly.map { it.toEntity() })
        }

        companion object {
            const val RECENT_LIMIT: Int = 10
        }
    }
