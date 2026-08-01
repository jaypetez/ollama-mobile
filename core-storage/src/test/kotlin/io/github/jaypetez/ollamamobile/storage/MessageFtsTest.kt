package io.github.jaypetez.ollamamobile.storage

import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.storage.dao.MessageDao
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves FTS5 is really there.
 *
 * This is the test that justifies `BundledSQLiteDriver`. It opens an in-memory
 * database through the same driver the app ships, creates the FTS5 virtual
 * table, and runs a `MATCH` ordered by `bm25()`. `CREATE VIRTUAL TABLE ...
 * USING fts5` fails outright on a SQLite built without FTS5, and `bm25()` is
 * unavailable on FTS4, so a green run here means the whole path exists — and it
 * means it without a device attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageFtsTest {
    private lateinit var database: OllamaDatabase
    private lateinit var messages: MessageDao

    @Before
    fun setUp() = runTest {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        messages = database.messageDao()
        database.conversationDao().insert(
            ConversationEntity(id = CONVERSATION, title = "Search", createdAt = 0, updatedAt = 0),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `fts5 virtual table exists`() = runTest {
        // The table is not a Room entity, so nothing but this callback creates
        // it and nothing but a query proves it was created.
        val names = tableNames()
        assertThat(names).contains(FtsSchema.MESSAGE_FTS_TABLE)
        assertThat(names).contains(FtsSchema.RAG_CHUNK_FTS_TABLE)
    }

    @Test
    fun `match finds an inserted message`() = runTest {
        insert("m1", "The quantisation table lists bits per weight.")
        insert("m2", "Nothing to do with the subject at hand.")

        val hits = messages.search("quantisation").first()

        assertThat(hits.map { it.uuid }).containsExactly("m1")
    }

    @Test
    fun `bm25 ranks the denser match first`() = runTest {
        insert("sparse", "A long paragraph that mentions tokenizer exactly once, ${filler(60)}")
        insert("dense", "tokenizer tokenizer tokenizer")

        val hits = messages.search("tokenizer").first()

        // FTS5 returns bm25 negated, so ascending order is best-first. Sorting
        // DESC here would return "sparse" and would do it without an error,
        // which is exactly why this assertion is on the order and not just on
        // set membership.
        assertThat(hits.map { it.uuid }).containsExactly("dense", "sparse").inOrder()
    }

    @Test
    fun `search is scoped to a conversation`() = runTest {
        database.conversationDao().insert(
            ConversationEntity(id = "other", title = "Other", createdAt = 0, updatedAt = 0),
        )
        insert("here", "shared keyword")
        insert("elsewhere", "shared keyword", conversationId = "other")

        val hits = messages.searchInConversation(CONVERSATION, "shared").first()

        assertThat(hits.map { it.uuid }).containsExactly("here")
    }

    @Test
    fun `user punctuation does not reach MATCH as syntax`() = runTest {
        insert("m1", "The error was ENOSPC on the data partition.")

        // Handed to MATCH raw, every one of these is either an FTS5 operator or
        // a syntax error. Quoted as phrases they are ordinary terms.
        val queries = listOf("ENOSPC", "\"ENOSPC\"", "ENOSPC: partition", "(ENOSPC)", "ENOSPC -- data")
        queries.forEach { query ->
            val hits = messages.search(query).first()
            assertThat(hits.map { it.uuid }).containsExactly("m1")
        }
    }

    @Test
    fun `an FTS5 operator word is treated as a term, not as an operator`() = runTest {
        insert("m1", "The error was ENOSPC on the data partition.")
        insert("m2", "ENOSPC and nothing else.")

        // Terms are joined by implicit AND, so a bare "AND" in the user's text
        // becomes a required word rather than a boolean. That is the safe
        // reading: the alternative is letting typed prose change the query
        // semantics.
        assertThat(messages.search("ENOSPC AND").first().map { it.uuid }).containsExactly("m2")
    }

    @Test
    fun `a query that tokenises to nothing yields no results and no error`() = runTest {
        insert("m1", "anything")

        assertThat(messages.search("   ").first()).isEmpty()
        assertThat(messages.search("!!! ***").first()).isEmpty()
    }

    @Test
    fun `prefix search finds a partial word`() = runTest {
        insert("m1", "quantisation is the lever")

        val match = FtsSchema.sanitizeMatchQuery("quanti", prefixLastToken = true)
        val hits = messages.searchRaw(FtsSchema.messageSearchQuery(match, 10)).first()

        assertThat(hits.map { it.uuid }).containsExactly("m1")
    }

    private suspend fun insert(uuid: String, content: String, conversationId: String = CONVERSATION) {
        messages.insert(
            MessageEntity(
                uuid = uuid,
                conversationId = conversationId,
                role = "user",
                content = content,
                createdAt = 0,
            ),
        )
    }

    private suspend fun tableNames(): List<String> = database.useReaderConnection { transactor ->
        transactor.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    }

    private fun filler(words: Int) = List(words) { "padding$it" }.joinToString(" ")

    private companion object {
        const val CONVERSATION = "conversation-1"
    }
}
