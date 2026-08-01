package io.github.jaypetez.ollamamobile.data.rag

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.dispatcher.AppDispatchers
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.llm.testing.FakeLlamaEngine
import io.github.jaypetez.ollamamobile.storage.OllamaDatabase
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pipeline end to end, plus the two failures that are otherwise silent.
 *
 * Everything runs on [FakeLlamaEngine], so no native code is involved. See that
 * class for what its embeddings do and do not model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RagPipelineTest {
    private lateinit var database: OllamaDatabase
    private lateinit var context: Context
    private lateinit var repository: RagRepository

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }

    private val profile = EmbeddingModelProfile.NOMIC_EMBED_TEXT

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = OllamaDatabase.buildInMemory(context)
        repository = RagRepository(
            ragDao = database.ragDao(),
            messageDao = database.messageDao(),
            extractor = TextExtractor(context.contentResolver, dispatchers),
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --- the prefix regression -------------------------------------------

    /**
     * The test the whole [TaskPrefixer] class exists for.
     *
     * It measures retrieval quality with prefixes on and with prefixes off over
     * the same corpus, the same queries and the same engine, and asserts the
     * second is worse. Nothing else in the suite can catch a missing prefix:
     * with it removed, every other test here still passes, the vectors are still
     * finite, the shapes still match and no exception is thrown anywhere.
     *
     * The magnitude of the degradation is a property of [FakeLlamaEngine]'s
     * simulation and means nothing quantitatively. What is being asserted is
     * that the prefix reaches the engine at all and that the pipeline is wired to
     * a prefix-sensitive model correctly — on a real model, the same wiring bug
     * costs real accuracy.
     */
    @Test
    fun `retrieval measurably worsens when task prefixes are disabled`() = runTest(dispatcher) {
        // A short entry that mentions "thread count" in passing, and a long
        // section that is actually about it. This is the ordinary shape of a
        // real corpus, and it is where length normalisation decides the answer.
        //
        // An instructed encoder normalises roughly with the square root of the
        // passage length, so the long section wins on content. An uninstructed
        // one is dominated by surface form, which scales with length outright —
        // and the passing mention in the short entry wins instead. Nothing about
        // that failure is observable from outside: both runs return three finite
        // vectors and a confident ranking.
        val corpus = mapOf(
            "ctx" to "The context window defaults to four thousand ninety six tokens. " +
                "The thread count is configured separately.",
            "servers" to "A remote server is added by scanning the local subnet for an Ollama compatible " +
                "endpoint, or by typing its address directly into the server form.",
            "thermal" to "Thermal throttling reduces the worker thread count when the device sustains a high " +
                "skin temperature over a long generation. The thermal policy watches the headroom that the " +
                "platform reports and lowers the thread count in steps before the kernel intervenes far less " +
                "gracefully. Once the temperature falls back below the threshold the thermal policy restores " +
                "the original thread count, so a short conversation after a long one runs at full speed.",
        )
        val queries = mapOf(
            "ctx" to "default context window tokens",
            "servers" to "scanning the subnet for a server",
            "thermal" to "thermal throttling thread count",
        )

        val withPrefixes = scoreRetrieval(corpus, queries, prefixesEnabled = true)
        val withoutPrefixes = scoreRetrieval(corpus, queries, prefixesEnabled = false)

        // With the instruction, content decides and every query finds its own
        // passage. Without it, at least one is lost to length bias — silently,
        // with no error anywhere in the pipeline.
        assertThat(withPrefixes).isEqualTo(queries.size)
        assertThat(withoutPrefixes).isLessThan(withPrefixes)
    }

    @Test
    fun `the prefix actually reaches the engine, and differs by side`() = runTest(dispatcher) {
        val engine = FakeLlamaEngine()
        val service = LocalEmbeddingService(engine, profile)

        service.embedDocuments(listOf("a passage"))
        service.embedQuery("a question")

        // The specific failure this catches is using one prefix for both sides,
        // which looks entirely correct in review.
        assertThat(engine.embedCalls[0]).startsWith(profile.documentPrefix)
        assertThat(engine.embedCalls[1]).startsWith(profile.queryPrefix)
        assertThat(profile.queryPrefix).isNotEqualTo(profile.documentPrefix)
    }

    @Test
    fun `prefixing is idempotent so a retried index does not double-prefix`() {
        val prefixer = TaskPrefixer(profile)

        val once = prefixer.forDocument("body")
        val twice = prefixer.forDocument(once)

        assertThat(twice).isEqualTo(once)
    }

    @Test
    fun `a model with no known recipe gets no prefix rather than a guessed one`() {
        val unknown = DefaultEmbeddingModelRegistry().profileFor("some-new-encoder", dimensions = 512)

        // Feeding nomic's instruction to a model that never saw it is worse than
        // feeding none: it is unfamiliar text in every pooled vector.
        assertThat(unknown.usesTaskPrefixes).isFalse()
        assertThat(TaskPrefixer(unknown).forQuery("q")).isEqualTo("q")
    }

    @Test
    fun `the registry recognises a model by its file name, not only its exact id`() {
        val registry = DefaultEmbeddingModelRegistry()

        val resolved = registry.profileFor("nomic-ai/nomic-embed-text-v1.5-GGUF/model-Q4_K_M.gguf", 768)

        assertThat(resolved.queryPrefix).isEqualTo(EmbeddingModelProfile.NOMIC_EMBED_TEXT.queryPrefix)
    }

    /** How many queries retrieved their own document first. */
    private suspend fun scoreRetrieval(
        corpus: Map<String, String>,
        queries: Map<String, String>,
        prefixesEnabled: Boolean,
    ): Int {
        val engine = FakeLlamaEngine()
        val prefixer = TaskPrefixer(profile, enabled = prefixesEnabled)
        val service = LocalEmbeddingService(engine, profile, prefixer)

        val vectors = service.embedDocuments(corpus.values.toList()).expectSuccess()
        val store = VectorStore()
        store.load(
            corpus.keys.mapIndexed { index, id ->
                VectorStore.Entry(id, VectorQuantizer.quantize(vectors[index]))
            },
        )

        return queries.count { (expectedId, query) ->
            val queryVector = service.embedQuery(query).expectSuccess()
            store.search(queryVector, 1).firstOrNull()?.chunkUuid == expectedId
        }
    }

    // --- citations --------------------------------------------------------

    @Test
    fun `the injector numbers markers from one and the citations resolve back to chunks`() =
        runTest(dispatcher) {
            val documentId = indexDocument("Notes.md", THERMAL_DOCUMENT)
            val service = LocalEmbeddingService(FakeLlamaEngine(), profile)
            val assistantUuid = seedAssistantMessage()

            val injected = repository
                .buildContext("thermal throttling thread count", service, assistantUuid)
                .expectSuccess()

            assertThat(injected.contextBlock).contains("<context>")
            assertThat(injected.contextBlock).contains("[1]")
            assertThat(injected.citations).isNotEmpty()

            // Dense, 1-based, and in the same order as the markers.
            assertThat(injected.citations.map { it.rank })
                .isEqualTo((1..injected.citations.size).toList())

            for (citation in injected.citations) {
                assertThat(injected.contextBlock).contains("[${citation.rank}]")
                // The chip must resolve to a real chunk of a real document.
                val chunk = repository.findChunk(citation.chunkUuid)
                assertThat(chunk).isNotNull()
                assertThat(chunk!!.documentId).isEqualTo(documentId)
                assertThat(injected.contextBlock).contains(chunk.text.take(40))
            }

            repository.saveCitations(injected.citations)
            val persisted = repository.observeCitations(assistantUuid).first()
            assertThat(persisted.map { it.chunkUuid })
                .containsExactlyElementsIn(injected.citations.map { it.chunkUuid })
        }

    @Test
    fun `nothing retrieved means no context block and no citations`() = runTest(dispatcher) {
        val service = LocalEmbeddingService(FakeLlamaEngine(), profile)

        val injected = repository
            .buildContext("anything at all", service, seedAssistantMessage())
            .expectSuccess()

        // An empty <context> block would teach the model that the block is
        // sometimes meaningless, which degrades its use of a populated one.
        assertThat(injected.contextBlock).isEmpty()
        assertThat(injected.citations).isEmpty()
    }

    @Test
    fun `a citation keeps its quoted text so a chip still shows something after deletion`() =
        runTest(dispatcher) {
            indexDocument("Notes.md", THERMAL_DOCUMENT)
            val service = LocalEmbeddingService(FakeLlamaEngine(), profile)

            val injected = repository
                .buildContext("thermal throttling", service, seedAssistantMessage())
                .expectSuccess()

            assertThat(injected.citations.first().quotedText).isNotEmpty()
        }

    // --- end to end -------------------------------------------------------

    @Test
    fun `three markdown files index, then each one is retrieved by its own question`() =
        runTest(dispatcher) {
            val service = LocalEmbeddingService(FakeLlamaEngine(), profile)

            val contextDoc = indexDocument("context.md", CONTEXT_DOCUMENT, service)
            val thermalDoc = indexDocument("thermal.md", THERMAL_DOCUMENT, service)
            val serversDoc = indexDocument("servers.md", SERVERS_DOCUMENT, service)

            val documents = repository.observeDocuments().first()
            assertThat(documents).hasSize(3)
            assertThat(documents.map { it.state }).containsExactly(
                IndexState.INDEXED,
                IndexState.INDEXED,
                IndexState.INDEXED,
            )
            assertThat(documents.all { it.chunkCount > 0 }).isTrue()
            assertThat(documents.all { it.embeddingModelId == profile.modelId }).isTrue()
            assertThat(documents.all { it.progress == 1f }).isTrue()

            // Questions phrased with the corpus' own vocabulary. The fake engine
            // is a bag of words with no semantics, so a paraphrase would test
            // nothing about this pipeline and everything about a model that is
            // not present. What is under test here is that indexing, storage,
            // fusion and citation wiring carry a match end to end.
            val expectations = mapOf(
                "default context window tokens n_ctx" to contextDoc,
                "thermal throttling worker thread count" to thermalDoc,
                "scanning the local subnet for a server" to serversDoc,
            )
            for ((question, expectedDocument) in expectations) {
                val injected = repository
                    .buildContext(question, service, seedAssistantMessage())
                    .expectSuccess()

                assertThat(injected.citations).isNotEmpty()
                assertThat(injected.citations.first().documentId).isEqualTo(expectedDocument)
            }
        }

    @Test
    fun `deleting a document removes its chunks from retrieval`() = runTest(dispatcher) {
        val service = LocalEmbeddingService(FakeLlamaEngine(), profile)
        val documentId = indexDocument("thermal.md", THERMAL_DOCUMENT, service)

        repository.delete(documentId)

        val injected = repository
            .buildContext("thermal throttling", service, seedAssistantMessage())
            .expectSuccess()
        assertThat(injected.citations).isEmpty()
    }

    @Test
    fun `an unsupported format is refused at import, before any work is done`() = runTest(dispatcher) {
        val result = repository.import(Uri.parse("file:///tmp/paper.pdf"), "paper.pdf", "application/pdf", 10)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.message).contains("PDF")
        assertThat(repository.observeDocuments().first()).isEmpty()
    }

    @Test
    fun `an index failure marks the document failed with a readable reason`() = runTest(dispatcher) {
        val file = File(context.cacheDir, "empty.md").apply { writeText("   \n\n  ") }
        val documentId = repository
            .import(Uri.fromFile(file), "empty.md", "text/markdown", file.length())
            .expectSuccess()

        val outcome = repository.index(documentId, LocalEmbeddingService(FakeLlamaEngine(), profile))

        assertThat(outcome).isInstanceOf(AppResult.Failure::class.java)
        val document = repository.observeDocuments().first().single()
        assertThat(document.state).isEqualTo(IndexState.FAILED)
        assertThat(document.errorMessage).isNotEmpty()
    }

    // --- helpers ----------------------------------------------------------

    private suspend fun indexDocument(
        name: String,
        body: String,
        service: EmbeddingService = LocalEmbeddingService(FakeLlamaEngine(), profile),
    ): String {
        val file = File(context.cacheDir, name).apply { writeText(body) }
        val documentId = repository
            .import(Uri.fromFile(file), name, "text/markdown", file.length())
            .expectSuccess()
        repository.index(documentId, service).expectSuccess()
        return documentId
    }

    private suspend fun seedAssistantMessage(): String {
        // A citation row has a foreign key onto messages, so a real message has
        // to exist before saveCitations can succeed.
        val conversationId = UUID.randomUUID().toString()
        database.conversationDao().insert(
            ConversationEntity(id = conversationId, title = "t", createdAt = 0, updatedAt = 0),
        )
        val messageUuid = UUID.randomUUID().toString()
        database.messageDao().insert(
            MessageEntity(
                uuid = messageUuid,
                conversationId = conversationId,
                role = "assistant",
                content = "",
                createdAt = 0,
            ),
        )
        return messageUuid
    }

    private fun <T> AppResult<T>.expectSuccess(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AssertionError("Expected success but failed: ${error.message}")
    }

    private companion object {
        val CONTEXT_DOCUMENT = """
            # Context length

            The context window is configured with n_ctx and defaults to four thousand
            ninety six tokens on most models. Raising it costs memory for the key value
            cache, which grows linearly with the number of tokens the window holds.

            ## Trimming

            When a conversation exceeds the window the oldest turns are trimmed first.
        """.trimIndent()

        val THERMAL_DOCUMENT = """
            # Thermal behaviour

            Thermal throttling reduces the thread count when the device sustains a high
            skin temperature. The policy watches the thermal headroom reported by the
            platform and lowers the number of worker threads before the kernel does it
            less gracefully.

            ## Recovery

            Threads are restored once the temperature falls back below the threshold.
        """.trimIndent()

        val SERVERS_DOCUMENT = """
            # Remote servers

            A remote server is added by scanning the local subnet for an Ollama
            compatible endpoint, or by typing its address directly. Discovery probes
            each address on the subnet and records the ones that answer a health check.

            ## Policy

            Scanning is refused entirely when the network policy is set to offline.
        """.trimIndent()
    }
}
