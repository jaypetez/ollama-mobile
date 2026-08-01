package io.github.jaypetez.ollamamobile.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.asException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import timber.log.Timber

/** One file of an installed model, as recorded in `metadata.json`. */
@Serializable
public data class InstalledFile(
    public val fileName: String,
    public val sizeBytes: Long,
    /** The LFS SHA-256 this file was verified against, when the source supplied one. */
    public val sha256: String? = null,
)

/**
 * `metadata.json`: what the app knows about a model it has on disk.
 *
 * It is also the **completion marker**. A sharded set is several files and
 * therefore several renames, which cannot be made atomic as a group; writing
 * this file last, after every rename has landed, gives the set a single atomic
 * commit point. A model directory without a parsable `metadata.json` listing
 * every file at its recorded length is treated as incomplete and is not offered
 * to the loader.
 */
@Serializable
public data class InstalledModelMetadata(
    public val schemaVersion: Int = SCHEMA_VERSION,
    public val modelId: String,
    public val displayName: String,
    /** Human-readable provenance, shown before and after download. */
    public val origin: String,
    public val repo: String? = null,
    /** The commit SHA the bytes came from, so the same bytes can be fetched again. */
    public val revision: String? = null,
    public val sourceUrl: String? = null,
    public val files: List<InstalledFile>,
    /** The shard the loader is pointed at, or the only file. */
    public val primaryFileName: String,
    public val downloadedAtEpochMillis: Long,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * The on-disk layout for model files.
 *
 * ## Internal storage, deliberately
 *
 * Everything here lives under `context.filesDir` — never
 * `getExternalFilesDir()`, never the shared Downloads directory. Since Android
 * 11 the emulated external volume is backed by FUSE, and while ordinary
 * read/write got a kernel bypass, `mmap` did not: a page fault on a FUSE-backed
 * mapping round-trips to a userspace daemon. llama.cpp maps the entire model
 * and every token touches every weight, so the faults are continuous rather
 * than occasional. Since mmap is how the model is loaded, putting weights on
 * FUSE trades the app's core performance for capacity. (This has not been
 * measured on a device — there is no arm64 device on this project. The
 * mechanism is the argument.)
 *
 * ## Two roots, not one
 *
 * ```
 * filesDir/models/<storage-dir>/<file>.gguf     complete, verified, loadable
 * filesDir/models/<storage-dir>/metadata.json   the completion marker
 * filesDir/downloads/<storage-dir>/<file>.part  in flight
 * filesDir/downloads/<storage-dir>/<file>.resume  the resume validator sidecar
 * ```
 *
 * In-flight bytes live outside `models/` so the model scanner can treat
 * everything under `models/` as complete without inspecting it. An interrupted
 * download must never be discoverable as a usable model.
 */
