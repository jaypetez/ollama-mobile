package io.github.jaypetez.ollamamobile.download.hf

import io.github.jaypetez.ollamamobile.model.GgufMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `lfs` block on a tree entry.
 *
 * [oid] is the **SHA-256 of the content** and is the only integrity reference
 * this app uses. It is not the entry's top-level `oid`, which is a git blob
 * SHA-1 of the *pointer file*, and it is not any `ETag` seen during a download.
 */
@Serializable
public data class HfLfsInfo(
    public val oid: String? = null,
    public val size: Long? = null,
    public val pointerSize: Long? = null,
)

/** One entry from `/api/models/<repo>/tree/<rev>`. */
@Serializable
public data class HfTreeEntry(
    public val type: String = TYPE_FILE,
    public val path: String,
    /** Bytes. For an LFS file this is the real content length, not the pointer's. */
    public val size: Long = 0,
    /** Git object id. A SHA-1 of the pointer for LFS files — never an integrity hash. */
    public val oid: String? = null,
    public val lfs: HfLfsInfo? = null,
) {
    public val isFile: Boolean get() = type == TYPE_FILE

    public val isGguf: Boolean get() = path.endsWith(GGUF_SUFFIX, ignoreCase = true)

    /** The content SHA-256, when the file is LFS-backed. Small files are not. */
    public val sha256: String? get() = lfs?.oid

    public val contentSize: Long get() = lfs?.size ?: size
}

// Top-level rather than in a companion: kotlinx.serialization generates
// `serializer()` onto whatever companion the class already has, so a private one
// makes the generated accessor private too and nothing can decode the type.
private const val TYPE_FILE = "file"
private const val GGUF_SUFFIX = ".gguf"

/**
 * The Hub's own precomputed GGUF metadata.
 *
 * The Hub parses the header server-side and publishes the result on the model
 * info endpoint. Preferring it over a range read is worth doing: it is one small
 * JSON response instead of a chain of range requests that grow to megabytes when
 * the file carries a large chat template or a 150k-entry tokeniser.
 *
 * It is not a complete substitute — there is no `block_count`, no
 * `attention.head_count_kv`, and those are what the KV-cache estimate is
 * proportional to — so a caller that needs the memory arithmetic still has to
 * fall back to reading the header. See `HuggingFaceApi.headerMetadata`.
 */
@Serializable
public data class HfGgufInfo(
    /** Parameter count, the Hub's `total`. */
    public val total: Long? = null,
    public val architecture: String? = null,
    @SerialName("context_length")
    public val contextLength: Int? = null,
    @SerialName("chat_template")
    public val chatTemplate: String? = null,
    @SerialName("bos_token")
    public val bosToken: String? = null,
    @SerialName("eos_token")
    public val eosToken: String? = null,
) {
    /** Null when the Hub did not identify an architecture, which is the one required field. */
    public fun toGgufMetadata(): GgufMetadata? = architecture?.let {
        GgufMetadata(
            architecture = it,
            parameterCount = total,
            contextLength = contextLength,
            chatTemplate = chatTemplate,
        )
    }
}

@Serializable
public data class HfSibling(
    @SerialName("rfilename")
    public val fileName: String,
)

/** A model as `/api/models` and `/api/models/<repo>` describe it. */
@Serializable
public data class HfModelInfo(
    public val id: String,
    public val author: String? = null,
    /** The commit SHA of the revision that was queried. Worth pinning a catalogue entry to. */
    public val sha: String? = null,
    public val downloads: Long? = null,
    public val likes: Long? = null,
    public val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag")
    public val pipelineTag: String? = null,
    @SerialName("lastModified")
    public val lastModified: String? = null,
    /**
     * `false`, `"auto"` or `"manual"` — a JSON field that is sometimes a boolean
     * and sometimes a string, so it is kept raw and interpreted by [isGated]
     * rather than being forced into a type it does not have.
     */
    public val gated: JsonElement? = null,
    @SerialName("private")
    public val isPrivate: Boolean = false,
    public val siblings: List<HfSibling> = emptyList(),
    public val gguf: HfGgufInfo? = null,
) {
    /** True for both `"auto"` and `"manual"` gating; either needs the licence accepting first. */
    public val isGated: Boolean
        get() = when (val value = gated) {
            null -> false
            is JsonPrimitive -> value.content != "false" && value.content.isNotEmpty()
            else -> true
        }

    public val ggufFileNames: List<String>
        get() = siblings.map { it.fileName }.filter { it.endsWith(".gguf", ignoreCase = true) }

    public val hasGguf: Boolean get() = ggufFileNames.isNotEmpty()
}
