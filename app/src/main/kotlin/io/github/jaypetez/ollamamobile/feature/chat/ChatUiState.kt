package io.github.jaypetez.ollamamobile.feature.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the chat screen draws, as one closed set of shapes.
 *
 * A bag of `isLoading`/`error`/`messages?` fields makes "loading *and* failed
 * *and* streaming" representable, and every consumer then re-derives which of
 * the eight combinations is real. Here the compiler does it: a `when` over this
 * interface has exactly five arms and none of them can contradict another.
 *
 * Two things are deliberately *not* in here:
 *
 *  * **The in-flight text.** It changes twenty-five times a second. Putting it
 *    in the screen state means rebuilding this object — and recomposing the
 *    whole transcript — at that rate. It lives in its own [StreamFrame] stream,
 *    which only the streaming bubble subscribes to.
 *  * **The composer draft.** It changes on every keystroke and nothing above
 *    the input bar needs it, so it stays inside the input bar.
 */
@Immutable
sealed interface ChatUiState {
    /** Nothing has come back from disk yet. Distinct from an empty thread. */
    data object Loading : ChatUiState

    /** A thread the user can interact with, however it is currently doing. */
    sealed interface Open : ChatUiState {
        val header: ChatHeader
        val composer: ComposerState

        /** Finalised turns only, oldest first. Never contains a pending turn. */
        val messages: ImmutableList<ChatMessageUi>
    }

    /** A thread with no turns yet. [messages] is empty by construction. */
    data class Empty(
        override val header: ChatHeader,
        override val composer: ComposerState,
    ) : Open {
        override val messages: ImmutableList<ChatMessageUi> = persistentListOf()
    }

    /** Idle with a transcript. [messages] is never empty — that is [Empty]. */
    data class Ready(
        override val header: ChatHeader,
        override val composer: ComposerState,
        override val messages: ImmutableList<ChatMessageUi>,
    ) : Open

    /**
     * A turn is in flight.
     *
     * The answer being generated is *not* in [messages]: the persisted row is
     * still pending and is filtered out, and the bubble on screen is fed by
     * [StreamFrame] instead. That is what keeps the finalised turns from
     * recomposing while the new one grows.
     */
    data class Streaming(
        override val header: ChatHeader,
        override val composer: ComposerState,
        override val messages: ImmutableList<ChatMessageUi>,
    ) : Open

    /** The last attempt failed. The transcript stays visible underneath. */
    data class Failed(
        override val header: ChatHeader,
        override val composer: ComposerState,
        override val messages: ImmutableList<ChatMessageUi>,
        val failure: ChatFailure,
    ) : Open
}

/** The title bar's content. */
@Immutable
data class ChatHeader(
    /** Null for a thread that does not exist yet; the screen supplies the label. */
    val title: String?,
    /** The wire tag of the selected model, or null when none is chosen. */
    val modelName: String?,
    /** Which server would serve it, when that is known. */
    val serverLabel: String?,
    val hasSystemPrompt: Boolean,
    val canExport: Boolean,
)

/** Whether the send button works, and if not, why not. */
@Immutable
data class ComposerState(
    val canSend: Boolean,
    val blocker: ComposerBlocker?,
)

/**
 * Why sending is refused.
 *
 * Named rather than a string so the reason survives into a test, and so the
 * screen can offer the matching fix instead of a sentence.
 */
enum class ComposerBlocker(
    @param:StringRes val messageRes: Int,
) {
    NO_MODEL_SELECTED(R.string.chat_blocker_no_model),
    NO_REACHABLE_SERVER(R.string.chat_blocker_no_server),
}

/** One finalised turn, in the form the bubble needs it. */
@Immutable
data class ChatMessageUi(
    val id: MessageId,
    val role: Role,
    val text: String,
    val reasoning: String?,
    /** Null whenever the server reported no counters. Never a zeroed instance. */
    val stats: MessageStatsUi?,
    val outcome: MessageOutcome,
    /** True for the newest assistant turn, the only one worth regenerating. */
    val isLastAssistant: Boolean,
) {
    /**
     * The [androidx.compose.foundation.lazy.LazyListScope] content type.
     *
     * Bubbles of different roles have genuinely different subtrees, so telling
     * the list that lets it reuse a user bubble's nodes for the next user
     * bubble instead of throwing them away.
     */
    val contentType: String
        get() = "${role.wireName}-${outcome.name}"
}

/** How a finalised turn ended. */
enum class MessageOutcome {
    COMPLETE,

