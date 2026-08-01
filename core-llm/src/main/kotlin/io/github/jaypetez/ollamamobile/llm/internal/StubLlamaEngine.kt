package io.github.jaypetez.ollamamobile.llm.internal

import io.github.jaypetez.ollamamobile.llm.InferenceEvent
import io.github.jaypetez.ollamamobile.llm.InferenceRequest
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.llm.ModelLoadSpec
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.asException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The engine a build with no native code gets.
 *
 * This is what `-Pollama.nativeSource=none` — the **default** — produces, and
 * it is the reason a fresh clone builds and runs on a machine that has never
 * installed the NDK. The app is a fully working remote Ollama client with this
 * bound; the only thing missing is the local target, and the router already has
 * a branch for that not existing.
 *
 * It is deliberately not a silent no-op. [generate] emits
 * [InferenceEvent.Failed] and the suspending calls throw, both carrying
 * `AppError.Engine.NotAvailable`, so a caller that reached here by mistake sees
 * a specific sentence rather than an empty answer it might present as a real
 * one.
 */
internal class StubLlamaEngine
    @Inject
    constructor() : LlamaEngine {
        private val _loadedModel = MutableStateFlow<ModelRef?>(null)

        override val isAvailable: Boolean = false

        override val loadedModel: StateFlow<ModelRef?> = _loadedModel.asStateFlow()

        override suspend fun load(spec: ModelLoadSpec): Unit = throw unavailable()

        /** Succeeds, because unloading nothing is not a failure. */
        override suspend fun unload() = Unit

        override fun generate(request: InferenceRequest): Flow<InferenceEvent> =
            flowOf(InferenceEvent.Failed(EngineErrors.notAvailable()))

        override suspend fun embed(text: String): FloatArray = throw unavailable()

        // Overridden rather than inherited so an empty batch fails too. The
        // default returns an empty list without touching the engine, which would
        // let an indexer believe a no-native build had embedded something.
        override suspend fun embed(texts: List<String>): List<FloatArray> = throw unavailable()

        /**
         * Zero, not an estimate.
         *
         * A characters-divided-by-four guess would let a caller believe it had
         * trimmed a history correctly against a tokenizer that does not exist here.
         * Zero is obviously wrong, which is the point.
         */
        override suspend fun tokenCount(text: String): Int = 0

        private fun unavailable() = EngineErrors.notAvailable().asException()
    }
