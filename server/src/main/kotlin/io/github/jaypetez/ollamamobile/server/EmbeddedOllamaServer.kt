package io.github.jaypetez.ollamamobile.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The whole HTTP surface, installed into a Ktor [Application]. */
fun Application.ollamaServerModule(env: ServerEnvironment) {
    installServerPlugins(env)
    routing {
        ollamaApiRoutes(env)
        openAiRoutes(env)
    }
}

/** What the UI and the notification observe. */
sealed interface ServerState {
    data object Stopped : ServerState

    data object Starting : ServerState

    data class Running(
        val config: ServerConfig,
    ) : ServerState

    /** [reason] is already user-facing; the usual one is "port in use". */
    data class Failed(
        val reason: String,
    ) : ServerState
}

/**
 * Owns the Ktor engine and the idle-unload timer.
 *
 * **Nothing here starts by itself.** There is no boot receiver and no
 * auto-start: an inference endpoint that comes up on every reboot is one the
 * user stopped consenting to the moment they forgot it existed.
 */
class EmbeddedOllamaServer(
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow<ServerState>(ServerState.Stopped)

    val state: StateFlow<ServerState> = stateFlow.asStateFlow()

    private var engine: EmbeddedServer<*, *>? = null

    private var sweeper: Job? = null

    private var environment: ServerEnvironment? = null

    /** Live request counter for the notification, or a constant zero when stopped. */
    val requestCount: StateFlow<Long>?
        get() = environment?.requestCount

    /**
     * Binds the socket and installs the routes.
     *
     * Returns the resulting state rather than throwing: "port 11434 is already
     * taken by something else" is an ordinary outcome that belongs on screen,
     * not in a crash report.
     */
    suspend fun start(env: ServerEnvironment): ServerState {
        stop()
        val config = runCatching { env.config.requireValid() }.getOrElse { failure ->
            return fail(failure.message ?: "invalid server configuration")
        }
        stateFlow.value = ServerState.Starting
        return withContext(Dispatchers.IO) {
            try {
                val started = embeddedServer(
                    factory = CIO,
                    port = config.port,
                    host = config.bindAddress,
                    module = { ollamaServerModule(env) },
                ).start(wait = false)
                engine = started
                environment = env
                sweeper = scope.launch { sweepLoop(env) }
                ServerState.Running(config).also { stateFlow.value = it }
            } catch (failure: IOException) {
                fail(failure.message ?: "could not bind ${config.displayAddress}")
            }
        }
    }

    suspend fun stop() {
        sweeper?.cancel()
        sweeper = null
        val running = engine ?: run {
            stateFlow.value = ServerState.Stopped
            return
        }
        engine = null
        withContext(Dispatchers.IO) {
            running.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        }
        environment?.residency?.clear()
        environment = null
        stateFlow.value = ServerState.Stopped
    }

    private fun fail(reason: String): ServerState = ServerState.Failed(reason).also { stateFlow.value = it }

    /**
     * The idle-unload timer.
     *
     * `/api/ps` reports `expires_at`, and a deadline that nothing enforces is a
     * lie the client acts on. This loop is what makes the timestamp true; it
     * runs at a coarse interval because a model outliving its deadline by a few
     * seconds is harmless while a per-second wakeup is not.
     */
    private suspend fun sweepLoop(env: ServerEnvironment) {
        while (true) {
            delay(SWEEP_INTERVAL_MILLIS)
            env.residency.sweep()
        }
    }

    private companion object {
        const val GRACE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 2_000L
        val SWEEP_INTERVAL_MILLIS = 5.seconds.inWholeMilliseconds
    }
}
