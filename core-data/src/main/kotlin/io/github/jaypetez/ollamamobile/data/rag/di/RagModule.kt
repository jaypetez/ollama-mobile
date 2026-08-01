package io.github.jaypetez.ollamamobile.data.rag.di

import android.content.ContentResolver
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.data.rag.DefaultEmbeddingModelRegistry
import io.github.jaypetez.ollamamobile.data.rag.EmbeddingModelRegistry
import io.github.jaypetez.ollamamobile.data.rag.EmbeddingService
import io.github.jaypetez.ollamamobile.data.rag.EmbeddingServiceProvider
import io.github.jaypetez.ollamamobile.data.rag.LocalEmbeddingService
import io.github.jaypetez.ollamamobile.llm.EngineRole
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object RagModule {
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideEmbeddingModelRegistry(): EmbeddingModelRegistry = DefaultEmbeddingModelRegistry()

    /**
     * Resolves an embedding service at the moment the indexer asks for one.
     *
     * Deliberately returns null rather than throwing when no embedding model is
     * resident. Indexing is a retryable background job, and "the embedding model
     * is not loaded right now" is a normal, transient state on a device that
     * evicts models under memory pressure — the worker reschedules and the
     * document finishes later. Throwing would mark it permanently failed for a
     * condition that fixes itself.
     *
     * The engine injected here is whichever [LlamaEngine] the application binds.
     * A build that keeps a chat model and an embedding model resident at once
     * binds two instances and overrides this provider with one that picks the
     * embedding-role instance; the role check below is what makes that
     * substitution safe rather than silently wrong, because embedding through a
     * generative context returns no pooled vector.
     */
    @Provides
    @Singleton
    fun provideEmbeddingServiceProvider(
        engine: LlamaEngine,
        registry: EmbeddingModelRegistry,
    ): EmbeddingServiceProvider = object : EmbeddingServiceProvider {
        override suspend fun current(): EmbeddingService? {
            val model = engine.loadedModel.value ?: return null
            val profile = registry.profileFor(model)
            // Only a model we can identify as an embedding model is used. A chat
            // model would produce zeroes or a last-token hidden state, either of
            // which poisons the index with no error anywhere.
            if (!model.isEmbeddingModel()) return null
            return LocalEmbeddingService(engine, profile)
        }
    }

    /**
     * Whether a loaded model is an embedding model.
     *
     * Name-based, because a GGUF does not carry a "this is an encoder" flag that
     * is reliable across converters, and [EngineRole] is a load-time decision
     * this layer cannot observe through the public engine contract.
     */
    private fun io.github.jaypetez.ollamamobile.model.ModelRef.isEmbeddingModel(): Boolean {
        val haystack = "$name $displayName".lowercase()
        return EMBEDDING_NAME_HINTS.any { it in haystack }
    }

    private val EMBEDDING_NAME_HINTS = listOf("embed", "bge-", "gte-", "e5-", "minilm")
}
