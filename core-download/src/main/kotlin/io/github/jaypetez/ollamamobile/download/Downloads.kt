package io.github.jaypetez.ollamamobile.download

import io.github.jaypetez.ollamamobile.model.ModelId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The one [Json] this module encodes with.
 *
 * Two very different things go through it — the Hub's API responses and this
 * module's own on-disk sidecars — and both want the same settings:
 * `ignoreUnknownKeys` because the Hub adds fields between deploys and a
 * sidecar written by an older build must still load, and `explicitNulls = false`
 * so an absent field and a null one are the same thing.
 */
internal val DownloadJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    encodeDefaults = true
    coerceInputValues = false
}

/**
 * `ModelId` is a value class in `:core-model`, which has no serialization
 * plugin applied and should not grow one just for this module's wire format.
 */
internal object ModelIdSerializer : KSerializer<ModelId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.jaypetez.ollamamobile.model.ModelId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ModelId): Unit = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): ModelId = ModelId(decoder.decodeString())
}

/** Where a download's bytes come from. */
@Serializable
public sealed interface DownloadSource {
    /**
     * The directory, relative to `filesDir/models`, that this source's files
     * land in.
     *
     * A directory per model rather than a flat namespace, because the shards of
     * a split GGUF have to sit next to each other under their original names —
     * see [ShardedModelResolver] — and because deleting a model should be a
     * recursive delete of one directory rather than a glob over filenames.
     */
    public val storageDir: String

    /** Where the licence, and the thing being trusted, can be seen by the user. */
    public val originLabel: String

    @Serializable
    @SerialName("huggingface")
    public data class HuggingFace(
        /** `owner/name`. */
        public val repo: String,
        /**
         * A commit SHA whenever the catalogue has one.
         *
         * `main` is a moving pointer: the bytes behind it can change between
         * the day a hash was recorded and the day a user downloads, and the
         * resulting mismatch is indistinguishable from corruption.
         */
        public val revision: String = DEFAULT_REVISION,
    ) : DownloadSource {
        override val storageDir: String get() = repo
        override val originLabel: String get() = "huggingface.co/$repo"

        public companion object {
            public const val DEFAULT_REVISION: String = "main"
        }
    }

    /** An arbitrary URL the user pasted. See [CustomUrlSource]. */
    @Serializable
    @SerialName("url")
    public data class CustomUrl(
        public val url: String,
        /** Derived once by [CustomUrlSource] so the path is stable across resumes. */
        override val storageDir: String,
        override val originLabel: String,
    ) : DownloadSource
}

/**
 * One file to fetch.
 *
 * [sizeBytes] and [sha256] are nullable because a pasted URL supplies neither,
 * and because an unverified catalogue entry has to be resolved against the tree
 * API before either is known. When [sha256] is null the transfer still runs, but
 * it can only assert the GGUF magic and the declared length — which is stated
 * plainly in the UI rather than presented as a verified download.
 */
@Serializable
public data class RemoteFile(
    /** The name the file keeps on disk. Load-bearing for shards. */
    public val fileName: String,
    public val sizeBytes: Long? = null,
    /** The LFS `oid`: a SHA-256 hex digest. Never a CDN `ETag`. */
    public val sha256: String? = null,
)

/** Everything the worker needs to perform, resume and verify a download. */
@Serializable
public data class DownloadRequest(
    @Serializable(with = ModelIdSerializer::class)
    public val modelId: ModelId,
    public val displayName: String,
    public val source: DownloadSource,
    /**
     * Every file in the set. For a sharded model this is all shards, because
     * the set is one unit of work — see [ShardedModelResolver].
     */
    public val files: List<RemoteFile>,
    /**
     * Default on. A four-gigabyte download over cellular is a real bill, and a
     * user who wants it anyway can say so per download.
     */
    public val requireUnmeteredNetwork: Boolean = true,
    /** Default on. Filling the data partition degrades the whole device, not just this app. */
    public val requireStorageNotLow: Boolean = true,
) {
    /** Sum of the declared sizes, or null when any file's size is still unknown. */
    public val totalBytes: Long?
        get() = files.fold<RemoteFile, Long?>(0L) { acc, file ->
            val size = file.sizeBytes
            if (acc == null || size == null) null else acc + size
        }

    /** The shard that the loader is pointed at, or the only file. */
    public val primaryFileName: String
        get() = ShardedModelResolver.primaryOf(files.map { it.fileName }) ?: files.first().fileName
}

/** What a download is doing right now. */
public enum class DownloadStatus {
    QUEUED,
    RUNNING,

    /** Hashing the finished bytes. Deliberately visible: it is not instant on a 4 GB file. */
    VERIFYING,

    /** Cancelled by the user with the partial bytes kept, so resuming is cheap. */
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * A progress snapshot for one model.
 *
 * Progress is reported across the whole set, never per shard: a bar that
 * reaches 100% three times is a bug report.
 */
public data class DownloadProgress(
    public val modelId: ModelId,
    public val status: DownloadStatus,
    public val bytesDownloaded: Long = 0,
    public val totalBytes: Long? = null,
    /** 1-based, for "part 2 of 5" in the UI. */
    public val fileIndex: Int = 0,
    public val fileCount: Int = 1,
    public val currentFileName: String? = null,
    /**
     * Set when a server answered 200 to a ranged request, so the transfer had
     * to begin again from byte zero. Surfaced because otherwise the user just
     * watches the bar go backwards with no explanation.
     */
    public val restartedFromZero: Boolean = false,
    public val error: DownloadError? = null,
) {
    /** 0.0..1.0, or null when the total is not yet known. */
    public val fraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0 }
            ?.let { (bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    public val isTerminal: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED
}
