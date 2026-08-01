package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.llm.InferenceGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that knows whether the embedded server is up.
 *
 * A singleton rather than state inside the service, because the settings screen
 * has to render the address and the token *before* the service starts and
 * after it dies, and a ViewModel that binds to a service to ask a yes/no
 * question is a lifecycle bug waiting to happen.
 *
 * ## LAN exposure is per session, always
 *
 * [lanToken] is minted in memory when the user opts in and is dropped when the
 * server stops. It is never persisted, so it cannot be restored on boot, and
 * there is deliberately no code path that starts the server without a user
 * action.
 */
@Singleton
class OllamaServerController
    @Inject
    constructor(
        private val gateway: InferenceGateway,
        private val admin: ModelAdmin,
        private val embeddings: EmbeddingProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val server = EmbeddedOllamaServer(scope)

        private val lock = Mutex()

        private val tokenFlow = MutableStateFlow<String?>(null)

        private val countFlow = MutableStateFlow(0L)

        val state: StateFlow<ServerState> = server.state

        /** The bearer token for this session, or null when bound to loopback. */
        val lanToken: StateFlow<String?> = tokenFlow.asStateFlow()

        /** Requests served since the current start. Resets to zero on stop. */
        val requestCount: StateFlow<Long> = countFlow.asStateFlow()

        /**
         * The collector mirroring the live counter onto [requestCount].
         *
         * Held so a restart cancels the previous one. Without this, every start
         * leaves a coroutine collecting a StateFlow that nothing will ever
         * complete — a leak that grows with each toggle of the switch.
         */
        private var counterRelay: Job? = null

        /**
         * Starts on [port] with [bindPolicy].
         *
         * Choosing [BindPolicy.LAN] mints a fresh 32-byte token every time —
         * reusing one across sessions would make it a long-lived credential
         * that leaks through a screenshot of the settings screen.
         */
        suspend fun start(port: Int = ServerConfig.DEFAULT_PORT, bindPolicy: BindPolicy = BindPolicy.LOOPBACK) {
            lock.withLock {
                val token = if (bindPolicy == BindPolicy.LAN) ServerConfig.generateToken() else null
                val config = ServerConfig(port = port, bindPolicy = bindPolicy, bearerToken = token)
                val env = ServerEnvironment(
                    config = config,
                    gateway = gateway,
                    admin = admin,
                    embeddings = embeddings,
                )
                tokenFlow.value = token
                countFlow.value = 0L
                counterRelay?.cancel()
                counterRelay = scope.launch { relayCounts(env) }
                server.start(env)
            }
        }

        suspend fun stop() {
            lock.withLock {
                server.stop()
                counterRelay?.cancel()
                counterRelay = null
                tokenFlow.value = null
                countFlow.value = 0L
            }
        }

        private suspend fun relayCounts(env: ServerEnvironment) {
            env.requestCount.collect { count -> countFlow.value = count }
        }
    }
