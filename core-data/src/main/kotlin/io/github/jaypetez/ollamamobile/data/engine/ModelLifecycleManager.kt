package io.github.jaypetez.ollamamobile.data.engine

import android.content.ComponentCallbacks2
import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.data.repository.SettingsRepository
import io.github.jaypetez.ollamamobile.llm.EngineRole
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.asException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Why a model left memory. Recorded so the UI can say something better than "unloaded". */
enum class UnloadReason {
    /** The user asked. */
    REQUESTED,

    /** The keep-alive window expired with nothing running. */
    IDLE_TIMEOUT,

    /** `onTrimMemory` or `onLowMemory` fired. */
    MEMORY_PRESSURE,

    /** A different model is being loaded in its place. */
    REPLACED,
}

/**
 * Owns what is in the engine, and for how long.
 *
 * ## Two independent reasons a model leaves memory
 *
 * **The keep-alive timer**, which mirrors Ollama's `keep_alive` so that the
 * setting the user already configured for their servers means the same thing on
 * the device. It runs only while nothing is generating and is restarted at the
 * end of every turn.
 *
 * **Memory pressure**, which is not a timer and cannot be one.
 * [io.github.jaypetez.ollamamobile.storage.MemoryEstimator] can honestly answer
 * `Fits` at load time and the low-memory killer can still disagree ninety
 * seconds later, because the estimate is against `availMem` at one instant and
 * the camera opening changes it. Being killed loses the whole process — the
 * conversation being generated included — whereas dropping the weights loses
 * only the answer in flight, and the difference is what makes
 * [onTrimMemory] mandatory rather than an optimisation. It is wired in
 * `OllamaMobileApplication`, which is the only object the platform will call it
 * on.
 *
 * ## Serialised
 *
 * Every load and unload goes through one mutex. `llama_context` creation and
 * destruction are not concurrent operations, and two coroutines racing to swap
 * the resident model is how a freed context gets decoded into.
 */
