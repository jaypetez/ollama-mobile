package io.github.jaypetez.ollamamobile.storage

import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.dao.RagDao
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagChunkEntity
import io.github.jaypetez.ollamamobile.storage.entity.RagDocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The external-content index only stays correct because of the triggers, and
 * the failure mode when one is wrong is silent: search returns rows that no
 * longer exist, or misses rows that do.
 *
 * `integrity-check` is FTS5's own consistency command — it raises
 * `SQLITE_CORRUPT_VTAB` when the index disagrees with the content table — so
 * each case here asserts both the observable search result and the index's own
 * opinion of itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtsTriggerTest {
    private lateinit var database: OllamaDatabase
    private lateinit var messages: MessageDao
    private lateinit var rag: RagDao

    @Before
    fun setUp() = runTest {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        messages = database.messageDao()
        rag = database.ragDao()
        database.conversationDao().insert(
            ConversationEntity(id = CONVERSATION, title = "Triggers", createdAt = 0, updatedAt = 0),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `updating a message replaces its terms in the index`() = runTest {
        insert("m1", "aardvark")
        assertThat(messages.search("aardvark").first()).hasSize(1)

        val stored = requireNotNull(messages.find("m1"))
        messages.update(stored.copy(content = "buffalo"))

        // The old term must be gone. If the update trigger deleted using the
        // *new* values, the "aardvark" posting would survive and this row would
        // still be returned for a word it no longer contains.
        assertThat(messages.search("aardvark").first()).isEmpty()
        assertThat(messages.search("buffalo").first().map { it.uuid }).containsExactly("m1")
        assertIndexIntact(FtsSchema.MESSAGE_FTS_TABLE)
    }

    @Test
    fun `streamed appends keep the index in step`() = runTest {
        insert("m1", "first")
        messages.appendContent("m1", " second")
        messages.appendContent("m1", " third")

        assertThat(messages.search("second").first().map { it.uuid }).containsExactly("m1")
        assertThat(messages.search("third").first().map { it.uuid }).containsExactly("m1")
        assertThat(messages.search("first").first().map { it.uuid }).containsExactly("m1")
        assertIndexIntact(FtsSchema.MESSAGE_FTS_TABLE)
    }

    @Test
    fun `deleting a message removes it from the index`() = runTest {
        insert("m1", "ephemeral")
        insert("m2", "ephemeral")

        messages.deleteByUuid("m1")

        assertThat(messages.search("ephemeral").first().map { it.uuid }).containsExactly("m2")
        assertIndexIntact(FtsSchema.MESSAGE_FTS_TABLE)
    }

    @Test
    fun `cascading a conversation delete empties the index too`() = runTest {
        insert("m1", "cascade")
        insert("m2", "cascade")

        // The delete arrives via ON DELETE CASCADE from the parent row, not
        // from a DELETE on `messages`. SQLite fires row triggers for cascaded
        // deletes only when recursive triggers are on for FK actions, so this
        // is worth asserting rather than assuming.
        database.conversationDao().deleteById(CONVERSATION)

        assertThat(messages.search("cascade").first()).isEmpty()
        assertIndexIntact(FtsSchema.MESSAGE_FTS_TABLE)
    }

    @Test
    fun `rag chunk index tracks inserts updates and deletes`() = runTest {
        rag.upsertDocument(
            RagDocumentEntity(id = DOCUMENT, title = "Doc", uri = "content://doc", createdAt = 0),
        )
        rag.insertChunks(
            listOf(
                RagChunkEntity(uuid = "c1", documentId = DOCUMENT, ordinal = 0, text = "gradient descent"),
                RagChunkEntity(uuid = "c2", documentId = DOCUMENT, ordinal = 1, text = "unrelated prose"),
            ),
        )
        assertThat(rag.searchChunks("gradient").first().map { it.uuid }).containsExactly("c1")

        rag.replaceChunks(
            DOCUMENT,
            listOf(RagChunkEntity(uuid = "c3", documentId = DOCUMENT, ordinal = 0, text = "gradient boosting")),
        )

        assertThat(rag.searchChunks("descent").first()).isEmpty()
        assertThat(rag.searchChunks("boosting").first().map { it.uuid }).containsExactly("c3")
        assertIndexIntact(FtsSchema.RAG_CHUNK_FTS_TABLE)
    }

    @Test
    fun `a heading match outranks the same term in the body`() = runTest {
        rag.upsertDocument(
            RagDocumentEntity(id = DOCUMENT, title = "Doc", uri = "content://doc", createdAt = 0),
        )
        rag.insertChunks(
            listOf(
                RagChunkEntity(
                    uuid = "body",
                    documentId = DOCUMENT,
                    ordinal = 0,
                    text = "mentions throttling once among other things",
                    heading = "Introduction",
                ),
                RagChunkEntity(
                    uuid = "titled",
                    documentId = DOCUMENT,
                    ordinal = 1,
                    text = "some other content entirely",
                    heading = "Throttling",
                ),
            ),
        )

        val hits = rag.searchChunks("throttling").first()

        assertThat(hits.map { it.uuid }).containsExactly("titled", "body").inOrder()
    }

    private suspend fun insert(uuid: String, content: String) {
        messages.insert(
            MessageEntity(
                uuid = uuid,
                conversationId = CONVERSATION,
                role = "user",
                content = content,
                createdAt = 0,
            ),
        )
    }

    /** Asks FTS5 whether its index still agrees with the content table. */
    private suspend fun assertIndexIntact(table: String) {
        database.useReaderConnection { transactor ->
            transactor.usePrepared("INSERT INTO $table($table, rank) VALUES ('integrity-check', 1)") {
                it.step()
            }
        }
    }

    private companion object {
        const val CONVERSATION = "conversation-1"
        const val DOCUMENT = "document-1"
    }
}
