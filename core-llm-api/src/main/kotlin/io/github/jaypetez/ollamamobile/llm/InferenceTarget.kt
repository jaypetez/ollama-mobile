package io.github.jaypetez.ollamamobile.llm

import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ServerId

/**
 * Where one request is going to run.
 *
 * This is the answer a router produces and the thing an
 * [InferenceEvent.Started] announces, so the UI can say *which* machine is
 * answering before the first token lands — which matters on a LAN with three
 * Pis, one of which is slow.
 *
 * [Local] is representable today and nothing produces it. That is deliberate
 * rather than aspirational: the type has to exist for the router to have a
 * branch to write and for a test to exercise it, but no code path in the
 * shipped app can construct one, because there is no engine to run it. See
 * `SmartRouter` in `:core-data` for the branch and the reasoning.
 */
public sealed interface InferenceTarget {
    /** True for [Local]. Present so a `when` is not required just to label a badge. */
    public val isLocal: Boolean

    public val isRemote: Boolean
        get() = !isLocal

    /**
     * A model served by a configured remote server.
     *
     * [modelName] is the server's own tag (`qwen3:1.7b-instruct-q4_K_M`) and
     * not a [ModelId], because the same model on two servers is two different
     * tags and the request has to carry the one this server will recognise.
     */
    public data class Remote(
        public val serverId: ServerId,
        public val modelName: String,
    ) : InferenceTarget {
        override val isLocal: Boolean
            get() = false
    }

    /**
     * A GGUF loaded into an on-device engine.
     *
     * Carries a [ModelId] and not a path: the file layout belongs to the
     * storage layer, and a target that quoted a path would have to be
     * recomputed whenever the model moved.
     */
    public data class Local(
        public val modelId: ModelId,
    ) : InferenceTarget {
        override val isLocal: Boolean
            get() = true
    }
}
