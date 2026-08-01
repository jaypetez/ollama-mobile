package io.github.jaypetez.ollamamobile.server

import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.remote.dto.ModelDetails
import io.github.jaypetez.ollamamobile.remote.dto.OllamaModel
import io.github.jaypetez.ollamamobile.remote.dto.OpenAiModel
import io.github.jaypetez.ollamamobile.remote.dto.ShowResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Projections from the app's own ModelRef onto the two wire vocabularies.
 *
 * These live in :server rather than in :core-remote because :core-remote maps
 * the other direction — it *reads* someone else's server. Sharing one file
 * would put an encode path and a decode path on the same types and invite a
 * future edit to satisfy one while breaking the other.
 */

/**
 * A stable content-addressed-looking digest.
 *
 * Clients display it and use it to notice that a tag changed underneath them,
 * so it must be stable for a given model and must change when the model does.
 * It is **not** the digest of the weights — this server does not hash
 * multi-gigabyte files on every `/api/tags` — it is a digest of the identity
 * that names them. The `sha256:` prefix is what clients expect to see.
 */
fun ModelRef.syntheticDigest(): String {
    val identity = buildString {
        append(id.value)
        append('|')
        append(name)
        append('|')
        append(sizeBytes ?: -1L)
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    return "sha256:" + bytes.joinToString("") { byte -> "%02x".format(byte) }
}

fun ModelRef.toModelDetails(): ModelDetails = ModelDetails(
    format = "gguf",
    family = name.substringBefore(':').substringAfterLast('/'),
    families = listOf(name.substringBefore(':').substringAfterLast('/')),
    parameterSize = parameterCount?.let(::humanParameterCount),
    quantizationLevel = quantization?.name,
)

fun ModelRef.toOllamaModel(modifiedAt: String = EPOCH_RFC3339): OllamaModel = OllamaModel(
    name = name,
    model = name,
    modifiedAt = modifiedAt,
    size = sizeBytes,
    digest = syntheticDigest(),
    details = toModelDetails(),
)

fun ModelRef.toOpenAiModel(createdEpochSeconds: Long = 0L): OpenAiModel = OpenAiModel(
    id = name,
    objectType = "model",
    created = createdEpochSeconds,
    ownedBy = when (origin) {
        is ModelOrigin.Local -> "library"
        is ModelOrigin.Remote -> "library"
        is ModelOrigin.Catalog -> "library"
    },
)

/**
 * The `/api/show` payload.
 *
 * `capabilities` is the field that changes client behaviour: the Ollama Python
 * client and several UIs decide whether to offer tools or image upload from it,
 * so an empty list here silently disables working features.
 */
fun ModelRef.toShowResponse(): ShowResponse = ShowResponse(
    details = toModelDetails(),
    template = chatTemplate,
    modelInfo = buildModelInfo(),
    capabilities = capabilities.mapNotNull { it.wireCapability() }.sorted().ifEmpty { listOf("completion") },
    modifiedAt = EPOCH_RFC3339,
)

/**
 * Ollama's capability vocabulary, which is not the enum's.
 *
 * [ModelCapability.CHAT] maps to `"completion"` — the name Ollama uses for
 * "can generate text at all" — and there is no wire name for anything else we
 * might add later, hence the nullable return rather than a total `when` that a
 * new enum constant would silently mistranslate.
 */
private fun ModelCapability.wireCapability(): String? = when (this) {
    ModelCapability.CHAT -> "completion"
    ModelCapability.EMBEDDING -> "embedding"
    ModelCapability.VISION -> "vision"
    ModelCapability.TOOLS -> "tools"
    ModelCapability.REASONING -> "thinking"
}

private fun ModelRef.buildModelInfo(): JsonObject {
    val fields = buildMap<String, JsonPrimitive> {
        parameterCount?.let { put("general.parameter_count", JsonPrimitive(it)) }
        contextLength?.let { put("general.context_length", JsonPrimitive(it)) }
        quantization?.let { put("general.file_type", JsonPrimitive(it.name)) }
        put("general.architecture", JsonPrimitive(name.substringBefore(':').substringAfterLast('/')))
    }
    return JsonObject(fields)
}

/** `1_700_000_000` -> `"1.7B"`, the way Ollama prints it. */
private fun humanParameterCount(count: Long): String = when {
    count >= BILLION -> "%.1fB".format(count.toDouble() / BILLION)
    count >= MILLION -> "%.0fM".format(count.toDouble() / MILLION)
    else -> count.toString()
}

private const val BILLION = 1_000_000_000.0
private const val MILLION = 1_000_000.0

/**
 * The `modified_at` this server reports.
 *
 * A fixed epoch rather than "now": a client that caches on `modified_at` would
 * re-download on every poll if the value moved, and this server has no honest
 * modification time for a model it does not own the file for.
 */
internal val EPOCH_RFC3339: String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.EPOCH.atOffset(ZoneOffset.UTC))

/** `created_at` for a response, in the RFC 3339 spelling the native API uses. */
internal fun ServerClock.nowRfc3339(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMillis()).atOffset(ZoneOffset.UTC))

/** `created` for a `/v1` response, which is epoch **seconds** rather than a string. */
internal fun ServerClock.nowEpochSeconds(): Long = nowMillis() / MILLIS_PER_SECOND

private const val MILLIS_PER_SECOND = 1_000L
