package io.github.jaypetez.ollamamobile.data.engine

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One generation that is currently running.
 *
 * [cancel] is the handle the notification's Stop action pulls. It cancels the
 * coroutine the stream is being produced in, which is the same thing the user
 * pressing Stop in the UI does — a "stop" that only dismissed the notification
 * would leave the model decoding with the screen off, which is the exact
 * battery cost the foreground service exists to make visible.
 */
data class ActiveGeneration(
    val id: Long,
    /** What to call it on the notification: a model tag, never a file path. */
    val modelLabel: String,
    val isLocal: Boolean,
    internal val cancel: () -> Unit,
)

/**
 * Which generations are in flight, for anything that needs to know without
 * being able to see the gateway.
 *
 * `:app`'s foreground service and the idle-unload timer need the same fact —
 * "is the engine busy?" — and neither may reach into
 * [io.github.jaypetez.ollamamobile.data.InferenceGatewayImpl] to get it.
 * Registration is the gateway's job and happens on every exit path, including
 * cancellation, because a leaked registration pins a wake lock forever.
 */
@Singleton
class InferenceActivityTracker
    @Inject
    constructor() {
        private val nextId = AtomicLong(1L)
        private val _active = MutableStateFlow<List<ActiveGeneration>>(emptyList())

        val active: StateFlow<List<ActiveGeneration>> = _active.asStateFlow()

        /** The newest generation, which is the one the notification names. */
        val current: ActiveGeneration?
            get() = _active.value.lastOrNull()

        fun begin(modelLabel: String, isLocal: Boolean, cancel: () -> Unit): ActiveGeneration {
            val handle = ActiveGeneration(
                id = nextId.getAndIncrement(),
                modelLabel = modelLabel,
                isLocal = isLocal,
                cancel = cancel,
            )
            _active.value = _active.value + handle
            return handle
        }

        /** Idempotent: a stream that fails after being cancelled unwinds twice. */
        fun end(handle: ActiveGeneration) {
            _active.value = _active.value.filterNot { it.id == handle.id }
        }

        /**
         * Cancels everything in flight.
         *
         * Deliberately does not clear the list: each cancelled stream unwinds
         * through its own `finally` and calls [end], so clearing here would make
         * the tracker claim to be idle while a coroutine was still winding down
         * — and the wake lock would be released early.
         */
        fun cancelAll() {
            _active.value.forEach { it.cancel() }
        }
    }
