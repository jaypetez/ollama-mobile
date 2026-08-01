package io.github.jaypetez.ollamamobile.download.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.download.DownloadJson
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One downloadable entry: a model **at a specific quantisation from a specific
 * repository**, not a model family.
 *
 * `qwen2.5:1.5b` is a family; `qwen2.5-1.5b-instruct-q4_k_m.gguf` from a named
 * repository at a named revision is an entry, because that is the granularity at
 * which size, memory fit and download URL are determined.
 *
 * @property revision a commit SHA whenever one has been recorded. `main` is a
 *   moving pointer: the bytes behind it can change between the day [sha256] was
 *   recorded and the day a user downloads, and the mismatch is indistinguishable
 *   from corruption.
 * @property sizeBytes null until a maintainer has read it from the tree API. The
 *   app resolves it at download time when it is absent, at the cost of one extra
 *   request and of not being able to show a size in the picker beforehand.
 * @property sha256 the LFS `oid`. Null means the download is verified only
 *   against its declared length and the GGUF magic, which is a real and
 *   admitted reduction in guarantee.
 * @property verified false means **a maintainer has not confirmed this entry
 *   against the live repository**. Filename conventions and parameter counts here
 *   were written from memory of each publisher's conventions; sizes, hashes and
 *   revisions were not, and are left null rather than guessed. See
 *   [verificationNote].
 */
@Serializable
public data class CatalogEntry(
    public val id: String,
    public val displayName: String,
    public val family: String,
    public val repo: String,
    public val file: String,
    public val revision: String = DownloadSource.HuggingFace.DEFAULT_REVISION,
    public val quantization: String? = null,
    public val parameterCount: Long? = null,
    public val sizeBytes: Long? = null,
    public val sha256: String? = null,
    public val contextLength: Int? = null,
    public val capabilities: List<String> = listOf("CHAT"),
    public val licence: String? = null,
    /** Output dimensionality. Embedding entries only; it is part of the index format. */
    public val embeddingDimensions: Int? = null,
    /**
     * Verbatim, including trailing spaces.
     *
     * Mandatory for the embedding models that were trained with them; omitting or
     * mangling one is a silent quality bug rather than an error.
     */
    public val queryPrefix: String? = null,
    public val documentPrefix: String? = null,
    public val verified: Boolean = false,
    public val verificationNote: String? = null,
) {
    public val modelId: ModelId get() = ModelId("hf:$repo:$file")

    public val source: DownloadSource.HuggingFace
        get() = DownloadSource.HuggingFace(repo = repo, revision = revision)

    public val quantizationEnum: Quantization?
        get() = quantization?.let { name -> Quantization.entries.firstOrNull { it.name == name } }
            ?: Quantization.fromFileName(file)

    public val capabilitySet: Set<ModelCapability>
        get() = capabilities
            .mapNotNull { name -> ModelCapability.entries.firstOrNull { it.name == name } }
            .toSet()
            .ifEmpty { setOf(ModelCapability.CHAT) }

    /** A catalogue entry as the picker sees it, before anything is downloaded. */
    public fun toModelRef(): ModelRef = ModelRef(
        id = modelId,
        displayName = displayName,
        name = file,
        origin = ModelOrigin.Catalog(repo = repo, file = file),
        parameterCount = parameterCount,
        quantization = quantizationEnum,
        sizeBytes = sizeBytes,
        contextLength = contextLength,
        capabilities = capabilitySet,
    )
}

/**
 * The bundled catalogue.
 *
 * @property note carried in the asset itself so that anyone reading the JSON
 *   sees the same caveat as anyone reading this class.
 */
@Serializable
public data class ModelCatalog(
    public val schemaVersion: Int,
    public val generatedAt: String? = null,
    public val note: String? = null,
    public val chatModels: List<CatalogEntry> = emptyList(),
    public val embeddingModels: List<CatalogEntry> = emptyList(),
) {
    public val all: List<CatalogEntry> get() = chatModels + embeddingModels

    public fun find(id: String): CatalogEntry? = all.firstOrNull { it.id == id }

    public companion object {
        /** Bump when a field changes meaning; a reader that does not know the version must refuse. */
        public const val SCHEMA_VERSION: Int = 1

        public val EMPTY: ModelCatalog = ModelCatalog(schemaVersion = SCHEMA_VERSION)
    }
}

/**
 * Loads the catalogue from the APK's assets.
 *
 * The bundled copy is a **fallback**, and deliberately so: the catalogue is meant
 * to be refreshed from the documentation site without shipping a new APK, but a
 * first run on a device with no connectivity still has to show the user what
 * exists. So the asset must always be usable on its own, and a refresh layer —
 * not yet written — should overlay it rather than replace it.
 */
@Singleton
public class ModelCatalogSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private var cached: ModelCatalog? = null

        public suspend fun load(): ModelCatalog = withContext(io) {
            cached ?: read().also { cached = it }
        }

        private fun read(): ModelCatalog = try {
            val text = context.assets
                .open(ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
            val catalog = DownloadJson.decodeFromString(ModelCatalog.serializer(), text)
            // A newer schema may have changed what a field means, and guessing is
            // how a size in MiB gets read as bytes. An empty catalogue with a
            // "check for an update" message is the honest failure.
            if (catalog.schemaVersion > ModelCatalog.SCHEMA_VERSION) ModelCatalog.EMPTY else catalog
        } catch (e: IOException) {
            throw IllegalStateException("The bundled model catalogue is missing from the APK.", e)
        } catch (e: SerializationException) {
            throw IllegalStateException("The bundled model catalogue does not parse.", e)
        }

        public companion object {
            public const val ASSET_NAME: String = "models_catalog.json"
        }
    }
