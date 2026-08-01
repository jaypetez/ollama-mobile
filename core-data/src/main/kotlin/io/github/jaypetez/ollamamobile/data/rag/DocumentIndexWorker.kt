package io.github.jaypetez.ollamamobile.data.rag

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jaypetez.ollamamobile.common.result.AppResult
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Indexes one document in the background.
 *
 * ## Why WorkManager and not a coroutine on a repository scope
 *
 * Embedding a book-length document is minutes of sustained inference, and the
 * user will put the phone in their pocket during it. A coroutine tied to a
 * ViewModel dies with the screen; one on an application scope survives the
 * screen and then dies with the process, which the system will freeze or kill
 * shortly after the app goes to the background. WorkManager is the only thing on
 * Android that survives both, and it is also the only thing that will retry
 * after a reboot.
 *
 * ## Yielding to the user
 *
 * There is no explicit yield here, and that is deliberate. The worker embeds
 * through [EmbeddingService], which on the local path goes through the engine's
 * `InferenceArbiter` at `Priority.BACKGROUND` — an interactive chat turn
 * preempts it between batches without this class knowing anything about it.
 * Reaching for the arbiter directly is also impossible by construction:
 * `checkModuleGraph` does not let `:core-data` see `:core-llm`, precisely so
 * scheduling policy stays in one place instead of being re-decided by every
 * caller.
 *
 * ## Why unique work per document
 *
 * [ExistingWorkPolicy.KEEP], keyed on the document id. Two workers embedding the
 * same document race on `setEmbedding` for the same chunk rows and burn twice
 * the battery to reach the same state — and a user who taps "retry" twice, or
 * re-imports a file already queued, is the ordinary way to get there.
 */
@HiltWorker
public class DocumentIndexWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val repository: RagRepository,
        private val embeddingServiceProvider: EmbeddingServiceProvider,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            val documentId = inputData.getString(KEY_DOCUMENT_ID)
                ?: return Result.failure(errorData("No document id in the work request."))

            val embeddings = embeddingServiceProvider.current()
                // Retry rather than fail: "no embedding model is loaded" is a
                // transient condition on a device that unloads models under memory
                // pressure, and failing would leave the document permanently red
                // with no way back except a manual retry the user will not find.
                ?: return Result.retry()

            return when (val outcome = repository.index(documentId, embeddings, ::report)) {
                is AppResult.Success -> {
                    Result.success()
                }

                is AppResult.Failure -> {
                    Timber.w("Indexing %s failed: %s", documentId, outcome.error.message)
                    // The repository has already written FAILED and the message to
                    // the row, so the UI can explain itself. Returning failure()
                    // rather than retry() for a typed error is right: an unsupported
                    // format or an empty file will fail identically forever, and
                    // retrying it just spends battery.
                    Result.failure(errorData(outcome.error.message))
                }
            }
        }

        private suspend fun report(done: Int, total: Int) {
            setProgress(workDataOf(KEY_PROGRESS_DONE to done, KEY_PROGRESS_TOTAL to total))
        }

        private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

        public companion object {
            public const val KEY_DOCUMENT_ID: String = "documentId"
            public const val KEY_PROGRESS_DONE: String = "progressDone"
            public const val KEY_PROGRESS_TOTAL: String = "progressTotal"
            public const val KEY_ERROR: String = "error"

            private const val BACKOFF_SECONDS = 30L

            public fun uniqueName(documentId: String): String = "rag-index-$documentId"

            /**
             * Queues indexing for one document.
             *
             * Constrained on battery-not-low only. Deliberately *not* on charging or
             * unmetered network: this is on-device work the user explicitly asked
             * for by importing a file, and making it wait for a charger would mean a
             * document imported in the morning is still unsearchable at lunchtime.
             * Battery-not-low is the one constraint where the system's judgement
             * beats the user's intent.
             */
            public fun enqueue(workManager: WorkManager, documentId: String) {
                val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                    .setInputData(workDataOf(KEY_DOCUMENT_ID to documentId))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(TAG)
                    .build()
                workManager.enqueueUniqueWork(uniqueName(documentId), ExistingWorkPolicy.KEEP, request)
            }

            /** Cancels indexing, e.g. because the document was deleted mid-run. */
            public fun cancel(workManager: WorkManager, documentId: String) {
                workManager.cancelUniqueWork(uniqueName(documentId))
            }

            public const val TAG: String = "rag-index"
        }
    }

/**
 * Supplies the embedding service the worker should use right now.
 *
 * Resolved per run, not injected once, because the answer changes: the user can
 * switch embedding models, unload the local one, or add a server between two
 * runs of the same job. An [EmbeddingService] captured at construction would
 * keep embedding with a model that is no longer loaded, and the failure would
 * surface as a load error inside the engine rather than as "pick a model".
 *
 * Returning null means "nothing can embed at the moment", which the worker
 * treats as retryable.
 */
public interface EmbeddingServiceProvider {
    public suspend fun current(): EmbeddingService?
}
