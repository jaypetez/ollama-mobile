package io.github.jaypetez.ollamamobile.server.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.server.EmbeddingProvider
import io.github.jaypetez.ollamamobile.server.ModelAdmin
import io.github.jaypetez.ollamamobile.server.UnsupportedEmbeddingProvider
import io.github.jaypetez.ollamamobile.server.UnsupportedModelAdmin
import javax.inject.Singleton

/**
 * The bindings the HTTP surface needs that it cannot construct itself.
 *
 * [ModelAdmin] and [EmbeddingProvider] are ports precisely because their real
 * implementations live behind `:core-download`, `:core-storage` and
 * `:core-llm`, none of which `:server` may depend on. The defaults below are
 * the honest answer for a build where those modules are absent: the endpoints
 * exist, answer in Ollama's own error vocabulary, and never pretend to have
 * succeeded.
 *
 * `:app` replaces either binding by declaring its own module and removing
 * this one from the component; nothing in `:server` assumes the defaults.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    @Provides
    @Singleton
    fun provideModelAdmin(): ModelAdmin = UnsupportedModelAdmin

    @Provides
    @Singleton
    fun provideEmbeddingProvider(): EmbeddingProvider = UnsupportedEmbeddingProvider
}