    /** Cancelled, or the stream died. Whatever text arrived is still shown. */
    INTERRUPTED,
}

/**
 * Server-reported counters, already reduced to what is displayable.
 *
 * Every field is nullable and a null field draws nothing. This type is only
 * ever built from a [GenerationStats] that the server actually sent, so there
 * is no path that renders "0 tok/s" for a measurement nobody made.
 */
@Immutable
data class MessageStatsUi(
    val completionTokens: Int?,
    val promptTokens: Int?,
    val tokensPerSecond: Double?,
    val secondsToFirstToken: Double?,
    val totalSeconds: Double?,
) {
    val isEmpty: Boolean
        get() = completionTokens == null &&
            promptTokens == null &&
            tokensPerSecond == null &&
            secondsToFirstToken == null &&
            totalSeconds == null

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * Projects [stats], or returns null when there is nothing honest to show.
         *
         * Time to first token is `load + prompt evaluation`, and it is computed
         * only when the prompt evaluation duration was reported: without it the
         * figure would be the load time alone, which is not the same thing and
         * reads as an implausibly fast model.
         */
        fun from(stats: GenerationStats?): MessageStatsUi? {
            if (stats == null || stats.isEmpty) return null
            val firstToken = stats.promptEvalNanos?.let { promptEval ->
                (promptEval + (stats.loadNanos ?: 0L)) / NANOS_PER_SECOND
            }
            return MessageStatsUi(
                completionTokens = stats.completionTokens,
                promptTokens = stats.promptTokens,
                tokensPerSecond = stats.tokensPerSecond,
                secondsToFirstToken = firstToken,
                totalSeconds = stats.totalNanos?.let { it / NANOS_PER_SECOND },
            ).takeUnless { it.isEmpty }
        }
    }
}

/**
 * One published snapshot of the answer being generated.
 *
 * Published at [ChatViewModel.FRAME_INTERVAL_MILLIS], not per token — see the
 * note on the view model's frame pump.
 */
@Immutable
data class StreamFrame(
    val text: String,
    val reasoning: String,
    val phase: StreamPhase,
)

/** Where an in-flight turn is. */
enum class StreamPhase {
    /** Request sent, nothing decoded yet. */
    WAITING,

    /** Tokens are arriving. */
    GENERATING,

    /**
     * Generation ended; the bubble is holding the finished text until the
     * persisted row appears in the transcript.
     *
     * Without this step the bubble would disappear the instant the stream
     * ended and reappear a frame or two later when Room re-emitted, which
     * reads as a flicker at the end of every answer.
     */
    SETTLING,
}

/** What the model picker shows. */
@Immutable
data class TargetPickerState(
    val options: ImmutableList<TargetOptionUi>,
    /** Always false in this build: there is no on-device engine. */
    val localAvailable: Boolean,
)

/** One selectable model, with the server that would run it. */
@Immutable
data class TargetOptionUi(
    val modelId: ModelId,
    val displayName: String,
    val modelName: String,
    val serverLabel: String?,
    val reachable: Boolean,
    val selected: Boolean,
)

/** A one-shot thing the screen has to do, as opposed to a state it is in. */
sealed interface ChatEvent {
    /** Hand [text] to the system share sheet. */
    data class Share(
        val text: String,
        val fileName: String,
        val mimeType: String,
    ) : ChatEvent

    data class Notice(
        @param:StringRes val messageRes: Int,
    ) : ChatEvent

    /** A thread that did not exist until the first message was sent. */
    data class Created(
        val conversationId: ConversationId,
    ) : ChatEvent
}

/**
 * A failure the chat screen knows how to talk about.
 *
 * The point of the hierarchy is that "the server is unreachable", "LAN-only
 * mode blocked this" and "the model is not loaded" are three different
 * sentences with three different fixes, and [AppError] already distinguishes
 * them. Collapsing them into one string is what this type exists to prevent.
 *
 * [AppError.message] is never rendered: `:core-model` documents it as
 * developer-facing, so the mapping below goes from error *type* to resource.
 */
@Immutable
sealed interface ChatFailure {
    @get:StringRes val titleRes: Int

    @get:StringRes val bodyRes: Int

    /** A safe extra detail — a hostname, an HTTP code. Never a secret. */
    val detail: String?

    /** Whether offering "Try again" makes any sense for this failure. */
    val retryable: Boolean

    data object ServerUnreachable : ChatFailure {
        override val titleRes: Int = R.string.chat_error_unreachable_title
        override val bodyRes: Int = R.string.chat_error_unreachable_body
        override val detail: String? = null
        override val retryable: Boolean = true
    }

