package io.github.jaypetez.ollamamobile.model

/**
 * A chat thread and the settings it was configured with.
 *
 * The settings are stored on the conversation, not read from global preferences
 * at send time: reopening an old thread must reproduce the model and sampling
 * it actually ran with, otherwise the transcript stops matching its own
 * history the moment the user changes a default.
 */
public data class Conversation(
    public val id: ConversationId,
    public val title: String,
    /** Epoch milliseconds; see the note on [ChatMessage.createdAt]. */
    public val createdAt: Long,
    /** Epoch milliseconds of the last message or edit. Drives list ordering. */
    public val updatedAt: Long,
    /** Null for a thread whose model has been deleted or not yet chosen. */
    public val modelId: ModelId? = null,
    /** Null means send no system message at all, which is not the same as "". */
    public val systemPrompt: String? = null,
    public val sampling: SamplingParams = SamplingParams.Default,
)