@Singleton
class ModelLifecycleManager
    @Inject
    constructor(
        private val engine: LlamaEngine,
        private val localModels: LocalModelRepository,
        private val settings: SettingsRepository,
        private val activity: InferenceActivityTracker,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val lock = Mutex()
        private var idleJob: Job? = null

        private val _lastUnloadReason = MutableStateFlow<UnloadReason?>(null)

        /** Why the engine is currently empty, or null when it has never held anything. */
        val lastUnloadReason: StateFlow<UnloadReason?> = _lastUnloadReason.asStateFlow()

        /** The resident model, straight from the engine. Null when nothing is loaded. */
        val resident: StateFlow<ModelRef?> = engine.loadedModel

        val engineAvailable: Boolean
            get() = engine.isAvailable

        /**
         * Makes [modelId] the resident model, loading it if it is not already.
         *
         * Idempotent for the model that is already loaded, which is the whole
         * point of a warm model being cheap: the second turn of a conversation
         * must not pay the load again.
         *
         * @throws AppErrorException with [AppError.Engine.NotAvailable] when the
         *   build has no engine, [AppError.Model.NotFound] when the file is
         *   gone, or [AppError.Model.InsufficientMemory] when the estimate
         *   refuses it. Thrown rather than returned because every caller either
         *   proceeds or reports, and none of them can partially recover.
         */
        suspend fun ensureLoaded(modelId: ModelId): ModelRef = lock.withLock {
            if (!engine.isAvailable) throw notAvailable().asException()
            engine.loadedModel.value
                ?.takeIf { it.id == modelId }
                ?.let { return@withLock it }

            val record = localModels.find(modelId)
                ?: throw AppError.Model.NotFound(modelId = modelId).asException()

            val verdict = localModels.verdictFor(modelId) ?: record.verdict
            if (verdict is MemoryVerdict.Refuse) {
                // Refused before the file is opened, not after ggml has already
                // mapped two gigabytes: an OOM kill teaches the user nothing and
                // takes the conversation with it.
                throw AppError.Model.InsufficientMemory(verdict = verdict).asException()
            }

            if (engine.loadedModel.value != null) {
                engine.unload()
                _lastUnloadReason.value = UnloadReason.REPLACED
            }
            engine.load(
                ModelLoadSpec(
                    model = record.ref,
                    path = record.path,
                    role = EngineRole.CHAT,
                    contextTokens = record.budgetedContextLength,
                ),
            )
            _lastUnloadReason.value = null
            engine.loadedModel.value ?: record.ref
        }

        /** Unloads whatever is resident. Idempotent, and never throws. */
        suspend fun unload(reason: UnloadReason = UnloadReason.REQUESTED) {
            cancelIdleTimer()
            performUnload(reason)
        }

        /**
         * The unload itself, with no timer bookkeeping.
         *
         * Separate from [unload] because the timer's own coroutine calls it: a
         * shared implementation that cancelled [idleJob] would be cancelling the
         * coroutine it is running in, and the unload would never happen.
         */
        private suspend fun performUnload(reason: UnloadReason) {
            lock.withLock {
                if (engine.loadedModel.value == null) return@withLock
                engine.unload()
                _lastUnloadReason.value = reason
            }
        }

        private fun cancelIdleTimer() {
            idleJob?.cancel()
            idleJob = null
        }

        /** Called by the gateway when a local turn starts. Suspends the idle timer. */
        fun onGenerationStarted() {
            cancelIdleTimer()
        }

        /** Called by the gateway when a local turn ends, however it ended. Restarts the timer. */
        fun onGenerationFinished() {
            scheduleIdleUnload()
        }

        /**
         * The platform's memory callback. **Unloads, unconditionally, above
         * [TRIM_THRESHOLD].**
         *
         * The threshold is `TRIM_MEMORY_RUNNING_LOW` rather than
         * `TRIM_MEMORY_COMPLETE`: by the time the process is at the top of the
         * kill list, the notification has arrived too late to be worth acting
         * on, and the levels below it are the ones where dropping a couple of
         * gigabytes actually changes the outcome.
         *
         * The unload runs on the application scope because
         * [ComponentCallbacks2.onTrimMemory] is a synchronous main-thread
         * callback and freeing a `llama_context` is not a main-thread operation.
         */
        fun onTrimMemory(level: Int) {
            if (level < TRIM_THRESHOLD) return
            if (engine.loadedModel.value == null) return
            Timber.i("Unloading the resident model: onTrimMemory(%d).", level)
            scope.launch {
                withContext(NonCancellable) { unload(UnloadReason.MEMORY_PRESSURE) }
            }
        }

        /** As [onTrimMemory], for the older callback the platform still delivers. */
        fun onLowMemory() {
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        /**
         * Arms the keep-alive timer.
         *
         * A cancellable `delay` rather than an alarm: the whole point is that
         * the next turn cancels it, and a `AlarmManager` entry that has to be
         * cancelled from three places is one that eventually is not.
         */
        private fun scheduleIdleUnload() {
            cancelIdleTimer()
            idleJob = scope.launch {
                val window = KeepAlive.parse(settings.current().keepAlive)
                if (window == KeepAlive.INDEFINITE) return@launch
                if (window > 0L) delay(window)
                // Re-checked after the wait: another turn may have started while
                // this coroutine was asleep, and unloading underneath it would
                // fail the generation the user is watching.
                if (activity.active.value.any { it.isLocal }) return@launch
                performUnload(UnloadReason.IDLE_TIMEOUT)
            }
        }

        private fun notAvailable(): AppError = AppError.Engine.NotAvailable(
            message = "This build has no on-device inference engine, so no model can be loaded.",
        )

        companion object {
            /** See [onTrimMemory]. `RUNNING_LOW` is 10 in `ComponentCallbacks2`. */
            const val TRIM_THRESHOLD: Int = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        }
    }
