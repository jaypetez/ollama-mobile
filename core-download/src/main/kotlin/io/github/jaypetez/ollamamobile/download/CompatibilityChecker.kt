package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.storage.KvCacheType
import io.github.jaypetez.ollamamobile.storage.MemoryEstimator
import io.github.jaypetez.ollamamobile.storage.ModelMemoryRequest
import io.github.jaypetez.ollamamobile.storage.gguf.GgufHeaderParser
import io.github.jaypetez.ollamamobile.storage.gguf.GgufSource
import io.github.jaypetez.ollamamobile.storage.gguf.LocalFileGgufSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a model can be run here, and what it will cost.
 *
 * @property blocker non-null means **refuse**: nothing the user can agree to
 *   makes this file loadable on this device.
 * @property verdict a [MemoryVerdict.Tight] is a *warning*, and a warning the
 *   user is allowed to override. Only [MemoryVerdict.Refuse] produces a
 *   [blocker]. Conflating the two either lies to people with borderline devices
 *   or lets them OOM-kill themselves, and the difference is the whole reason the
 *   verdict has three cases instead of a boolean.
 */
public data class CompatibilityReport(
    public val metadata: GgufMetadata,
    public val verdict: MemoryVerdict,
    public val quantization: Quantization?,
    /** The context that was budgeted for, which may be less than the model's maximum. */
    public val contextLength: Int,
    public val blocker: DownloadError? = null,
) {
    /** True when the download or load may proceed, possibly after a warning. */
    public val isAllowed: Boolean get() = blocker == null

    /** True when it may proceed but the user should be told why it is close. */
    public val isTight: Boolean get() = blocker == null && verdict is MemoryVerdict.Tight

    public val explanation: String get() = blocker?.message ?: verdict.explain()
}

/**
 * Reads a GGUF header and answers Fits / Tight / Refuse, honestly.
 *
 * Two separate gates, and they fail for different reasons:
 *
 *  1. **Can ggml read it at all?** A file using a `ggml_type` that was retired
 *     from the format looks perfectly well-formed right up to the JNI boundary,
 *     where ggml aborts the *process* — no Kotlin `try` can catch that. So the
 *     tensor table is validated here, in Kotlin, before any bytes reach native
 *     code.
 *  2. **Will it fit in memory?** Weights plus KV cache plus compute buffer plus
 *     a runtime reserve, against `availMem - threshold`. See `MemoryEstimator`.
 *
 * The tensor check is expensive on a remote file — it forces the whole
 * tensor-info table, megabytes of names on a large model, over range requests —
 * so it is off for the pre-download estimate and on for the file on disk.
 */
@Singleton
public class CompatibilityChecker
    @Inject
    constructor(
        private val parser: GgufHeaderParser,
        private val memoryEstimator: MemoryEstimator,
    ) {
        /**
         * The pre-download check, against a header read over the network.
         *
         * Tensor validation is deliberately off: the point of this call is to
         * spend a few tens of kilobytes deciding whether to spend gigabytes.
         */
        public suspend fun checkRemote(
            source: GgufSource,
            fileName: String,
            requestedContextLength: Int? = null,
            fileSizeBytes: Long? = null,
            kvCacheType: KvCacheType = KvCacheType.F16,
        ): CompatibilityReport = check(
            source = source,
            fileName = fileName,
            requestedContextLength = requestedContextLength,
            fileSizeBytes = fileSizeBytes ?: source.sizeBytes,
            kvCacheType = kvCacheType,
            validateTensorTypes = false,
        )

        /**
         * The pre-load check, against the finished file.
         *
         * Tensor validation is on here, because this is the last moment before
         * the bytes are handed to native code.
         */
        public suspend fun checkLocalFile(
            file: File,
            requestedContextLength: Int? = null,
            kvCacheType: KvCacheType = KvCacheType.F16,
        ): CompatibilityReport = LocalFileGgufSource(file).use { source ->
            check(
                source = source,
                fileName = file.name,
                requestedContextLength = requestedContextLength,
                fileSizeBytes = file.length(),
                kvCacheType = kvCacheType,
                validateTensorTypes = true,
            )
        }

        @Suppress("LongParameterList") // Each parameter is an independent input to the verdict.
        public suspend fun check(
            source: GgufSource,
            fileName: String,
            requestedContextLength: Int?,
            fileSizeBytes: Long?,
            kvCacheType: KvCacheType,
            validateTensorTypes: Boolean,
        ): CompatibilityReport {
            val metadata = try {
                parser.parse(source, validateTensorTypes = validateTensorTypes)
            } catch (e: AppErrorException) {
                return unreadable(e, fileName, fileSizeBytes)
            }

            // The header's own quantisation wins; the filename is a fallback for
            // the converters that omit `general.file_type`.
            val quantization = metadata.quantization ?: Quantization.fromFileName(fileName)
            val context = chooseContext(metadata, requestedContextLength)
            val verdict = memoryEstimator.verdict(
                ModelMemoryRequest(
                    metadata = metadata,
                    contextLength = context,
                    fileSizeBytes = fileSizeBytes,
                    kvCacheType = kvCacheType,
                ),
            )
            return CompatibilityReport(
                metadata = metadata,
                verdict = verdict,
                quantization = quantization,
                contextLength = context,
                // Only Refuse blocks. Tight is a sentence in the UI with a
                // "download anyway" next to it, because on a borderline device
                // the user knows things the estimator does not — that they are
                // about to close every other app, for instance.
                blocker = (verdict as? MemoryVerdict.Refuse)?.let { DownloadError.MemoryRefused(it) },
            )
        }

        /**
         * A header that will not parse is not a memory question.
         *
         * `Unsupported` is the retired-`ggml_type` case and anything else the
         * parser refuses by design; `Corrupt` is a truncated file or an HTML
         * error page wearing a `.gguf` name. Both are hard refusals, and both
         * name the actual reason rather than saying "incompatible".
         */
        private fun unreadable(e: AppErrorException, fileName: String, fileSizeBytes: Long?): CompatibilityReport {
            val reason = when (val error = e.error) {
                is AppError.Model.Unsupported -> error.reason
                else -> error.message
            }
            return CompatibilityReport(
                metadata = GgufMetadata(architecture = UNKNOWN_ARCHITECTURE),
                verdict = MemoryVerdict.Refuse(
                    requiredBytes = fileSizeBytes ?: 0L,
                    availableBytes = 0L,
                    reason = reason,
                ),
                quantization = Quantization.fromFileName(fileName),
                contextLength = 0,
                blocker = DownloadError.Incompatible(reason = reason, cause = e),
            )
        }

        /**
         * Budget for what will actually be allocated.
         *
         * Never more than the model was trained for — a longer context than
         * `<arch>.context_length` is not usable — and never the trained maximum
         * by default either: modern models advertise 128k or more, and budgeting
         * a 128k KV cache would refuse every model on every phone.
         */
        private fun chooseContext(metadata: GgufMetadata, requested: Int?): Int {
            val trained = metadata.contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_LENGTH
            val wanted = requested?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_LENGTH
            return minOf(trained, wanted)
        }

        public companion object {
            /**
             * The context a fresh install allocates. A deliberate policy value,
             * not a model property: it is what the memory verdict is computed
             * against before the user has chosen anything.
             */
            public const val DEFAULT_CONTEXT_LENGTH: Int = 4096

            private const val UNKNOWN_ARCHITECTURE = "unknown"
        }
    }
