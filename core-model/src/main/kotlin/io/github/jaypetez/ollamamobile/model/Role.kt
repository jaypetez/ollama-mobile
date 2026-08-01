package io.github.jaypetez.ollamamobile.model

/**
 * Who authored a message.
 *
 * [wireName] is what goes on the wire for both the Ollama native API and the
 * OpenAI-compatible `/v1` surface; both use the same lowercase spellings.
 */
public enum class Role(
    public val wireName: String,
) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    ;

    public companion object {
        /**
         * Parses a wire role, returning `null` for anything unrecognised.
         *
         * Tolerant on purpose. The client talks to Ollama, to llama.cpp's own
         * server, and to whatever else implements the protocol, and those
         * disagree: some emit `"model"` for the assistant turn and older
         * OpenAI-compatible servers emit `"function"` where the spec now says
         * `"tool"`. Rejecting the whole response over a spelling would lose a
         * message that is otherwise perfectly usable, so map the known aliases
         * and let the caller decide what to do with a genuine unknown.
         */
        public fun fromWireOrNull(wire: String?): Role? = when (wire?.trim()?.lowercase()) {
            "system", "developer" -> SYSTEM
            "user", "human" -> USER
            "assistant", "model", "ai" -> ASSISTANT
            "tool", "function" -> TOOL
            else -> null
        }

        /** As [fromWireOrNull], falling back to [fallback] for unknown values. */
        public fun fromWire(wire: String?, fallback: Role = ASSISTANT): Role = fromWireOrNull(wire) ?: fallback
    }
}
