package io.github.jaypetez.ollamamobile.model

import java.util.UUID

// Every identifier in this app is a String underneath, and almost all of them
// are opaque UUIDs — so the compiler cannot tell a ModelId from a ServerId if
// both are declared as `String`. Passing the wrong one compiles, ships, and
// fails at runtime as a "model not found" against a server row. Wrapping each
// in a @JvmInline value class makes that mixup a compile error while erasing to
// the same String at runtime: no allocation, no Room converter beyond the
// one-line unwrap, no cost anywhere.

/** Identifies a conversation (a chat thread). */
@JvmInline
public value class ConversationId(
    public val value: String,
) {
    public override fun toString(): String = value

    public companion object {
        public fun random(): ConversationId = ConversationId(UUID.randomUUID().toString())
    }
}

/** Identifies a single message inside a conversation. */
@JvmInline
public value class MessageId(
    public val value: String,
) {
    public override fun toString(): String = value

    public companion object {
        public fun random(): MessageId = MessageId(UUID.randomUUID().toString())
    }
}

/**
 * Identifies a model the app knows about.
 *
 * Deliberately has no `random()`: a model's identity comes from where it lives
 * (a file on disk, a tag on a server, a catalogue entry). A randomly generated
 * one would not survive a rescan, so two scans would produce two rows for the
 * same file. Derive it from the origin instead — see [ModelRef].
 */
@JvmInline
public value class ModelId(
    public val value: String,
) {
    public override fun toString(): String = value
}

/** Identifies a configured remote server. */
@JvmInline
public value class ServerId(
    public val value: String,
) {
    public override fun toString(): String = value

    public companion object {
        public fun random(): ServerId = ServerId(UUID.randomUUID().toString())
    }
}

/** Identifies a document ingested into the RAG index. */
@JvmInline
public value class DocumentId(
    public val value: String,
) {
    public override fun toString(): String = value

    public companion object {
        public fun random(): DocumentId = DocumentId(UUID.randomUUID().toString())
    }
}

/** Identifies one chunk of an indexed [DocumentId]. */
@JvmInline
public value class ChunkId(
    public val value: String,
) {
    public override fun toString(): String = value

    public companion object {
        public fun random(): ChunkId = ChunkId(UUID.randomUUID().toString())

        /**
         * A deterministic id for the [index]-th chunk of [documentId].
         *
         * Re-indexing the same document must overwrite its chunks rather than
         * duplicate them, which needs the id to be a function of the input.
         */
        public fun of(documentId: DocumentId, index: Int): ChunkId = ChunkId("${documentId.value}:$index")
    }
}
