package io.github.jaypetez.ollamamobile.data.repository

import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.common.net.NetworkPolicyController
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.storage.dao.SettingDao
import io.github.jaypetez.ollamamobile.storage.entity.SettingEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Which colour scheme the app draws in. */
enum class ThemeMode {
    /** Follow the system dark-mode setting. */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Everything the settings screen edits, in one snapshot.
 *
 * A single value object rather than a flow per setting: the settings screen
 * renders all of them at once, and eleven separate `collectAsState` calls is
 * eleven recompositions on every change.
 */
data class AppSettings(
    /** Enforced by `LanOnlyGuard` on the shared OkHttpClient, not by call sites. */
    val networkPolicy: NetworkPolicy = NetworkPolicyController.DEFAULT_POLICY,
    val routingPolicy: RoutingPolicy = RoutingPolicy.Default,
    /** Preselected in a new conversation. Null until the user has picked one. */
    val defaultModelId: ModelId? = null,
    /** Null means "send no system message", which is not the same as "". */
    val defaultSystemPrompt: String? = null,
    val defaultSampling: SamplingParams = SamplingParams.Default,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Whether reasoning blocks start expanded. Not whether they are requested. */
    val showReasoning: Boolean = false,
    /**
     * Whether to ask reasoning models for a `<think>` block at all.
     *
     * Distinct from [showReasoning]: this one changes what the model does and
     * how long it takes, the other changes only what is on screen.
     */
    val requestReasoning: Boolean = true,
    /** How long a server keeps the model resident, e.g. `"10m"`. Null leaves the server's own setting. */
    val keepAlive: String? = null,
)

/**
 * App-wide settings.
 *
 * Split across two stores on purpose, and the split is not arbitrary.
 * [NetworkPolicy] lives in `:core-common`'s own tiny DataStore because
 * `LanOnlyGuard` reads it synchronously from inside an OkHttp DNS lookup,
 * where nothing may touch Room. Everything else lives in the `settings` table,
 * where it can take part in the same transaction as the rows it describes.
 * This class is the seam that makes that invisible to the UI.
 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val settingDao: SettingDao,
        private val networkPolicyController: NetworkPolicyController,
        private val clock: WallClock,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        val settings: Flow<AppSettings> = combine(
            settingDao.observeAll(),
            networkPolicyController.policy,
        ) { rows, policy ->
            val values = rows.associate { it.key to it.value }
            AppSettings(
                networkPolicy = policy,
                routingPolicy = RoutingPolicy.fromNameOrNull(values[KEY_ROUTING_POLICY]) ?: RoutingPolicy.Default,
                defaultModelId = values[KEY_DEFAULT_MODEL]?.takeIf { it.isNotEmpty() }?.let(::ModelId),
                defaultSystemPrompt = values[KEY_SYSTEM_PROMPT],
                defaultSampling = readSampling(values),
                themeMode = ThemeMode.entries.firstOrNull { it.name == values[KEY_THEME] } ?: ThemeMode.SYSTEM,
                dynamicColor = values[KEY_DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
                showReasoning = values[KEY_SHOW_REASONING]?.toBooleanStrictOrNull() ?: false,
                requestReasoning = values[KEY_REQUEST_REASONING]?.toBooleanStrictOrNull() ?: true,
                keepAlive = values[KEY_KEEP_ALIVE],
            )
        }.flowOn(io)

        /** The current snapshot, for a caller that needs one value and not a subscription. */
        suspend fun current(): AppSettings = settings.first()

        /**
         * Routing policy on its own.
         *
         * The router reads this per request and nothing else, so it should not
         * have to recompute the whole settings object — and, more importantly,
         * should not re-route because the user changed the theme.
         */
        val routingPolicy: Flow<RoutingPolicy> = settingDao
            .observeValue(KEY_ROUTING_POLICY)
            .map { RoutingPolicy.fromNameOrNull(it) ?: RoutingPolicy.Default }
            .flowOn(io)

        val networkPolicy: Flow<NetworkPolicy> = networkPolicyController.policy

        /**
         * Delegated, not stored here.
         *
         * The controller publishes the new value before it writes it to disk,
         * so a request issued on the next line already obeys the stricter rule.
         */
        suspend fun setNetworkPolicy(policy: NetworkPolicy) {
            networkPolicyController.setPolicy(policy)
        }

        suspend fun setRoutingPolicy(policy: RoutingPolicy): Unit = put(KEY_ROUTING_POLICY, policy.name)

        suspend fun setDefaultModel(id: ModelId?): Unit = put(KEY_DEFAULT_MODEL, id?.value)

        suspend fun setDefaultSystemPrompt(prompt: String?): Unit = put(KEY_SYSTEM_PROMPT, prompt)

        suspend fun setThemeMode(mode: ThemeMode): Unit = put(KEY_THEME, mode.name)

