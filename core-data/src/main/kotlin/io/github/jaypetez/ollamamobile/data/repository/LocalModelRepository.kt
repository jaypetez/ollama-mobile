package io.github.jaypetez.ollamamobile.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.mapper.toEntity
import io.github.jaypetez.ollamamobile.download.CompatibilityChecker
import io.github.jaypetez.ollamamobile.download.DownloadRequest
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.download.InstalledModelMetadata
import io.github.jaypetez.ollamamobile.download.ModelStorage
import io.github.jaypetez.ollamamobile.download.RemoteFile
import io.github.jaypetez.ollamamobile.llm.LlamaEngine
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.GgufMetadata
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.remote.health.WallClock
import io.github.jaypetez.ollamamobile.storage.MemoryEstimator
import io.github.jaypetez.ollamamobile.storage.ModelMemoryRequest
import io.github.jaypetez.ollamamobile.storage.dao.ModelDao
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One GGUF that is installed on this device, with everything the picker and the
 * loader need to reason about it.
 *
 * [verdict] is a *snapshot*: available memory moves as other apps come and go,
 * so the figure shown in a list is the one from the last scan and the figure
 * the loader acts on is [LocalModelRepository.verdictFor], taken at the moment
 * of the load. Showing a stale "Fits" and then refusing is worse than
 * re-checking, and re-parsing every GGUF header per frame is not an option.
 */
data class LocalModelRecord(
    val ref: ModelRef,
    /** Absolute path of the file the loader is pointed at. */
    val path: String,
    val sizeBytes: Long,
    /** Where the bytes came from — `huggingface.co/owner/repo`, a hostname, or "Imported". */
    val origin: String,
    val downloadedAtMillis: Long,
    val verdict: MemoryVerdict,
    /** `general.architecture`, when the header carried one. */
    val architecture: String?,
    /** The context the verdict was computed against, which is not the model's maximum. */
    val budgetedContextLength: Int,
) {
    val id: ModelId get() = ref.id

    val quantization: Quantization? get() = ref.quantization

    val parameterCount: Long? get() = ref.parameterCount

    /** False when the memory estimate refuses this model on this device. */
    val loadable: Boolean get() = verdict.allowsLoad
}

/**
 * The on-device model library: what is on disk, whether it will fit, and the
 * Room rows that let the rest of the app see it.
 *
 * ## Why this writes to the `models` table
 *
 * [ModelRepository.localModels] reads local-origin rows and, until this class
 * existed, nothing wrote any — which is exactly why the router could not
 * produce an `InferenceTarget.Local`. Scanning the filesystem into that table
 * is the wiring change the router's local branch was written in anticipation
 * of; the picker, the router and the chat header all keep reading the same
 * table they always did.
 *
 * ## Why the scan is explicit rather than a `FileObserver`
 *
 * The directory changes only when this app downloads, imports or deletes a
 * model, all of which call [refresh] on the way out. An inotify watch on a tree
 * would be a thread and a wake-up per shard write during a multi-gigabyte
 * download, for information the writer already has.
 */