    data object ServerTimeout : ChatFailure {
        override val titleRes: Int = R.string.chat_error_timeout_title
        override val bodyRes: Int = R.string.chat_error_timeout_body
        override val detail: String? = null
        override val retryable: Boolean = true
    }

    data class LanOnlyBlocked(
        val host: String,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_lan_title
        override val bodyRes: Int = R.string.chat_error_lan_body
        override val detail: String = host
        override val retryable: Boolean = false
    }

    data object OfflineMode : ChatFailure {
        override val titleRes: Int = R.string.chat_error_offline_title
        override val bodyRes: Int = R.string.chat_error_offline_body
        override val detail: String? = null
        override val retryable: Boolean = false
    }

    data object LocalNetworkPermission : ChatFailure {
        override val titleRes: Int = R.string.chat_error_permission_title
        override val bodyRes: Int = R.string.chat_error_permission_body
        override val detail: String? = null
        override val retryable: Boolean = false
    }

    data class ModelNotLoaded(
        override val detail: String?,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_model_title
        override val bodyRes: Int = R.string.chat_error_model_body
        override val retryable: Boolean = false
    }

    data class ModelUnsupported(
        override val detail: String?,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_model_unsupported_title
        override val bodyRes: Int = R.string.chat_error_model_unsupported_body
        override val retryable: Boolean = false
    }

    data class AuthenticationRequired(
        val code: Int,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_auth_title
        override val bodyRes: Int = R.string.chat_error_auth_body
        override val detail: String = code.toString()
        override val retryable: Boolean = false
    }

    data class CertificateUntrusted(
        val fingerprintSha256: String?,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_tls_title
        override val bodyRes: Int = R.string.chat_error_tls_body
        override val detail: String? = fingerprintSha256
        override val retryable: Boolean = false
    }

    data class ServerRejected(
        val code: Int,
    ) : ChatFailure {
        override val titleRes: Int = R.string.chat_error_http_title
        override val bodyRes: Int = R.string.chat_error_http_body
        override val detail: String = code.toString()
        override val retryable: Boolean = true
    }

    data object ServerBusy : ChatFailure {
        override val titleRes: Int = R.string.chat_error_busy_title
        override val bodyRes: Int = R.string.chat_error_busy_body
        override val detail: String? = null
        override val retryable: Boolean = true
    }

    data object OnDeviceUnavailable : ChatFailure {
        override val titleRes: Int = R.string.chat_error_engine_title
        override val bodyRes: Int = R.string.chat_error_engine_body
        override val detail: String? = null
        override val retryable: Boolean = false
    }

    data object StorageUnavailable : ChatFailure {
        override val titleRes: Int = R.string.chat_error_storage_title
        override val bodyRes: Int = R.string.chat_error_storage_body
        override val detail: String? = null
        override val retryable: Boolean = false
    }

    data object Unknown : ChatFailure {
        override val titleRes: Int = R.string.chat_error_unknown_title
        override val bodyRes: Int = R.string.chat_error_unknown_body
        override val detail: String? = null
        override val retryable: Boolean = true
    }
}

// ---------------------------------------------------------------------------
// Types the composables exchange
//
// They live beside the screen state rather than next to the composable that
// uses them because every one of them is a stability promise: a bubble that
// takes an unstable parameter stops skipping, and the streaming split stops
// working. Keeping them together makes that contract reviewable in one place.
// ---------------------------------------------------------------------------

/**
 * Everything the chat screen can ask for.
 *
 * One stable holder rather than ten lambda parameters: built once in
 * [ChatRoute], it keeps the message list — and therefore every finalised bubble
 * in it — skippable.
 */
