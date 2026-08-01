package io.github.jaypetez.ollamamobile.remote.modelfile

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.common.result.map
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.ModelDefinition
import io.github.jaypetez.ollamamobile.remote.ModelDetails
import io.github.jaypetez.ollamamobile.remote.OllamaClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parameters of a model definition, structured.
 *
 * A multimap because the format is one: `stop` appears once per stop sequence,
 * and collapsing the repeats into a single value silently deletes all but the
 * last one — which shows up much later as a model that will not stop
 * generating.
 */
data class ModelParameters(
    val values: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = values.isEmpty()

    /** The single value for [key], or null. The last wins for a key that was repeated by mistake. */
    fun single(key: String): String? = values[key]?.lastOrNull()

    fun all(key: String): List<String> = values[key].orEmpty()

    /** The stop sequences, already unquoted. */
    val stop: List<String> get() = all("stop")

    fun with(key: String, value: String): ModelParameters =
        copy(values = values + (key to listOf(value)))

    fun without(key: String): ModelParameters = copy(values = values - key)

    /**
     * The `parameters` object for `POST /api/create`.
     *
     * Types are inferred back from the text because the blob threw them away:
     * `temperature 0.7` has to go back as the number `0.7`, not the string
     * `"0.7"`, or the server rejects it. A repeated key becomes an array,
     * which is how `stop` is expressed in the structured API.
     */
    fun toWireMap(): Map<String, JsonElement> = values.mapValues { (_, raw) ->
        if (raw.size > 1) JsonArray(raw.map(::typed)) else typed(raw.first())
    }

    private fun typed(raw: String): JsonPrimitive = when {
        raw.equals("true", ignoreCase = true) -> JsonPrimitive(true)

        raw.equals("false", ignoreCase = true) -> JsonPrimitive(false)

        else -> raw.toLongOrNull()?.let(::JsonPrimitive)
            ?: raw.toDoubleOrNull()?.let(::JsonPrimitive)
            ?: JsonPrimitive(raw)
    }

    companion object {
        val Empty: ModelParameters = ModelParameters()

        /**
         * Parses the unstructured `parameters` text that `/api/show` returns.
         *
         * The format is one `key<whitespace>value` pair per line, with values
         * optionally double-quoted — quotes that matter, because a stop
         * sequence is `"<|im_end|>"` and the quotes are not part of it.
         */
        fun parse(blob: String?): ModelParameters {
            if (blob.isNullOrBlank()) return Empty
            val collected = LinkedHashMap<String, MutableList<String>>()
            blob.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
                val key = trimmed.takeWhile { !it.isWhitespace() }
                val value = trimmed.drop(key.length).trim()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                collected.getOrPut(key.lowercase()) { mutableListOf() }.add(unquote(value))
            }
            return ModelParameters(collected.mapValues { (_, list) -> list.toList() })
        }

        private fun unquote(value: String): String =
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
            } else {
                value
            }
    }
}

/** A model definition the user is editing. */
data class ModelfileDraft(
    /** The name being edited. Saving under the same name replaces the model on the server. */
    val model: String,
    /**
     * The base the new definition derives from.
     *
     * `/api/show` does not report a `FROM`, so this defaults to the model being
     * edited: the structured create then layers the edited system prompt and
     * parameters over the existing weights rather than re-importing them.
     */
    val from: String,
    val system: String? = null,
    val template: String? = null,
    val license: String? = null,
    val parameters: ModelParameters = ModelParameters.Empty,
) {
    internal fun toDefinition(): ModelDefinition = ModelDefinition(
        model = model,
        from = from,
        system = system,
        template = template,
        license = license,
        parameters = parameters,
    )

    companion object {
        fun from(details: ModelDetails): ModelfileDraft = ModelfileDraft(
            model = details.model,
            from = details.model,
            system = details.system,
            template = details.template,
            license = details.license,
            parameters = details.parameters,
        )
    }
}

/**
 * Reads and edits a model definition on a remote server.
 *
 * ## This is a form, not a text editor, and it has to be
 *
 * The obvious design — show the Modelfile, let the user edit the text, post it
 * back — cannot be built against the API as it exists:
 *
 *  * **`/api/show` does not return a Modelfile in any round-trippable form.**
 *    Its `parameters` field is an unstructured text blob (`stop "<|im_end|>"`,
 *    one pair per line, quoting rules nowhere specified) and its `modelfile`
 *    field, where present, is a *reconstruction* containing comments and a
 *    `FROM` pointing at a blob digest on that server's disk. It is a
 *    description, not a source file.
 *  * **`/api/create` no longer accepts a raw modelfile string.** The `modelfile`
 *    request field was removed; the endpoint takes structured fields —
 *    `from`, `system`, `template`, `parameters`, `license`, `adapters`. There
 *    is nowhere to post edited text to.
 *
 * So the round trip is: parse the blob into [ModelParameters], present typed
 * fields, and write back through the structured create API. A text editor
 * would be a lie about what the server can accept, and the failure would land
 * on the user as an opaque 400 after they had typed a page of configuration.
 */
@Singleton
class ModelfileService
    @Inject
    constructor(
        private val client: OllamaClient,
    ) {
        /** Loads [model] from [server] and turns it into an editable draft. */
        suspend fun load(server: ServerRef, model: String): AppResult<ModelfileDraft> =
            client.showModel(server, model).map(ModelfileDraft::from)

        /** Writes [draft] back through the structured create API. */
        suspend fun save(server: ServerRef, draft: ModelfileDraft): AppResult<Unit> =
            client.createModel(server, draft.toDefinition())
    }
