package io.github.jaypetez.ollamamobile.download.catalog

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.Quantization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bundled asset has to be usable on a device that has never been online, so
 * "it parses" is a real requirement rather than a formality.
 */
@RunWith(RobolectricTestRunner::class)
class ModelCatalogTest {
    private val source = ModelCatalogSource(ApplicationProvider.getApplicationContext(), Dispatchers.IO)

    private fun catalog(): ModelCatalog = runBlocking { source.load() }

    @Test
    fun `the bundled catalogue parses at the schema version this build understands`() {
        val catalog = catalog()

        assertThat(catalog.schemaVersion).isEqualTo(ModelCatalog.SCHEMA_VERSION)
        assertThat(catalog.chatModels).isNotEmpty()
        assertThat(catalog.embeddingModels).isNotEmpty()
        // The caveat travels with the data, not only with the code that reads it.
        assertThat(catalog.note).contains("BUNDLED FALLBACK")
        assertThat(catalog.note).contains("refreshed from the documentation site")
    }

    @Test
    fun `every entry names a repository, a file and a quantisation this build models`() {
        catalog().all.forEach { entry ->
            assertThat(entry.repo).contains("/")
            assertThat(entry.file).endsWith(".gguf")
            assertThat(entry.quantizationEnum).isNotNull()
            assertThat(entry.parameterCount).isNotNull()
            assertThat(entry.modelId.value).isEqualTo("hf:${entry.repo}:${entry.file}")
        }
    }

    @Test
    fun `unverified entries carry a note and no invented size or hash`() {
        // Guessing a hash is worse than having none: a wrong one presents to the
        // user as a corrupted download of a perfectly good file.
        catalog().all.filterNot { it.verified }.forEach { entry ->
            assertThat(entry.verificationNote).isNotNull()
            assertThat(entry.sizeBytes).isNull()
            assertThat(entry.sha256).isNull()
        }
    }

    @Test
    fun `every model is small enough to be plausible on a phone`() {
        // The practical ceiling is around 8B at Q4_K_M, and that is already tight
        // on an 8 GB device once a context is allocated.
        catalog().chatModels.forEach { entry ->
            assertThat(entry.parameterCount!!).isLessThan(8_500_000_000L)
            assertThat(entry.quantizationEnum!!.bitsPerWeight)
                .isAtLeast(Quantization.Q4_0.bitsPerWeight)
        }
    }

    @Test
    fun `the embedding entry records its dimensionality and its mandatory prefixes`() {
        val embedding = catalog().embeddingModels.first()

        assertThat(embedding.capabilitySet).contains(ModelCapability.EMBEDDING)
        assertThat(embedding.embeddingDimensions).isNotNull()
        // Verbatim, trailing space included: the model was trained with these and
        // mangling one degrades retrieval silently rather than failing.
        assertThat(embedding.queryPrefix).isEqualTo("search_query: ")
        assertThat(embedding.documentPrefix).isEqualTo("search_document: ")
    }

    @Test
    fun `an entry converts to the picker's model reference`() {
        val entry = catalog().chatModels.first()

        val ref = entry.toModelRef()

        assertThat(ref.id).isEqualTo(entry.modelId)
        assertThat(ref.quantization).isEqualTo(entry.quantizationEnum)
        // No size yet, so the picker falls back to parameters times bits-per-weight.
        assertThat(ref.estimatedWeightBytes).isNotNull()
    }
}
