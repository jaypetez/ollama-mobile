package io.github.jaypetez.ollamamobile.data.repository

import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.mapper.toDomain
import io.github.jaypetez.ollamamobile.data.mapper.toEntity
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ServerClientFactory
import io.github.jaypetez.ollamamobile.remote.health.ServerHealthMonitor
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.storage.crypto.SecretResult
import io.github.jaypetez.ollamamobile.storage.crypto.SecretsStore
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.dao.ServerDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * A credential as the user typed it.
 *
 * The plaintext exists only between the settings screen and
 * [ServerRepository.saveServer], which hands it straight to the Keystore-backed
 * store and keeps only the alias. It is deliberately *not* part of [ServerRef]
 * — see that type's KDoc for why a token must never live in a data class that
 * gets logged, bundled and `toString()`ed.
 */
sealed interface ServerCredential {
    /** Leave whatever is already stored alone. The value for "the user did not retype it". */
    data object Unchanged : ServerCredential

    /** No authentication; any previously stored secret for this server is deleted. */
    data object None : ServerCredential

    data class BearerToken(
        val token: String,
    ) : ServerCredential

    data class BasicAuth(
        val username: String,
        val password: String,
    ) : ServerCredential
}

/**
 * A configured server plus what the health monitor currently believes about it.
 *
 * Flattened rather than exposing `:core-remote`'s `ServerHealth` directly: that
 * type is an implementation detail of the monitor, and re-exporting it would
 * put `:core-remote` on the UI's compile classpath for no gain.
 */
data class ServerStatus(
    val server: ServerRef,
    val reachable: Boolean,
    val version: String? = null,
    /** Model tags the server currently has resident, from `/api/ps`. */
    val loadedModels: List<String> = emptyList(),
    /** Round-trip time of the last successful probe. Null when it has never answered. */
    val latencyMillis: Long? = null,
    val lastCheckedAtMillis: Long? = null,
    val lastError: AppError? = null,
    /** True while the breaker is refusing traffic, so the UI can say "not retrying yet". */
    val circuitOpen: Boolean = false,
    val consecutiveFailures: Int = 0,
)

/**
 * CRUD over configured servers, their credentials and their reachability.
 *
 * Three things are joined here and nowhere else: the row in Room, the secret in
 * the Keystore-backed store, and the live probe result from
 * [ServerHealthMonitor]. Keeping them together is what makes "forget this
 * server" a single call that cannot leave a bearer token behind.
 */
