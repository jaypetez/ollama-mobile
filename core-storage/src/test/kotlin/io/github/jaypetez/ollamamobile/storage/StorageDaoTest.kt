package io.github.jaypetez.ollamamobile.storage

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.storage.entity.BenchmarkResultEntity
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageStatusColumn
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import io.github.jaypetez.ollamamobile.storage.entity.PromptTemplateEntity
import io.github.jaypetez.ollamamobile.storage.entity.ServerAuthColumn
import io.github.jaypetez.ollamamobile.storage.entity.ServerConfigEntity
import io.github.jaypetez.ollamamobile.storage.entity.SettingEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageDaoTest {
    private lateinit var database: OllamaDatabase

    @Before
    fun setUp() {
        database = OllamaDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `conversations sort pinned first then by recency`() = runTest {
        val dao = database.conversationDao()
        dao.insert(conversation("a", updatedAt = 300))
        dao.insert(conversation("b", updatedAt = 200))
        dao.insert(conversation("c", updatedAt = 100))
        dao.setPinned("c", pinned = true, updatedAt = 100)

        assertThat(dao.observeActive().first().map { it.id }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `archiving removes a conversation from the active list without deleting it`() = runTest {
        val dao = database.conversationDao()
        dao.insert(conversation("a"))

        dao.setArchived("a", archived = true, updatedAt = 1)

        assertThat(dao.observeActive().first()).isEmpty()
        assertThat(dao.observeAll().first()).hasSize(1)
    }

    @Test
    fun `sampling overrides survive the round trip, nulls included`() = runTest {
        val dao = database.conversationDao()
        dao.insert(
            conversation("a").copy(
                temperature = 0.7,
                topK = 40,
                stopSequences = listOf("</s>", "\nUser:"),
            ),
        )

        val stored = requireNotNull(dao.find("a"))

        assertThat(stored.temperature).isEqualTo(0.7)
        assertThat(stored.topK).isEqualTo(40)
        // null is "use the engine default", which is a different state from any
        // number, so it has to come back as null rather than as zero.
        assertThat(stored.topP).isNull()
        assertThat(stored.stopSequences).containsExactly("</s>", "\nUser:").inOrder()
    }

    @Test
    fun `message flows emit on every write`() = runTest {
        database.conversationDao().insert(conversation("a"))
        val dao = database.messageDao()

        dao.observeConversation("a").test {
            assertThat(awaitItem()).isEmpty()

            dao.insert(message("m1", "a", "hello"))
            assertThat(awaitItem().map { it.uuid }).containsExactly("m1")

            dao.appendContent("m1", " world")
            assertThat(awaitItem().single().content).isEqualTo("hello world")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `finishing a streamed message records its stats`() = runTest {
        database.conversationDao().insert(conversation("a"))
        val dao = database.messageDao()
        dao.insert(message("m1", "a", "", status = MessageStatusColumn.PENDING))

        dao.finish(
            uuid = "m1",
            status = MessageStatusColumn.COMPLETE,
            errorMessage = null,
            completionTokens = 42,
            evalNanos = 2_000_000_000,
            totalNanos = 2_500_000_000,
        )

        val stored = requireNotNull(dao.find("m1"))
        assertThat(stored.status).isEqualTo(MessageStatusColumn.COMPLETE)
        assertThat(stored.completionTokens).isEqualTo(42)
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        database.conversationDao().insert(conversation("a"))
        database.messageDao().insert(message("m1", "a", "text"))

        database.conversationDao().deleteById("a")

        assertThat(database.messageDao().find("m1")).isNull()
    }

    @Test
    fun `forgetting a server takes its cached models with it`() = runTest {
        val servers = database.serverDao()
        val models = database.modelDao()
        servers.upsert(
            ServerConfigEntity(
                id = "s1",
                label = "Desk",
                baseUrl = "http://192.168.1.40:11434",
                authType = ServerAuthColumn.BEARER,
                tokenRefAlias = "server.s1.bearer",
            ),
        )
        models.upsert(
            ModelEntity(
                id = "s1/qwen3",
                displayName = "Qwen3",
                name = "qwen3:1.7b",
                originType = ModelOriginColumn.REMOTE,
                serverId = "s1",
            ),
        )

        servers.deleteById("s1")
        models.deleteForServer("s1")

        assertThat(servers.find("s1")).isNull()
        assertThat(models.observeAll().first()).isEmpty()
    }

    @Test
    fun `no credential value is stored on the server row`() = runTest {
        val servers = database.serverDao()
        servers.upsert(
            ServerConfigEntity(
                id = "s1",
                label = "Desk",
                baseUrl = "https://ollama.example.org",
                authType = ServerAuthColumn.BEARER,
                tokenRefAlias = "server.s1.bearer",
            ),
        )

        val stored = requireNotNull(servers.find("s1"))

        // Only the alias. The bytes live in the Keystore-backed SecretsStore,
        // which is what makes this row safe to export.
        assertThat(stored.tokenRefAlias).isEqualTo("server.s1.bearer")
        assertThat(stored.toString()).doesNotContain("Bearer ")
    }

    @Test
    fun `settings are addressable by prefix`() = runTest {
        val dao = database.settingDao()
        dao.upsertAll(
            listOf(
                SettingEntity("model.q4.threads", "4", 0),
                SettingEntity("model.q4.context", "4096", 0),
                SettingEntity("ui.theme", "dark", 0),
            ),
        )

        assertThat(dao.observeWithPrefix("model.q4.").first()).hasSize(2)

        dao.deleteWithPrefix("model.")

        assertThat(dao.observeAll().first().map { it.key }).containsExactly("ui.theme")
    }

    @Test
    fun `the best benchmark is scoped to one configuration`() = runTest {
        val dao = database.benchmarkDao()
        dao.insert(benchmark("r1", threads = 4, tokensPerSecond = 12.0))
        dao.insert(benchmark("r2", threads = 4, tokensPerSecond = 18.0))
        dao.insert(benchmark("r3", threads = 8, tokensPerSecond = 30.0))

        val best = dao.findBest("m1", backend = "cpu", threads = 4, contextLength = 4096)

        // The 8-thread run is faster and irrelevant: comparing across
        // configurations compares numbers that are not comparable.
        assertThat(best?.id).isEqualTo("r2")
    }

    @Test
    fun `a built-in prompt template cannot be deleted as a user template`() = runTest {
        val dao = database.promptTemplateDao()
        dao.upsert(
            PromptTemplateEntity(
                id = "t1",
                title = "Summarise",
                body = "Summarise the following.",
                builtIn = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        dao.deleteUserTemplate("t1")

        assertThat(dao.find("t1")).isNotNull()
    }

    @Test
    fun `recording a use increments the counter`() = runTest {
        val dao = database.promptTemplateDao()
        dao.upsert(
            PromptTemplateEntity(id = "t1", title = "T", body = "B", createdAt = 0, updatedAt = 0),
        )

        repeat(3) { dao.recordUse("t1") }

        assertThat(dao.find("t1")?.usageCount).isEqualTo(3)
    }

    @Test
    fun `model capabilities round-trip as a set`() = runTest {
        val dao = database.modelDao()
        dao.upsert(
            ModelEntity(
                id = "m1",
                displayName = "Embed",
                name = "nomic-embed-text",
                originType = ModelOriginColumn.LOCAL,
                localPath = "/data/models/m1/model.gguf",
                capabilities = setOf("EMBEDDING"),
                embeddingDimensions = 768,
            ),
        )

        val stored = requireNotNull(dao.find("m1"))

        assertThat(stored.capabilities).containsExactly("EMBEDDING")
        assertThat(stored.embeddingDimensions).isEqualTo(768)
    }

    private fun conversation(id: String, updatedAt: Long = 0) =
        ConversationEntity(id = id, title = "Thread $id", createdAt = 0, updatedAt = updatedAt)

    private fun message(
        uuid: String,
        conversationId: String,
        content: String,
        status: String = MessageStatusColumn.COMPLETE,
    ) = MessageEntity(
        uuid = uuid,
        conversationId = conversationId,
        role = "user",
        content = content,
        createdAt = 0,
        status = status,
    )

    private fun benchmark(id: String, threads: Int, tokensPerSecond: Double) = BenchmarkResultEntity(
        id = id,
        modelId = "m1",
        device = "Robolectric",
        backend = "cpu",
        threads = threads,
        contextLength = 4096,
        batchSize = 512,
        tokensPerSecond = tokensPerSecond,
        createdAt = 0,
    )
}
