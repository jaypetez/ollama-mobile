package io.github.jaypetez.ollamamobile.data.export

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.repository.ConversationRepository
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A conversation as a file.
 *
 * A dedicated DTO rather than serialising [Conversation] and [ChatMessage]
 * directly: the domain types are free to change shape with the app, and an
 * export written last year has to keep parsing. [schemaVersion] is what makes
 * that a decision rather than a hope.
 */
@Serializable
data class ConversationExport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    val sampling: SamplingExport = SamplingExport(),
    val messages: List<MessageExport> = emptyList(),
) {
    companion object {
        /** Bump on any breaking change, and keep a reader for the old value. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Every field nullable, because null means "engine default" and is not zero. */
@Serializable
data class SamplingExport(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val repeatLastN: Int? = null,
    val seed: Long? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val stop: List<String> = emptyList(),
)

@Serializable
data class MessageExport(
    val id: String,
    /** `Role.wireName`, so an unknown role from a third-party file survives the round trip. */
    val role: String,
    val content: String,
    val createdAt: Long,
    val reasoning: String? = null,
    val status: String = STATUS_COMPLETE,
    val error: String? = null,
    val stats: StatsExport? = null,
) {
    companion object {
        const val STATUS_COMPLETE: String = "complete"
        const val STATUS_PENDING: String = "pending"
        const val STATUS_FAILED: String = "failed"
    }
}

/** Absent fields stay absent: see [GenerationStats] on why zero is not the same as unreported. */
@Serializable
data class StatsExport(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val promptEvalNanos: Long? = null,
    val evalNanos: Long? = null,
    val loadNanos: Long? = null,
    val totalNanos: Long? = null,
)

/**
 * Export and import of a conversation, as Markdown or JSON.
 *
 * Markdown is for a human — pasted into an issue, a note, a review. JSON is the
 * lossless one and is the only format [importJson] reads back: Markdown throws
 * away statuses, counters and the sampling settings, and a "round trip" that
 * silently loses them is worse than one format that cannot be imported at all.
 *
 * Neither format's labels come from `strings.xml`, and that is deliberate. They
 * are part of a *file format*, not of the UI: a Markdown export whose headings
 * change with the device language stops being diffable against last month's,
 * and a JSON key that is translated is not a key. Everything the user reads
 * *about* an export — the share sheet, the success message, the error — is UI
 * text and belongs in resources.
 */
@Singleton
class ConversationExporter
    @Inject
    constructor(
        private val conversations: ConversationRepository,
    ) {
        /** Collects a conversation into its file representation. */
        suspend fun export(id: ConversationId): AppResult<ConversationExport> {
            val conversation = conversations.findConversation(id)
                ?: return AppResult.Failure(AppError.Storage.NotFound(what = "conversation ${id.value}"))
            val messages = conversations.messages(id, limit = Int.MAX_VALUE)
            return AppResult.Success(conversation.toExport(messages))
        }

        suspend fun toJson(id: ConversationId): AppResult<String> = when (val result = export(id)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(renderJson(result.value))
        }

        suspend fun toMarkdown(id: ConversationId): AppResult<String> = when (val result = export(id)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(renderMarkdown(result.value))
        }

        fun renderJson(export: ConversationExport): String =
            json.encodeToString(ConversationExport.serializer(), export)

        /**
         * Parses an export file.
         *
         * A malformed file is a failure, never a partially populated object: a
         * half-read transcript imported as if it were whole is data loss the
         * user cannot see.
         */
        fun parseJson(text: String): AppResult<ConversationExport> = try {
            val parsed = json.decodeFromString(ConversationExport.serializer(), text)
            if (parsed.schemaVersion > ConversationExport.SCHEMA_VERSION) {
                AppResult.Failure(
                    AppError.Storage.Io(
                        message = "This export was written by a newer version of the app " +
                            "(format ${parsed.schemaVersion}).",
                    ),
                )
            } else {
                AppResult.Success(parsed)
            }
        } catch (e: SerializationException) {
            AppResult.Failure(AppError.Storage.Io(message = "This file is not a conversation export.", cause = e))
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(AppError.Storage.Io(message = "This conversation export is damaged.", cause = e))
        }

        /** Parses and writes a copy of the thread. Returns the id of the new conversation. */
        suspend fun importJson(text: String): AppResult<ConversationId> = when (val parsed = parseJson(text)) {
            is AppResult.Failure -> parsed
            is AppResult.Success -> AppResult.Success(importExport(parsed.value))
        }

        suspend fun importExport(export: ConversationExport): ConversationId = conversations.importConversation(
            conversation = export.toConversation(),
            messages = export.toMessages(),
        )

        /**
         * Markdown, for a human.
         *
         * The metadata goes in a YAML front-matter block because every Markdown
         * viewer that does not understand it simply shows it, while the ones
         * that do (GitHub gists, Obsidian, static site generators) render it as
         * a table — as opposed to a bare paragraph of `key: value` lines, which
         * nothing understands and everything reflows.
         */
        fun renderMarkdown(export: ConversationExport): String = buildString {
            appendLine("---")
            appendLine("title: ${export.title.escapeYaml()}")
            export.modelId?.let { appendLine("model: ${it.escapeYaml()}") }
            appendLine("exported_by: OllamaMobile")
            appendLine("messages: ${export.messages.size}")
            appendLine("---")
            appendLine()
            appendLine("# ${export.title}")

            export.systemPrompt?.let { prompt ->
                appendLine()
                appendLine("> **System**")
                appendLine(">")
                prompt.lineSequence().forEach { appendLine("> $it") }
            }

            export.messages.forEach { message ->
                appendLine()
                appendLine("## ${message.role.heading()}")
                appendLine()
                // A <details> block, not a blockquote: reasoning is routinely
                // longer than the answer, and an export that opens with a
                // screenful of chain-of-thought buries the thing being shared.
                message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    appendLine("<details><summary>Reasoning</summary>")
                    appendLine()
                    appendLine(reasoning.trimEnd())
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
                appendLine(message.content.trimEnd().ifEmpty { "*(no content)*" })
                if (message.status == MessageExport.STATUS_FAILED) {
                    appendLine()
                    appendLine("> **This response did not finish.** ${message.error.orEmpty()}".trimEnd())
                }
            }
        }

        /**
         * A filename for the share sheet.
         *
         * Sanitised to the intersection of what FAT32, ext4 and the Storage
         * Access Framework accept, because an export the user cannot save is
         * not an export.
         */
        fun suggestedFileName(export: ConversationExport, extension: String): String {
            val slug = export.title
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
                .take(MAX_SLUG_CHARS)
                .ifEmpty { "conversation" }
            return "$slug.$extension"
        }

        private companion object {
            const val MAX_SLUG_CHARS = 60

            /**
             * `prettyPrint` because these files are read and diffed by people,
             * and `explicitNulls = false` so an absent counter stays absent
             * rather than being written as `null` — the file is the record of
             * what the server reported, and it reported nothing.
             */
            val json = Json {
                prettyPrint = true
                explicitNulls = false
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        }
    }

// ---------------------------------------------------------------------------
// Domain <-> export
// ---------------------------------------------------------------------------

internal fun Conversation.toExport(messages: List<ChatMessage>): ConversationExport = ConversationExport(
    id = id.value,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId?.value,
    systemPrompt = systemPrompt,
    sampling = sampling.toExport(),
    messages = messages.map { it.toExport() },
)

internal fun SamplingParams.toExport(): SamplingExport = SamplingExport(
    temperature = temperature,
    topP = topP,
    topK = topK,
    minP = minP,
    repeatPenalty = repeatPenalty,
    repeatLastN = repeatLastN,
    seed = seed,
    numPredict = numPredict,
    numCtx = numCtx,
    stop = stop,
)

internal fun SamplingExport.toDomain(): SamplingParams = SamplingParams(
    temperature = temperature,
    topP = topP,
    topK = topK,
    minP = minP,
    repeatPenalty = repeatPenalty,
    repeatLastN = repeatLastN,
    seed = seed,
    numPredict = numPredict,
    numCtx = numCtx,
    stop = stop,
)

internal fun ChatMessage.toExport(): MessageExport = MessageExport(
    id = id.value,
    role = role.wireName,
    content = content,
    createdAt = createdAt,
    reasoning = reasoning,
    status = when (status) {
        is MessageStatus.Pending -> MessageExport.STATUS_PENDING
        is MessageStatus.Complete -> MessageExport.STATUS_COMPLETE
        is MessageStatus.Failed -> MessageExport.STATUS_FAILED
    },
    error = (status as? MessageStatus.Failed)?.error?.message,
    stats = stats?.toExport(),
)

internal fun GenerationStats.toExport(): StatsExport = StatsExport(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    promptEvalNanos = promptEvalNanos,
    evalNanos = evalNanos,
    loadNanos = loadNanos,
    totalNanos = totalNanos,
)

internal fun StatsExport.toDomain(): GenerationStats = GenerationStats(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    promptEvalNanos = promptEvalNanos,
    evalNanos = evalNanos,
    loadNanos = loadNanos,
    totalNanos = totalNanos,
)

internal fun ConversationExport.toConversation(): Conversation = Conversation(
    id = ConversationId(id),
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId?.let(::ModelId),
    systemPrompt = systemPrompt,
    sampling = sampling.toDomain(),
)

internal fun ConversationExport.toMessages(): List<ChatMessage> = messages.map { message ->
    ChatMessage(
        id = MessageId(message.id),
        conversationId = ConversationId(id),
        role = Role.fromWire(message.role),
        content = message.content,
        createdAt = message.createdAt,
        reasoning = message.reasoning,
        status = when (message.status) {
            MessageExport.STATUS_PENDING -> MessageStatus.Pending

            MessageExport.STATUS_FAILED -> MessageStatus.Failed(
                AppError.Unexpected(message = message.error ?: "The response could not be completed."),
            )

            else -> MessageStatus.Complete
        },
        stats = message.stats?.toDomain()?.takeUnless { it.isEmpty },
    )
}

private fun String.heading(): String = when (Role.fromWireOrNull(this)) {
    Role.SYSTEM -> "System"

    Role.USER -> "User"

    Role.ASSISTANT -> "Assistant"

    Role.TOOL -> "Tool"

    // An unrecognised role from a third-party file is shown as it came, rather
    // than relabelled into one of ours.
    null -> this
}

/**
 * Quotes a YAML scalar when it would otherwise change the document's meaning.
 *
 * A title containing `: ` splits a front-matter line into two keys, and one
 * starting with `#` becomes a comment — either way the block stops parsing and
 * the reader shows raw text where a table should be.
 */
private fun String.escapeYaml(): String {
    val needsQuoting = isEmpty() ||
        first().isWhitespace() ||
        last().isWhitespace() ||
        any { it in YAML_SPECIALS } ||
        first() in YAML_LEADING_SPECIALS
    if (!needsQuoting) return this
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}

private val YAML_SPECIALS = charArrayOf(':', '#', '\n', '"', '\'')

private val YAML_LEADING_SPECIALS = charArrayOf('-', '?', '*', '&', '!', '|', '>', '%', '@', '`', '[', '{')
