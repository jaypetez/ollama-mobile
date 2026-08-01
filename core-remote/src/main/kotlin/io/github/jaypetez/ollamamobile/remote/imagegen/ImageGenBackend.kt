package io.github.jaypetez.ollamamobile.remote.imagegen

import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.ServerRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What to render. Deliberately the small common denominator of every backend that could implement this. */
data class ImageGenRequest(
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int,
    val height: Int,
    val steps: Int? = null,
    val seed: Long? = null,
    /** Backend-specific model or checkpoint name. Meaningless to this module; passed through. */
    val model: String? = null,
)

/** Progress from a backend that reports it. */
sealed interface ImageGenEvent {
    data class Progress(
        val step: Int,
        val totalSteps: Int,
        /** A preview frame as PNG bytes, when the backend produces one. */
        val previewPng: ByteArray? = null,
    ) : ImageGenEvent {
        // ByteArray in a data class needs these; the generated ones compare by
        // identity, which would make two equal previews unequal.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Progress && step == other.step && totalSteps == other.totalSteps)

        override fun hashCode(): Int = step * PRIME + totalSteps

        private companion object {
            const val PRIME = 31
        }
    }

    data class Completed(
        val imagesPng: List<ByteArray>,
    ) : ImageGenEvent

    data class Failed(
        val error: AppError,
    ) : ImageGenEvent
}

/**
 * The seam for image generation. **Nothing implements this yet, and Ollama
 * never will.**
 *
 * ## The truth this interface exists to record
 *
 * **Ollama does not generate images.** It runs GGUF language models. Its vision
 * support is *input* only — a multimodal model such as Qwen-VL or Llama 3.2
 * Vision accepts an image in the `images` field of a chat message and describes
 * it. There is no `/api/generate-image`, no diffusion pipeline, no plan for one,
 * and no amount of prompt engineering that makes a language model return a PNG.
 * Every "Ollama image generation" tutorial is either describing vision input or
 * describing a separate service behind a proxy.
 *
 * So why does the interface exist at all? Because the *product* question — "can
 * this app make a picture" — is asked constantly, and the answer is going to be
 * a second backend: ComfyUI, an Automatic1111 API, or `stable-diffusion.cpp`
 * on-device. Each has its own protocol and its own progress semantics. Deciding
 * now that image generation is a *different backend* rather than another Ollama
 * endpoint is what stops it from being retrofitted into [OllamaClient][
 * io.github.jaypetez.ollamamobile.remote.OllamaClient] later, which is the
 * refactor that would actually hurt: the chat client's request and event types
 * would grow fields that are meaningless for text, and every caller would have
 * to ignore them.
 *
 * The interface has no bodies. It is a shape, and the shape is the deliverable.
 */
interface ImageGenBackend {
    /** True when this backend can actually be reached and used. [NoOpImageGenBackend] always says false. */
    suspend fun isAvailable(server: ServerRef): Boolean

    /** Model or checkpoint names this backend offers. */
    suspend fun listModels(server: ServerRef): AppResult<List<String>>

    /** Renders [request]. Cold; cancelling the collector cancels the job on the backend where the protocol allows. */
    fun generate(server: ServerRef, request: ImageGenRequest): Flow<ImageGenEvent>
}

/**
 * The binding that ships today: it refuses, in the one way the UI can explain.
 *
 * [AppError.Engine.NotAvailable] and not a network error, because nothing is
 * wrong with the network — the capability is simply not in this build.
 */
object NoOpImageGenBackend : ImageGenBackend {
    override suspend fun isAvailable(server: ServerRef): Boolean = false

    override suspend fun listModels(server: ServerRef): AppResult<List<String>> = AppResult.Failure(UNAVAILABLE)

    override fun generate(server: ServerRef, request: ImageGenRequest): Flow<ImageGenEvent> =
        flowOf(ImageGenEvent.Failed(UNAVAILABLE))

    private val UNAVAILABLE = AppError.Engine.NotAvailable(
        message = "This build cannot generate images. Ollama serves language models only.",
    )
}