@Singleton
class ServerRepository
    @Inject
    constructor(
        private val serverDao: ServerDao,
        private val modelDao: ModelDao,
        private val secrets: SecretsStore,
        private val healthMonitor: ServerHealthMonitor,
        private val clientFactory: ServerClientFactory,
        private val clock: WallClock,
        @param:IoDispatcher private val io: CoroutineDispatcher,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        init {
            // The monitor polls whatever it was last told about, so the
            // enabled set has to be pushed at it whenever it changes.
            // Subscribing here rather than making every caller remember to is
            // the difference between "health works" and "health works if you
            // opened the settings screen first".
            serverDao
                .observeEnabled()
                .map { rows -> rows.map { it.toDomain() } }
                .onEach { healthMonitor.setServers(it) }
                .launchIn(scope)
        }

        val servers: Flow<List<ServerRef>> = serverDao
            .observeAll()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        val enabledServers: Flow<List<ServerRef>> = serverDao
            .observeEnabled()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(io)

        /**
         * Every configured server with its live health.
         *
         * A server the monitor has not reached yet reports `reachable = false`
         * rather than being omitted: "we have not checked" and "it is down"
         * look the same to a user staring at a list, and omitting the row would
         * make a freshly added server disappear for fifteen seconds.
         */
        val statuses: Flow<List<ServerStatus>> = combine(
            servers,
            healthMonitor.health,
        ) { configured, health ->
            configured.map { server ->
                val probe = health[server.id]
                ServerStatus(
                    server = server,
                    reachable = probe?.reachable == true,
                    version = probe?.version,
                    loadedModels = probe?.loadedModels.orEmpty(),
                    latencyMillis = probe?.latencyMillis,
                    lastCheckedAtMillis = probe?.lastCheckedAtMillis,
                    lastError = probe?.lastError,
                    circuitOpen = probe?.isCircuitOpen == true,
                    consecutiveFailures = probe?.consecutiveFailures ?: 0,
                )
            }
        }.flowOn(io)

        fun observeServer(id: ServerId): Flow<ServerRef?> = serverDao
            .observe(id.value)
            .map { it?.toDomain() }
            .flowOn(io)

        suspend fun findServer(id: ServerId): ServerRef? = withContext(io) {
            serverDao.find(id.value)?.toDomain()
        }

        suspend fun findByBaseUrl(baseUrl: String): ServerRef? = withContext(io) {
            serverDao.findByBaseUrl(baseUrl)?.toDomain()
        }

        /**
         * Adds a server, storing its credential under a derived alias.
         *
         * The alias comes from [SecretRef.forServer] rather than being invented
         * here, so the settings screen and the storage layer cannot drift into
         * two spellings and silently lose a saved token.
         */
        suspend fun addServer(
            label: String,
            baseUrl: String,
            credential: ServerCredential = ServerCredential.None,
            enabled: Boolean = true,
        ): AppResult<ServerRef> = saveServer(
            server = ServerRef(
                id = ServerId.random(),
                label = label,
                baseUrl = baseUrl.trimEnd('/'),
                enabled = enabled,
            ),
            credential = credential,
        )

        /**
         * Writes a server row and reconciles its stored secret.
         *
         * The secret is written *first*. A row that references an alias with no
         * value behind it produces an unauthenticated request and a 401 the UI
         * can explain; a stored secret with no row pointing at it is a leak
         * that nothing will ever clean up.
         */
        suspend fun saveServer(
            server: ServerRef,
            credential: ServerCredential = ServerCredential.Unchanged,
        ): AppResult<ServerRef> = withContext(io) {
            val existing = serverDao.find(server.id.value)
            val resolved = when (credential) {
                is ServerCredential.Unchanged -> AppResult.Success(existing?.toDomain()?.auth ?: server.auth)
                else -> writeCredential(server.id, credential)
            }
            when (resolved) {
                is AppResult.Failure -> {
                    resolved
                }

                is AppResult.Success -> {
                    val stored = server.copy(auth = resolved.value)
                    serverDao.upsert(stored.toEntity(sortOrder = existing?.sortOrder ?: 0))
                    // The protocol probe is cached per (id, baseUrl); an edited
                    // address must not keep talking to the old shape.
                    clientFactory.invalidate(stored)
                    AppResult.Success(stored)
                }
            }
        }

        suspend fun setEnabled(id: ServerId, enabled: Boolean): Unit = withContext(io) {
            serverDao.setEnabled(id.value, enabled)
        }

        /**
         * Forgets a server completely: the row, every secret under its alias
         * prefix, and its cached model list.
         *
         * All three, because a "forget" that leaves the token in the Keystore
         * or the models in the picker is a bug the user cannot see and cannot
         * undo.
         */
        suspend fun deleteServer(id: ServerId): Unit = withContext(io) {
            val existing = serverDao.find(id.value)
            secrets.forgetServer(id)
            modelDao.deleteForServer(id.value)
            serverDao.deleteById(id.value)
            existing?.toDomain()?.let(clientFactory::invalidate)
        }

        /** Records the SPKI pin the user accepted for this host. Only ever from an explicit accept. */
        suspend fun trustCertificate(id: ServerId, sha256: String): Unit = withContext(io) {
            serverDao.setPin(id.value, sha256)
            serverDao.find(id.value)?.toDomain()?.let(clientFactory::invalidate)
        }

        suspend fun clearCertificatePin(id: ServerId): Unit = withContext(io) {
            serverDao.clearPin(id.value)
            serverDao.find(id.value)?.toDomain()?.let(clientFactory::invalidate)
        }

        suspend fun markSeen(id: ServerId): Unit = withContext(io) {
            serverDao.markSeen(id.value, clock.nowMillis())
        }

        /** One probe now, for a "check again" button. Does not wait for the poll interval. */
        suspend fun probeNow(id: ServerId) {
            val server = findServer(id) ?: return
            healthMonitor.probeNow(server)
        }

        /**
         * Reads a stored credential back, for a settings screen that offers to
         * reveal it.
         *
         * Returns null when nothing is stored. An [AppResult.Failure] here is
         * [AppError.Storage.SecretUnavailable] and means the value is gone for
         * good — the user has to type it again, and no retry recovers it.
         */
        suspend fun revealCredential(ref: SecretRef): AppResult<String?> = withContext(io) {
            when (val result = secrets.get(ref)) {
                is SecretResult.Ok -> AppResult.Success(result.value)
                is SecretResult.Unavailable -> AppResult.Failure(result.error)
                is SecretResult.Failed -> AppResult.Failure(result.error)
            }
        }

        private suspend fun writeCredential(id: ServerId, credential: ServerCredential): AppResult<ServerAuth> =
            when (credential) {
                is ServerCredential.Unchanged -> {
                    AppResult.Success(ServerAuth.None)
                }

                is ServerCredential.None -> {
                    secrets.forgetServer(id)
                    AppResult.Success(ServerAuth.None)
                }

                is ServerCredential.BearerToken -> {
                    val ref = SecretRef.forServer(id, TOKEN_PURPOSE)
                    when (val put = secrets.put(ref, credential.token)) {
                        is SecretResult.Ok -> AppResult.Success(ServerAuth.BearerToken(ref))
                        is SecretResult.Unavailable -> AppResult.Failure(put.error)
                        is SecretResult.Failed -> AppResult.Failure(put.error)
                    }
                }

                is ServerCredential.BasicAuth -> {
                    val ref = SecretRef.forServer(id, PASSWORD_PURPOSE)
                    when (val put = secrets.put(ref, credential.password)) {
                        is SecretResult.Ok -> AppResult.Success(ServerAuth.BasicAuth(credential.username, ref))
                        is SecretResult.Unavailable -> AppResult.Failure(put.error)
                        is SecretResult.Failed -> AppResult.Failure(put.error)
                    }
                }
            }

        companion object {
            /** The `purpose` half of a bearer token's alias. See [SecretRef.forServer]. */
            const val TOKEN_PURPOSE: String = "token"

            /** The `purpose` half of a basic-auth password's alias. */
            const val PASSWORD_PURPOSE: String = "password"
        }
    }