        suspend fun setDynamicColor(enabled: Boolean): Unit = put(KEY_DYNAMIC_COLOR, enabled.toString())

        suspend fun setShowReasoning(enabled: Boolean): Unit = put(KEY_SHOW_REASONING, enabled.toString())

        suspend fun setRequestReasoning(enabled: Boolean): Unit = put(KEY_REQUEST_REASONING, enabled.toString())

        suspend fun setKeepAlive(value: String?): Unit = put(KEY_KEEP_ALIVE, value)

        /**
         * Writes the default sampling parameters.
         *
         * A null field deletes its row rather than storing `"null"`: null means
         * "let the engine decide", and a stored string would make that
         * indistinguishable from a user who typed the word.
         */
        suspend fun setDefaultSampling(sampling: SamplingParams): Unit = withContext(io) {
            putAll(
                KEY_TEMPERATURE to sampling.temperature?.toString(),
                KEY_TOP_P to sampling.topP?.toString(),
                KEY_TOP_K to sampling.topK?.toString(),
                KEY_MIN_P to sampling.minP?.toString(),
                KEY_REPEAT_PENALTY to sampling.repeatPenalty?.toString(),
                KEY_REPEAT_LAST_N to sampling.repeatLastN?.toString(),
                KEY_SEED to sampling.seed?.toString(),
                KEY_NUM_PREDICT to sampling.numPredict?.toString(),
                KEY_NUM_CTX to sampling.numCtx?.toString(),
                KEY_STOP to sampling.stop.takeIf { it.isNotEmpty() }?.joinToString(STOP_SEPARATOR),
            )
        }

        private fun readSampling(values: Map<String, String>): SamplingParams = SamplingParams(
            temperature = values[KEY_TEMPERATURE]?.toDoubleOrNull(),
            topP = values[KEY_TOP_P]?.toDoubleOrNull(),
            topK = values[KEY_TOP_K]?.toIntOrNull(),
            minP = values[KEY_MIN_P]?.toDoubleOrNull(),
            repeatPenalty = values[KEY_REPEAT_PENALTY]?.toDoubleOrNull(),
            repeatLastN = values[KEY_REPEAT_LAST_N]?.toIntOrNull(),
            seed = values[KEY_SEED]?.toLongOrNull(),
            numPredict = values[KEY_NUM_PREDICT]?.toIntOrNull(),
            numCtx = values[KEY_NUM_CTX]?.toIntOrNull(),
            stop = values[KEY_STOP]?.split(STOP_SEPARATOR).orEmpty().filter { it.isNotEmpty() },
        )

        private suspend fun put(key: String, value: String?): Unit = withContext(io) {
            if (value == null) {
                settingDao.delete(key)
            } else {
                settingDao.upsert(SettingEntity(key, value, clock.nowMillis()))
            }
        }

        private suspend fun putAll(vararg entries: Pair<String, String?>) {
            val now = clock.nowMillis()
            val (present, absent) = entries.partition { it.second != null }
            absent.forEach { settingDao.delete(it.first) }
            if (present.isNotEmpty()) {
                settingDao.upsertAll(present.map { SettingEntity(it.first, it.second.orEmpty(), now) })
            }
        }

        companion object {
            const val KEY_ROUTING_POLICY: String = "routing.policy"
            const val KEY_DEFAULT_MODEL: String = "chat.defaultModel"
            const val KEY_SYSTEM_PROMPT: String = "chat.systemPrompt"
            const val KEY_KEEP_ALIVE: String = "chat.keepAlive"
            const val KEY_THEME: String = "ui.theme"
            const val KEY_DYNAMIC_COLOR: String = "ui.dynamicColor"
            const val KEY_SHOW_REASONING: String = "ui.showReasoning"
            const val KEY_REQUEST_REASONING: String = "chat.requestReasoning"

            const val KEY_TEMPERATURE: String = "sampling.temperature"
            const val KEY_TOP_P: String = "sampling.topP"
            const val KEY_TOP_K: String = "sampling.topK"
            const val KEY_MIN_P: String = "sampling.minP"
            const val KEY_REPEAT_PENALTY: String = "sampling.repeatPenalty"
            const val KEY_REPEAT_LAST_N: String = "sampling.repeatLastN"
            const val KEY_SEED: String = "sampling.seed"
            const val KEY_NUM_PREDICT: String = "sampling.numPredict"
            const val KEY_NUM_CTX: String = "sampling.numCtx"
            const val KEY_STOP: String = "sampling.stop"

            /**
             * Stop sequences are joined with a unit separator, not a newline or
             * a comma: `"\n\n"` and `","` are both perfectly ordinary stop
             * sequences, and either would split a value in half on the way back
             * out.
             */
            const val STOP_SEPARATOR: String = "\u001F"
        }
    }