@Immutable
data class ChatScreenActions(
    val onSend: (String) -> Unit,
    val onStop: () -> Unit,
    val onRegenerate: () -> Unit,
    val onCopy: (String) -> Unit,
    val onSelectModel: (ModelId) -> Unit,
    val onSetSystemPrompt: (String?) -> Unit,
    val onSetSampling: (SamplingParams) -> Unit,
    val onExport: (ExportFormat) -> Unit,
    val onDismissFailure: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * What a bubble can ask the screen to do.
 *
 * One `@Immutable` holder rather than three lambda parameters: the list rebuilds
 * this object never, so every bubble keeps skipping recomposition, whereas three
 * lambdas written at the call site would be three new instances each time the
 * list recomposed.
 */
@Immutable
data class MessageActions(
    val onCopy: (String) -> Unit,
    val onCopyCode: (String) -> Unit,
    val onRegenerate: () -> Unit,
)

/**
 * A Compose-stable handle on the in-flight frame stream.
 *
 * `StateFlow` is an unannotated interface, so Compose has to assume it can
 * change under it: a composable taking one directly cannot skip, and — worse —
 * a lambda that captures one is never memoised, so the `LazyColumn` content
 * block is rebuilt and every finalised bubble in it recomposes. Wrapping the
 * flow in a type whose identity genuinely never changes removes both.
 */
@Stable
class StreamingFrames(
    val state: StateFlow<StreamFrame?>,
)

/**
 * One parsed Markdown block.
 *
 * The tree is built off the main thread and handed to composition already
 * finished, which is why the inline spans are [AnnotatedString] rather than
 * source text: turning `**bold**` into spans is the expensive half of rendering
 * Markdown, and doing it inside a composable would put it on the frame budget
 * of every recomposition.
 */
@Immutable
sealed interface MarkdownBlock {
    data class Paragraph(
        val text: AnnotatedString,
    ) : MarkdownBlock

    data class Heading(
        val level: Int,
        val text: AnnotatedString,
    ) : MarkdownBlock

    data class ListItem(
        val marker: String,
        val text: AnnotatedString,
        val depth: Int,
    ) : MarkdownBlock

    data class Quote(
        val text: AnnotatedString,
    ) : MarkdownBlock

    /** A *closed* fence. An unterminated one never becomes this — see [splitStreamingText]. */
    data class Code(
        val language: String?,
        val code: String,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * Classifies an [AppError] for the chat screen.
 *
 * Returns null for [AppError.Network.Cancelled]: the user pressing Stop is a
 * normal outcome, and showing it as an error would mean apologising for doing
 * what was asked.
 */
fun AppError.toChatFailureOrNull(): ChatFailure? = when (this) {
    is AppError.Network -> toChatFailure()
    is AppError.Policy -> toChatFailure()
    is AppError.Model -> toChatFailure()
    is AppError.Engine -> toChatFailure()
    is AppError.Storage -> ChatFailure.StorageUnavailable
    is AppError.Unexpected -> ChatFailure.Unknown
}

/** Null for [AppError.Network.Cancelled]; see [toChatFailureOrNull]. */
private fun AppError.Network.toChatFailure(): ChatFailure? = when (this) {
    is AppError.Network.Cancelled -> null

    is AppError.Network.Unreachable -> ChatFailure.ServerUnreachable

    is AppError.Network.Timeout -> ChatFailure.ServerTimeout

    is AppError.Network.Tls -> ChatFailure.CertificateUntrusted(fingerprintSha256)

    is AppError.Network.QueueFull -> ChatFailure.ServerBusy

    is AppError.Network.Http -> when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ChatFailure.AuthenticationRequired(code)

        // Ollama answers 404 for a tag it does not have locally, which is the
        // "pull it first" case rather than a broken URL.
        HTTP_NOT_FOUND -> ChatFailure.ModelNotLoaded(null)

        else -> ChatFailure.ServerRejected(code)
    }
}

private fun AppError.Policy.toChatFailure(): ChatFailure = when (this) {
    is AppError.Policy.LanOnlyViolation -> ChatFailure.LanOnlyBlocked(host)
    is AppError.Policy.OfflineMode -> ChatFailure.OfflineMode
    is AppError.Policy.LocalNetworkPermissionDenied -> ChatFailure.LocalNetworkPermission
}

private fun AppError.Model.toChatFailure(): ChatFailure = when (this) {
    is AppError.Model.NotFound -> ChatFailure.ModelNotLoaded(modelId.value)
    is AppError.Model.Unsupported -> ChatFailure.ModelUnsupported(reason)
    is AppError.Model.Corrupt -> ChatFailure.ModelUnsupported(null)
    is AppError.Model.InsufficientMemory -> ChatFailure.OnDeviceUnavailable
}

private fun AppError.Engine.toChatFailure(): ChatFailure = when (this) {
    is AppError.Engine.NotAvailable -> ChatFailure.OnDeviceUnavailable

    // A model the engine could not load is, from the user's side, a model that
    // is not loaded — the same sentence and the same fix.
    is AppError.Engine.LoadFailed -> ChatFailure.ModelNotLoaded(null)

    is AppError.Engine.GenerationFailed -> ChatFailure.Unknown
}
