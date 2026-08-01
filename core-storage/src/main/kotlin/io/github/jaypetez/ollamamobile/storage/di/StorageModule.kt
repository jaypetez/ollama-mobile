package io.github.jaypetez.ollamamobile.storage.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
import io.github.jaypetez.ollamamobile.storage.crypto.AndroidKeystoreSecretKeyProvider
import io.github.jaypetez.ollamamobile.storage.crypto.SecretKeyProvider
import io.github.jaypetez.ollamamobile.storage.crypto.SecretsStore
import io.github.jaypetez.ollamamobile.storage.dao.BenchmarkDao
import io.github.jaypetez.ollamamobile.storage.dao.ConversationDao
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.dao.PromptTemplateDao
import io.github.jaypetez.ollamamobile.storage.dao.RagDao
import io.github.jaypetez.ollamamobile.storage.dao.ServerDao
import io.github.jaypetez.ollamamobile.storage.dao.SettingDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): OllamaDatabase =
        OllamaDatabase.build(context)

    @Provides
    fun provideConversationDao(database: OllamaDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: OllamaDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideModelDao(database: OllamaDatabase): ModelDao = database.modelDao()

    @Provides
    fun provideServerDao(database: OllamaDatabase): ServerDao = database.serverDao()

    @Provides
    fun providePromptTemplateDao(database: OllamaDatabase): PromptTemplateDao = database.promptTemplateDao()

    @Provides
    fun provideSettingDao(database: OllamaDatabase): SettingDao = database.settingDao()

    @Provides
    fun provideBenchmarkDao(database: OllamaDatabase): BenchmarkDao = database.benchmarkDao()

    @Provides
    fun provideRagDao(database: OllamaDatabase): RagDao = database.ragDao()

    /** Only the Keystore-backed provider is ever bound; there is no debug variant of this. */
    @Provides
    @Singleton
    fun provideSecretKeyProvider(): SecretKeyProvider = AndroidKeystoreSecretKeyProvider()

    /** Singleton because DataStore permits exactly one active instance per file per process. */
    @Provides
    @Singleton
    fun provideSecretsStore(
        @ApplicationContext context: Context,
        keyProvider: SecretKeyProvider,
    ): SecretsStore = SecretsStore.create(context, keyProvider)
}
