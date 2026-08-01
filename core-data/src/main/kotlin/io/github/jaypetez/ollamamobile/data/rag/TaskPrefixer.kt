package io.github.jaypetez.ollamamobile.data.rag

/**
 * Applies the embedding model's task instruction.
 *
 * ## Read this before deleting anything here
 *
 * This is the highest-consequence, lowest-visibility component in the retrieval
 * pipeline. Every other mistake in RAG announces itself: a bad chunk boundary is
 * visible in the chunk list, a dimension mismatch throws, a broken FTS query
 * returns nothing. Omitting the task prefix produces no exception, no warning,
 * no shape change and no empty result — just quietly worse answers, on a system
 * whose output nobody can eyeball for correctness. It is the kind of bug that
 * survives a release and gets diagnosed as "the small model isn't very good".
 *
 * Asymmetric retrieval models are trained with an instruction that tells the
 * encoder which side of the pair it is encoding. `search_query:` and
 * `search_document:` are not decoration; they are the mechanism by which a
 * six-word question and the three-hundred-word passage that answers it end up
 * near each other in the same space. Strip them and the encoder falls back on
 * surface form, where questions look like other questions and passages look like
 * other passages.
 *
 * ## Rules this type enforces
 *
 * * The prefix comes from the model ([EmbeddingModelProfile]), never from a
 *   constant here. Wrong-model prefixes are worse than none.
 * * Query and document get *different* prefixes. Using one for both is the
 *   subtle version of the same bug and looks correct in code review.
 * * It is idempotent. Indexing retries and re-embeds, and double-prefixing puts
 *   real tokens into the pooled average.
 * * Disabling it is explicit and only exists so a test can measure what it
 *   costs. There is no product reason to turn this off.
 */
public class TaskPrefixer(
    private val profile: EmbeddingModelProfile,
    /**
     * Off only in the regression test that measures the damage.
     *
     * A flag rather than a second code path: the test has to exercise the same
     * pipeline the app runs, or it proves nothing about the app.
     */
    private val enabled: Boolean = true,
) {
    /** Text to embed as a search query. */
    public fun forQuery(text: String): String = apply(profile.queryPrefix, text)

    /** Text to embed as an indexed passage. */
    public fun forDocument(text: String): String = apply(profile.documentPrefix, text)

    /** Both prefixes, so a caller can strip either when reading a stored chunk back. */
    public fun strip(text: String): String {
        for (prefix in listOf(profile.queryPrefix, profile.documentPrefix)) {
            if (prefix.isNotEmpty() && text.startsWith(prefix)) return text.removePrefix(prefix)
        }
        return text
    }

    private fun apply(prefix: String, text: String): String = when {
        !enabled || prefix.isEmpty() -> text

        // Idempotence. Indexing is a retryable WorkManager job and a chunk can
        // reach here twice; "search_query: search_query: foo" is not a no-op,
        // it is two real tokens averaged into every dimension of the vector.
        text.startsWith(prefix) -> text

        else -> prefix + text
    }
}