@Singleton
public class ModelStorage
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        public val modelsRoot: File get() = File(context.filesDir, MODELS_DIR)

        public val downloadsRoot: File get() = File(context.filesDir, DOWNLOADS_DIR)

        public fun modelDir(storageDir: String): File = File(modelsRoot, safeRelativePath(storageDir))

        public fun downloadDir(storageDir: String): File = File(downloadsRoot, safeRelativePath(storageDir))

        public fun installedFile(storageDir: String, fileName: String): File =
            File(modelDir(storageDir), safeFileName(fileName))

        public fun partFile(storageDir: String, fileName: String): File =
            File(downloadDir(storageDir), safeFileName(fileName) + PART_SUFFIX)

        public fun metadataFile(storageDir: String): File = File(modelDir(storageDir), METADATA_FILE)

        /**
         * Publishes a finished set.
         *
         * The order is the whole point and is not negotiable: **fsync, then
         * verify, then rename, then write the marker.** Renaming first would
         * make a half-flushed file discoverable as a model; writing the marker
         * first would do the same for a set whose later shards never landed.
         *
         * @param reverifyFromDisk re-reads and re-hashes every file. Off by
         *   default: [ModelTransfer] already hashed the bytes as they were
         *   written, which is what proves the *network* delivered them intact,
         *   and re-reading a multi-gigabyte file to prove the *disk* stored them
         *   intact doubles the wall-clock cost of a download. Turn it on for a
         *   deliberate integrity audit, not for the normal path.
         */
        @Suppress("ThrowsCount") // Each throw is a distinct, separately-actionable failure.
        public fun publish(
            request: DownloadRequest,
            digests: Map<String, String>,
            reverifyFromDisk: Boolean = false,
            nowMillis: Long = System.currentTimeMillis(),
        ): InstalledModelMetadata {
            val storageDir = request.source.storageDir
            val target = modelDir(storageDir)
            if (!target.exists() && !target.mkdirs()) {
                throw AppError.Storage.Io(message = "Could not create ${target.path}.").asException()
            }

            val installed = request.files.map { file ->
                val part = partFile(storageDir, file.fileName)
                if (!part.isFile) {
                    throw DownloadError
                        .IncompleteShardSet(missing = listOf(file.fileName))
                        .asException()
                }
                fsync(part)
                requireGgufMagic(part, file.fileName)
                if (reverifyFromDisk) {
                    val actual = sha256Of(part)
                    val expected = file.sha256 ?: digests[file.fileName]
                    if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                        throw DownloadError
                            .IntegrityMismatch(
                                fileName = file.fileName,
                                expectedSha256 = expected,
                                actualSha256 = actual,
                            ).asException()
                    }
                }
                InstalledFile(
                    fileName = file.fileName,
                    sizeBytes = part.length(),
                    sha256 = file.sha256 ?: digests[file.fileName],
                )
            }

            request.files.forEach { file ->
                val part = partFile(storageDir, file.fileName)
                val destination = installedFile(storageDir, file.fileName)
                // Both paths are under filesDir, so this is a same-filesystem
                // rename: atomic, and it never copies the bytes.
                if (!part.renameTo(destination)) {
                    throw AppError.Storage
                        .Io(message = "Could not move ${part.name} into ${destination.parent}.")
                        .asException()
                }
            }

            val metadata = InstalledModelMetadata(
                modelId = request.modelId.value,
                displayName = request.displayName,
                origin = request.source.originLabel,
                repo = (request.source as? DownloadSource.HuggingFace)?.repo,
                revision = (request.source as? DownloadSource.HuggingFace)?.revision,
                sourceUrl = (request.source as? DownloadSource.CustomUrl)?.url,
                files = installed,
                primaryFileName = request.primaryFileName,
                downloadedAtEpochMillis = nowMillis,
            )
            writeMarker(storageDir, metadata)
            clearDownloadDir(storageDir)
            return metadata
        }

        /** The marker, or null when the directory holds no complete model. */
        public fun installedMetadata(storageDir: String): InstalledModelMetadata? {
            val file = metadataFile(storageDir)
            if (!file.isFile) return null
            val metadata = try {
                DownloadJson.decodeFromString(InstalledModelMetadata.serializer(), file.readText())
            } catch (e: SerializationException) {
                Timber.w(e, "Unreadable metadata.json in %s; treating the model as incomplete.", storageDir)
                return null
            } catch (e: IOException) {
                Timber.w(e, "Could not read metadata.json in %s.", storageDir)
                return null
            }
            // The marker is only as good as the files it claims. A truncated or
            // manually deleted shard has to read as "not installed", not as a
            // model that fails inside ggml.
            val intact = metadata.files.all { entry ->
                val candidate = installedFile(storageDir, entry.fileName)
                candidate.isFile && candidate.length() == entry.sizeBytes
            }
            return metadata.takeIf { intact }
        }

        public fun isInstalled(storageDir: String): Boolean = installedMetadata(storageDir) != null

        /** The absolute path the loader should be handed, or null when nothing complete is there. */
        public fun primaryFilePath(storageDir: String): String? =
            installedMetadata(storageDir)?.let { installedFile(storageDir, it.primaryFileName).absolutePath }

        /** Recursive delete of one model directory, marker included. */
        public fun delete(storageDir: String): Boolean {
            clearDownloadDir(storageDir)
            return modelDir(storageDir).deleteRecursively()
        }

        public fun clearDownloadDir(storageDir: String) {
            downloadDir(storageDir).deleteRecursively()
        }

        /** Total bytes a model occupies, marker included. */
        public fun sizeOnDisk(storageDir: String): Long = modelDir(storageDir)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        public companion object {
            public const val MODELS_DIR: String = "models"
            public const val DOWNLOADS_DIR: String = "downloads"
            public const val METADATA_FILE: String = "metadata.json"
            public const val PART_SUFFIX: String = ".part"

            /** "GGUF" in ASCII, the first four bytes of every GGUF file. */
            private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

            private const val COPY_BUFFER_BYTES = 1 shl 16

            /**
             * Rejects anything that could escape the models root.
             *
             * Repository ids and pasted URLs are attacker-influenced strings that
             * end up as path segments, so `..`, absolute paths and Windows drive
             * separators are refused rather than sanitised into something else —
             * a silent rewrite makes two different repos share a directory.
             */
            internal fun safeRelativePath(value: String): String {
                val segments = value.trim().split('/', '\\').filter { it.isNotEmpty() }
                require(segments.isNotEmpty()) { "A storage directory cannot be empty." }
                segments.forEach { segment ->
                    require(segment != "." && segment != "..") { "Illegal path segment '$segment' in '$value'." }
                    require(!segment.contains(':')) { "Illegal path segment '$segment' in '$value'." }
                }
                return segments.joinToString("/")
            }

            internal fun safeFileName(value: String): String {
                val name = value.trim()
                require(name.isNotEmpty()) { "A file name cannot be empty." }
                require(!name.contains('/') && !name.contains('\\')) { "'$value' is not a plain file name." }
                require(name != "." && name != "..") { "'$value' is not a plain file name." }
                return name
            }
        }

        /**
         * Forces the bytes out of the page cache before the rename.
         *
         * Without this, a power loss between the rename and the kernel's own
         * writeback leaves a correctly-named file with a hole in it — which is
         * exactly the state `models/` is supposed to be incapable of holding.
         *
         * The containing directory is deliberately not fsynced: the JDK has no
         * portable way to open a directory for `fsync`, and the marker file
         * makes a lost rename read as "incomplete" rather than as corruption.
         */
        private fun fsync(file: File) {
            try {
                FileOutputStream(file, true).use { it.fd.sync() }
            } catch (e: IOException) {
                throw AppError.Storage.Io(message = "Could not flush ${file.name} to disk.", cause = e).asException()
            }
        }

        private fun requireGgufMagic(file: File, fileName: String) {
            if (!fileName.endsWith(GGUF_EXTENSION, ignoreCase = true)) return
            val head = ByteArray(GGUF_MAGIC.size)
            val read = try {
                RandomAccessFile(file, "r").use { handle ->
                    handle.seek(0)
                    handle.read(head)
                }
            } catch (e: IOException) {
                throw AppError.Storage.Io(message = "Could not read ${file.name}.", cause = e).asException()
            }
            if (read != head.size || !head.contentEquals(GGUF_MAGIC)) {
                // Costs four bytes and catches the case that otherwise wastes a
                // full download: a CDN or captive portal answering 200 with an
                // HTML error page.
                throw DownloadError.NotAGguf(fileName = fileName).asException()
            }
        }

        private fun writeMarker(storageDir: String, metadata: InstalledModelMetadata) {
            val file = metadataFile(storageDir)
            val text = DownloadJson.encodeToString(InstalledModelMetadata.serializer(), metadata)
            try {
                FileOutputStream(file).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            } catch (e: IOException) {
                throw AppError.Storage.Io(message = "Could not write ${file.path}.", cause = e).asException()
            }
        }

        private fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHexString()
        }
    }

internal const val GGUF_EXTENSION: String = ".gguf"

/** Lower-case hex, which is how the Hub writes an LFS `oid`. */
internal fun ByteArray.toHexString(): String = buildString(size * HEX_CHARS_PER_BYTE) {
    this@toHexString.forEach { byte ->
        val value = byte.toInt() and BYTE_MASK
        append(HEX_DIGITS[value ushr NIBBLE_BITS])
        append(HEX_DIGITS[value and NIBBLE_MASK])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_CHARS_PER_BYTE = 2
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
