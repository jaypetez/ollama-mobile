package io.github.jaypetez.ollamamobile.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.jaypetez.ollamamobile.storage.converter.StorageConverters
import io.github.jaypetez.ollamamobile.storage.dao.BenchmarkDao
import io.github.jaypetez.ollamamobile.storage.dao.ConversationDao
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.dao.PromptTemplateDao
import io.github.jaypetez.ollamamobile.storage.dao.RagDao
import io.github.jaypetez.ollamamobile.storage.dao.ServerDao
import io.github.jaypetez.ollamamobile.storage.dao.SettingDao
import io.github.jaypetez.ollamamobile.storage.entity.AttachmentEntity
import io.github.jaypetez.ollamamobile.storage.entity.BenchmarkResultEntity
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageCitationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import io.github.jaypetez.ollamamobile.storage.entity.PromptTemplateEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagChunkEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentEntity
import io.github.jaypetez.ollamamobile.storage.entity.ServerConfigEntity
import io.github.jaypetez.ollamamobile.storage.entity.SettingEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ModelEntity::class,
        ServerConfigEntity::class,
        PromptTemplateEntity::class,
        SettingEntity::class,
        BenchmarkResultEntity::class,
        RagDocumentEntity::class,
        RagChunkEntity::class,
        MessageCitationEntity::class,
    ],
    version = OllamaDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(StorageConverters::class)
abstract class OllamaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun modelDao(): ModelDao

    abstract fun serverDao(): ServerDao

    abstract fun promptTemplateDao(): PromptTemplateDao

    abstract fun settingDao(): SettingDao

    abstract fun benchmarkDao(): BenchmarkDao

    abstract fun ragDao(): RagDao

    companion object {
        const val VERSION: Int = 1
        const val FILE_NAME: String = "ollama.db"

        /**
         * The one place the database is constructed.
         *
         * Three choices here are load-bearing.
         *
         * **[BundledSQLiteDriver] is not optional.** It ships its own SQLite
         * compiled with `SQLITE_ENABLE_FTS5`. The system SQLite on an Android
         * device is whatever the OEM built — FTS5 is common but not guaranteed,
         * `bm25()` even less so, and `remove_diacritics 2` needs 3.27+. Falling
         * back to the platform library would turn "search works" into a
         * per-device lottery that only shows up in the field. It also pins the
         * SQLite version across the whole `minSdk` range, so a query cannot
         * behave differently on Android 10 than on Android 16.
         *
         * **WAL** so a long streaming write cannot block the UI's reads.
         *
         * **No `fallbackToDestructiveMigration`.** Losing every conversation
         * because a schema change shipped without a migration is not a
         * recoverable mistake; a crash on open is loud and fixable, silent data
         * loss is neither. `NoDestructiveMigrationTest` enforces that this stays
         * true.
         */
        fun build(
            context: Context,
            queryContext: CoroutineContext = Dispatchers.IO,
        ): OllamaDatabase = Room
            .databaseBuilder(
                context = context.applicationContext,
                klass = OllamaDatabase::class.java,
                name = FILE_NAME,
            ).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryContext)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(FtsCallback())
            .build()

        /** In-memory instance for tests; same driver, same callback, so FTS5 behaves identically. */
        fun buildInMemory(
            context: Context,
            queryContext: CoroutineContext = Dispatchers.IO,
        ): OllamaDatabase = Room
            .inMemoryDatabaseBuilder(
                context = context.applicationContext,
                klass = OllamaDatabase::class.java,
            ).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryContext)
            .addCallback(FtsCallback())
            .build()
    }
}
