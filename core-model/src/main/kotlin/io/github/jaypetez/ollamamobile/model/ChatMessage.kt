package io.github.jaypetez.ollamamobile.model

/**
 * One message in a conversation. Immutable: a streaming update produces a new
 * instance rather than mutating one, so a Compose snapshot and a database row
 * can never disagree about what the message currently says.
 */
public data class ChatMessage(
    public val id: MessageId,
    public val conversationId: ConversationId,
    public val role: Role,
    public val content: String,
    /**
     * Epoch milliseconds. Deliberately a `Long` and not `java.time.Instant`:
     * this is what Room stores, what the wire carries, and what sorts without
     * a converter. There is no default because a model type must not read a
     * clock — the caller owns time.
     */
    public val createdAt: Long,
    /**
     * Chain-of-thought emitted inside `<think>` blocks by reasoning models,
     * already stripped out of [content].
     *
     * Kept separate rather than inlined because it is collapsed by default in
     * the UI, is excluded when the conversation is replayed as context, and can
     * be an order of magnitude longer than the answer itself.
     */
    public val reasoning: String? = null,
    public val status: MessageStatus = MessageStatus.Complete,
    /** Populated on the terminal chunk; null while streaming and on failure. */
    public val stats: GenerationStats? = null,
    public val attachments: List<AttachmentRef> = emptyList(),
) {
    /** True while tokens are still arriving for this message. */
    public val isStreaming: Boolean
        get() = status is MessageStatus.Pending
}

/**
 * Where a streaming message is in its life cycle.
 *
 * A message that is still being generated has to be representable, or the UI
 * ends up inferring "in progress" from an empty content string — which is
 * indistinguishable from a model that legitimately returned nothing, and from a
 * message whose first token has not landed yet.
 */
public sealed interface MessageStatus {
    /** Created, tokens may still be arriving. */
    public data object Pending : MessageStatus

    /** Finished normally. */
    public data object Complete : MessageStatus

    /**
     * Generation failed. Any [ChatMessage.content] already accumulated stays —
     * a partial answer plus an explanation is more useful than a blank bubble.
     */
    public data class Failed(
        public val error: AppError,
    ) : MessageStatus
}

/**
 * A pointer to something attached to a message.
 *
 * The bytes are never held here. [uri] is resolved by `:core-storage` (a
 * content URI, or a path inside the app's private files dir); keeping a
 * multi-megabyte image in the message object would put it in the Compose
 * snapshot, in every recomposition and in the Room row.
 */
public data class AttachmentRef(
    public val id: String,
    public val kind: AttachmentKind,
    public val uri: String,
    public val mimeType: String? = null,
    public val sizeBytes: Long? = null,
    public val displayName: String? = null,
    /** Set once the attachment has been ingested into the RAG index. */
    public val documentId: DocumentId? = null,
)

public enum class AttachmentKind {
    /** Sent to the model as an image; requires [ModelCapability.VISION]. */
    IMAGE,

    /** Text-bearing file, chunked and embedded for retrieval. */
    DOCUMENT,

    /** Attached for the record; not sent to the model. */
    OTHER,
}