@Singleton
class LocalModelRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val storage: ModelStorage,
        private val compatibility: CompatibilityChecker,
        private val memoryEstimator: MemoryEstimator,
        private val modelDao: ModelDao,
        private val engine: LlamaEngine,
        private val clock: WallClock,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        private val _models = MutableStateFlow<List<LocalModelRecord>>(emptyList())

        /**
         * Every installed model, best-name-first.
         *
         * Empty is ambiguous on its own — "nothing downloaded" and "this build
         * cannot run anything" look identical — so a consumer must read
         * [engineAvailable] alongside it and say which one it is.
         */
        val models: StateFlow<List<LocalModelRecord>> = _models.asStateFlow()

        /** False for a `-Pollama.nativeSource=none` build. Nothing here is loadable then. */
        val engineAvailable: Boolean = engine.isAvailable

        /** The model the engine is holding in memory right now, or null. */
        val resident: StateFlow<ModelRef?> = engine.loadedModel

        /**
         * Re-reads the model directory and republishes [models].
         *
         * Parses every GGUF header, so it is genuinely I/O — call it after a
         * download, an import or a delete, and when the model manager opens.
         * Never on a hot path.
         */
        suspend fun refresh(): List<LocalModelRecord> = withContext(io) {
            scanned = true
            val records = scan()
            _models.value = records
            syncRows(records)
            records
        }

        /** Whether [refresh] has ever run. See [ensureScanned]. */
        private var scanned = false

        /**
         * Scans once, if nothing has scanned yet.
         *
         * Nothing populates [models] at process start — a scan parses every
         * GGUF header, which is not something to do on the critical path of a
         * cold launch. Every consumer that needs the list to be real calls this
         * first: without it the router would find no local candidates until the
         * model manager screen happened to be opened, which is the kind of bug
         * that looks like "local inference sometimes works".
         */
        suspend fun ensureScanned() {
            if (scanned) return
            scanned = true
            refresh()
        }

        suspend fun find(id: ModelId): LocalModelRecord? =
            _models.value.firstOrNull { it.id == id } ?: refresh().firstOrNull { it.id == id }

        /** The absolute path to hand [io.github.jaypetez.ollamamobile.llm.ModelLoadSpec], or null. */
        suspend fun pathFor(id: ModelId): String? = find(id)?.path

        /**
         * The verdict *now*, not the one from the last scan.
         *
         * Cheap: the header is not re-read, only the arithmetic is redone
         * against the current `availMem`. This is what a load must gate on —
         * see the note on [LocalModelRecord.verdict].
         */
        suspend fun verdictFor(id: ModelId, contextLength: Int? = null): MemoryVerdict? = withContext(io) {
            val record = find(id) ?: return@withContext null
            val metadata = metadataCache[id] ?: return@withContext record.verdict
            memoryEstimator.verdict(
                ModelMemoryRequest(
                    metadata = metadata,
                    contextLength = contextLength ?: record.budgetedContextLength,
                    fileSizeBytes = record.sizeBytes,
                ),
            )
        }

        /**
         * Deletes the files and the row.
         *
         * The model is unloaded first when it is the resident one: deleting the
         * file an mmap is pointing at leaves the mapping valid and the directory
         * entry gone, so the model would keep answering from a file the user
         * believes they deleted.
         */
        suspend fun delete(id: ModelId): AppResult<Unit> = withContext(io) {
            val record = find(id) ?: return@withContext AppResult.Failure(
                AppError.Storage.NotFound(what = "model ${id.value}"),
            )
            if (engine.loadedModel.value?.id == id) engine.unload()
            val storageDir = storageDirOf(record)
            val deleted = if (storageDir == null) File(record.path).delete() else storage.delete(storageDir)
            modelDao.deleteById(id.value)
            refresh()
            if (deleted) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.Storage.Io(message = "Could not delete ${record.ref.displayName}."))
            }
        }

        /**
         * Copies a GGUF the user picked with the Storage Access Framework into
         * the app's model directory.
         *
         * The copy is not an inefficiency to be optimised away. Weights are
         * memory-mapped, and a SAF descriptor carries no promise of a stable,
         * seekable, mappable region — the provider behind it may be a cloud
         * client that streams. See the note on `ModelLoadSpec.path`.
         */
        suspend fun importGguf(uri: Uri, fileName: String): AppResult<LocalModelRecord> = withContext(io) {
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            if (!safeName.endsWith(GGUF_EXTENSION, ignoreCase = true)) {
                return@withContext AppResult.Failure(
                    AppError.Model.Unsupported(reason = "$safeName is not a .gguf file."),
                )
            }
            val storageDir = "$IMPORTED_DIR/${safeName.removeSuffix(GGUF_EXTENSION)}"
            val request = DownloadRequest(
                modelId = importedModelId(safeName),
                displayName = safeName.removeSuffix(GGUF_EXTENSION),
                source = DownloadSource.CustomUrl(
                    url = uri.toString(),
                    storageDir = storageDir,
                    originLabel = IMPORTED_ORIGIN,
                ),
                files = listOf(RemoteFile(fileName = safeName)),
            )
            try {
                copyIntoPartFile(uri, storageDir, safeName)
                // publish() does the fsync, the GGUF magic check, the atomic
                // rename and the completion marker, in that order. Repeating any
                // of it here would be a second implementation of the one thing
                // that must not be got wrong twice.
                storage.publish(request, digests = emptyMap(), nowMillis = clock.nowMillis())
            } catch (e: AppErrorException) {
                storage.clearDownloadDir(storageDir)
                return@withContext AppResult.Failure(e.error)
            } catch (e: IOException) {
                storage.clearDownloadDir(storageDir)
                return@withContext AppResult.Failure(
                    AppError.Storage.Io(message = "Could not copy $safeName into the app.", cause = e),
                )
            }
            val record = refresh().firstOrNull { it.id.value == request.modelId.value }
            record
                ?.let { AppResult.Success(it) }
                ?: AppResult.Failure(AppError.Storage.Io(message = "$safeName was copied but could not be read."))
        }

        // -------------------------------------------------------------- scanning

        /**
         * Parsed headers, kept so [verdictFor] can redo the arithmetic without
         * re-reading megabytes of tensor names. Written only by [scan].
         */
        private val metadataCache = mutableMapOf<ModelId, GgufMetadata>()

        private suspend fun scan(): List<LocalModelRecord> {
            val root = storage.modelsRoot
            if (!root.isDirectory) return emptyList()
            val markers = root
                .walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .filter { it.isFile && it.name == ModelStorage.METADATA_FILE }
                .toList()
            metadataCache.clear()
            return markers
                .mapNotNull { marker -> recordFor(root, marker) }
                .sortedBy { it.ref.displayName.lowercase() }
        }

        private suspend fun recordFor(root: File, marker: File): LocalModelRecord? {
            val storageDir = marker.parentFile
                ?.relativeToOrNull(root)
                ?.invariantSeparatorsPath
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            // installedMetadata re-checks that every listed file is present at
            // its recorded length, so a half-deleted directory reads as absent
            // rather than as a model that explodes inside ggml.
            val metadata = storage.installedMetadata(storageDir) ?: return null
            val file = storage.installedFile(storageDir, metadata.primaryFileName)
            if (!file.isFile) return null

            val report = try {
                compatibility.checkLocalFile(file)
            } catch (e: AppErrorException) {
                Timber.w(e, "Could not read the header of %s.", file.name)
                return null
            }
            val id = ModelId(metadata.modelId)
            metadataCache[id] = report.metadata
            return LocalModelRecord(
                ref = ModelRef(
                    id = id,
                    displayName = metadata.displayName,
                    name = metadata.primaryFileName,
                    origin = ModelOrigin.Local(file.absolutePath),
                    parameterCount = report.metadata.parameterCount,
                    quantization = report.quantization,
                    sizeBytes = storage.sizeOnDisk(storageDir),
                    contextLength = report.metadata.contextLength,
                    capabilities = capabilitiesOf(report.metadata),
                    chatTemplate = report.metadata.chatTemplate,
                ),
                path = file.absolutePath,
                sizeBytes = storage.sizeOnDisk(storageDir),
                origin = metadata.origin,
                downloadedAtMillis = metadata.downloadedAtEpochMillis,
                verdict = report.verdict,
                architecture = report.metadata.architecture,
                budgetedContextLength = report.contextLength,
            )
        }

        /**
         * Replaces every local-origin row with what is actually on disk.
         *
         * Delete-then-insert for the same reason [ModelRepository] does it per
         * server: a model the user deleted has to leave the picker, and an
         * upsert cannot express a deletion.
         */
        private suspend fun syncRows(records: List<LocalModelRecord>) {
            val wanted = records.associateBy { it.id.value }
            modelDao
                .observeByOrigin(ModelOriginColumn.LOCAL)
                .first()
                .map { it.id }
                .filterNot { it in wanted }
                .forEach { modelDao.deleteById(it) }
            if (records.isNotEmpty()) {
                modelDao.upsertAll(records.map { it.ref.toEntity(installedAt = it.downloadedAtMillis) })
            }
        }

        private fun copyIntoPartFile(uri: Uri, storageDir: String, fileName: String) {
            val part = storage.partFile(storageDir, fileName)
            part.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw AppError.Storage
                    .NotFound(what = "the file you picked")
                    .asAppException()
            input.use { source -> part.outputStream().use { target -> source.copyTo(target, COPY_BUFFER_BYTES) } }
        }

        private fun storageDirOf(record: LocalModelRecord): String? {
            val root = storage.modelsRoot
            return File(record.path)
                .parentFile
                ?.relativeToOrNull(root)
                ?.invariantSeparatorsPath
                ?.takeIf { it.isNotEmpty() }
        }

        private fun capabilitiesOf(metadata: GgufMetadata): Set<ModelCapability> = buildSet {
            add(ModelCapability.CHAT)
            // The chat template is the only signal in the header that the model
            // was trained to emit a reasoning block; guessing from the name
            // mislabels every fine-tune.
            if (metadata.chatTemplate?.contains(THINK_MARKER) == true) add(ModelCapability.REASONING)
        }

        private fun importedModelId(fileName: String): ModelId = ModelId("$IMPORTED_ID_PREFIX$fileName")

        companion object {
            const val IMPORTED_ID_PREFIX: String = "imported:"
            const val IMPORTED_DIR: String = "imported"
            const val GGUF_EXTENSION: String = ".gguf"

            private const val IMPORTED_ORIGIN = "Imported from this device"
            private const val THINK_MARKER = "think"
            private const val COPY_BUFFER_BYTES = 1 shl 16

            /** `models/<owner>/<repo>/metadata.json` is three levels; four leaves room for a shard set. */
            private const val MAX_SCAN_DEPTH = 4
        }
    }

/**
 * Local alias for `AppError.asException()`.
 *
 * Spelled out rather than imported because `asException` is also the name of
 * `:core-download`'s `DownloadError` extension, and both are on this file's
 * classpath.
 */
private fun AppError.asAppException(): AppErrorException = AppErrorException(this)
