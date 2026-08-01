package io.github.jaypetez.ollamamobile.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRepository
import io.github.jaypetez.ollamamobile.download.CustomUrlSource
import io.github.jaypetez.ollamamobile.download.DownloadException
import io.github.jaypetez.ollamamobile.download.DownloadRepository
import io.github.jaypetez.ollamamobile.download.DownloadRequest
import io.github.jaypetez.ollamamobile.download.DownloadSource
import io.github.jaypetez.ollamamobile.download.DownloadStatus
import io.github.jaypetez.ollamamobile.download.catalog.CatalogEntry
import io.github.jaypetez.ollamamobile.download.catalog.ModelCatalogSource
import io.github.jaypetez.ollamamobile.download.hf.HfModelInfo
import io.github.jaypetez.ollamamobile.download.hf.HuggingFaceApi
import io.github.jaypetez.ollamamobile.model.AppErrorException
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.ui.formatBytes
import java.io.IOException
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Finding a model to download, from three sources that differ in what they can
 * promise.
 *
 * The **catalogue** is bundled in the APK, so it works on a first run with no
 * connectivity; its entries may still be unverified, which is a property of the
 * entry and is shown. **Search** is the live Hub, which knows sizes and hashes
 * but needs the network. A **pasted URL** has neither a hash nor any statement
 * about who is being downloaded from, and the screen says so rather than
 * presenting all three as equivalent.
 *
 * Progress is read back from `WorkManager` through [DownloadRepository], not
 * from an in-memory bus, so a screen reopened after the app was killed sees the
 * real state of a transfer that has been running for twenty minutes.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Three sources plus pause/resume/cancel; each is one user action.
class ModelDiscoverViewModel
    @Inject
    constructor(
        private val catalogSource: ModelCatalogSource,
        private val huggingFace: HuggingFaceApi,
        private val customUrls: CustomUrlSource,
        private val downloads: DownloadRepository,
        private val localModels: LocalModelRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ModelDiscoverUiState())

        val uiState: StateFlow<ModelDiscoverUiState> = _uiState.asStateFlow()

        /** One progress collector per model being watched, so a leave-and-return does not double up. */
        private val watchers = mutableMapOf<String, Job>()

        /** The requests this screen built, kept so pause and resume can re-enqueue them. */
        private val requests = mutableMapOf<String, DownloadRequest>()

        init {
            _uiState.update { it.copy(engineAvailable = localModels.engineAvailable) }
            viewModelScope.launch { loadCatalogue() }
        }

        fun selectTab(tab: DiscoverTab) {
            _uiState.update { it.copy(tab = tab) }
        }

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
        }

        fun search() {
            val query = _uiState.value.query.trim()
            if (query.isEmpty()) return
            viewModelScope.launch {
                _uiState.update { it.copy(searching = true, message = null) }
                val outcome = runCatching { huggingFace.searchGgufModels(query) }
                _uiState.update { state ->
                    outcome.fold(
                        onSuccess = { hits ->
                            state.copy(
                                searching = false,
                                searched = true,
                                results = hits
                                    .flatMap { info -> info.toEntries(installedIds()) }
                                    .toImmutableList(),
                            )
                        },
                        onFailure = { failure ->
                            state.copy(
                                searching = false,
                                searched = true,
                                results = persistentListOf(),
                                message = failure.toMessage(R.string.discover_error_search),
                            )
                        },
                    )
                }
            }
        }

        fun onCustomUrlChange(url: String) {
            _uiState.update { it.copy(customUrl = url, customUrlPreview = null) }
        }

        /**
         * Resolves a pasted URL into something downloadable before committing.
         *
         * A `HEAD` first, so the user learns that the link is an HTML error page
         * or does not support ranges *before* four gigabytes have moved.
         */
        fun inspectCustomUrl() {
            val url = _uiState.value.customUrl.trim()
            if (url.isEmpty()) return
            viewModelScope.launch {
                _uiState.update { it.copy(inspectingUrl = true, message = null) }
                val outcome = runCatching { customUrls.inspect(url) }
                _uiState.update { state ->
                    outcome.fold(
                        onSuccess = { model ->
                            val request = model.toRequest()
                            requests[request.modelId.value] = request
                            state.copy(
                                inspectingUrl = false,
                                customUrlPreview = DiscoverEntryUi(
                                    id = request.modelId.value,
                                    displayName = model.fileName,
                                    sourceLabel = model.host,
                                    fileName = model.fileName,
                                    sizeLabel = model.sizeBytes?.let(::formatBytes),
                                    quantizationLabel = Quantization.fromFileName(model.fileName)?.label,
                                    parameterLabel = null,
                                    installed = request.modelId.value in installedIds(),
                                    // A pasted URL never carries a SHA-256. Said
                                    // plainly rather than papered over.
                                    hashVerified = false,
                                ),
                            )
                        },
                        onFailure = { failure ->
                            state.copy(
                                inspectingUrl = false,
                                message = failure.toMessage(R.string.discover_error_url),
                            )
                        },
                    )
                }
            }
        }

        fun download(entry: DiscoverEntryUi) {
            viewModelScope.launch {
                val request = requests[entry.id] ?: buildRequest(entry)
                if (request == null) {
                    _uiState.update {
                        it.copy(
                            message = ModelsMessage(R.string.discover_error_prepare, isError = true),
                        )
                    }
                    return@launch
                }
                requests[entry.id] = request
                runCatching { downloads.enqueue(request) }
                    .onFailure { failure ->
                        _uiState.update { it.copy(message = failure.toMessage(R.string.discover_error_start)) }
                    }.onSuccess { watch(entry.id) }
            }
        }

        fun pause(entry: DiscoverEntryUi) {
            viewModelScope.launch { downloads.pause(ModelId(entry.id)) }
        }

        fun resume(entry: DiscoverEntryUi) {
            viewModelScope.launch {
                // The repository resumes from the request it wrote to disk, so
                // this works after a process death that lost `requests`.
                val resumed = downloads.resume(ModelId(entry.id))
                if (!resumed) download(entry) else watch(entry.id)
            }
        }

        fun cancel(entry: DiscoverEntryUi) {
            viewModelScope.launch {
                downloads.cancel(ModelId(entry.id))
                watchers.remove(entry.id)?.cancel()
                _uiState.update { it.copy(downloads = it.downloads - entry.id) }
            }
        }

        fun dismissMessage() {
            _uiState.update { it.copy(message = null) }
        }

        // ------------------------------------------------------------ internals

        private suspend fun loadCatalogue() {
            val installed = installedIds()
            val entries = runCatching { catalogSource.load().all }.getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(catalogue = entries.map { it.toEntry(installed) }.toImmutableList())
            }
            // Anything already in flight when the screen opens — a download the
            // user started, backgrounded the app on, and came back to.
            entries
                .map { it.modelId.value }
                .filter { downloads.isTracked(ModelId(it)) }
                .forEach { watch(it) }
        }

        private suspend fun installedIds(): Set<String> =
            localModels.models.value
                .map { it.id.value }
                .toSet()

        /**
         * Turns a catalogue or search entry into a complete unit of work.
         *
         * This is where a catalogue entry with a null size and a null hash gets
         * both, and where a request for one shard quietly becomes a request for
         * the whole set — see `HuggingFaceApi.downloadRequest`.
         */
        private suspend fun buildRequest(entry: DiscoverEntryUi): DownloadRequest? {
            val repo = entry.sourceLabel
            if (!repo.contains('/')) return null
            return runCatching {
                huggingFace.downloadRequest(
                    modelId = ModelId(entry.id),
                    displayName = entry.displayName,
                    source = DownloadSource.HuggingFace(repo = repo),
                    fileName = entry.fileName,
                )
            }.getOrNull()
        }

        private fun watch(id: String) {
            if (watchers[id]?.isActive == true) return
            watchers[id] = viewModelScope.launch {
                downloads.progress(ModelId(id)).collect { progress ->
                    _uiState.update { state ->
                        state.copy(downloads = state.downloads + (id to DownloadUi.from(progress)))
                    }
                    if (progress.status == DownloadStatus.COMPLETED) {
                        // The scan is what turns finished bytes into a loadable
                        // model, so it has to happen here rather than the next
                        // time the manager screen is opened.
                        localModels.refresh()
                        markInstalled(id)
                    }
                }
            }
        }

        private fun markInstalled(id: String) {
            _uiState.update { state ->
                state.copy(
                    catalogue = state.catalogue
                        .map { if (it.id == id) it.copy(installed = true) else it }
                        .toImmutableList(),
                    results = state.results
                        .map { if (it.id == id) it.copy(installed = true) else it }
                        .toImmutableList(),
                    customUrlPreview = state.customUrlPreview
                        ?.takeIf { it.id == id }
                        ?.copy(installed = true)
                        ?: state.customUrlPreview,
                )
            }
        }

        private fun CatalogEntry.toEntry(installed: Set<String>) = DiscoverEntryUi(
            id = modelId.value,
            displayName = displayName,
            sourceLabel = repo,
            fileName = file,
            sizeLabel = sizeBytes?.let(::formatBytes),
            quantizationLabel = quantizationEnum?.label,
            parameterLabel = formatParameters(parameterCount),
            installed = modelId.value in installed,
            // A catalogue entry without a recorded LFS oid is verified only
            // against its declared length and the GGUF magic.
            hashVerified = sha256 != null,
        )

        /**
         * One entry per GGUF in the repository, not one per repository.
         *
         * A repo that publishes eight quantisations of the same model is eight
         * different downloads with eight different sizes and eight different
         * memory verdicts, and collapsing them into one row would make the user
         * pick blind.
         */
        private fun HfModelInfo.toEntries(installed: Set<String>): List<DiscoverEntryUi> {
            val repo = id
            return ggufFileNames.map { file ->
                val entryId = "hf:$repo:$file"
                DiscoverEntryUi(
                    id = entryId,
                    displayName = file.removeSuffix(GGUF_SUFFIX),
                    sourceLabel = repo,
                    fileName = file,
                    // The search endpoint carries no per-file sizes; they are
                    // resolved from the tree API at download time. Showing
                    // nothing beats showing a number that came from nowhere.
                    sizeLabel = null,
                    quantizationLabel = Quantization.fromFileName(file)?.label,
                    parameterLabel = null,
                    installed = entryId in installed,
                    hashVerified = true,
                )
            }
        }

        /**
         * Maps a thrown failure onto a sentence.
         *
         * Only the three exception types this screen can actually produce are
         * named; anything else is the generic message rather than a leaked
         * developer string.
         */
        private fun Throwable.toMessage(fallbackRes: Int): ModelsMessage = when (this) {
            is DownloadException -> ModelsMessage(fallbackRes, detail = error.message, isError = true)
            is AppErrorException -> ModelsMessage(fallbackRes, detail = null, isError = true)
            is IOException -> ModelsMessage(R.string.discover_error_network, isError = true)
            else -> ModelsMessage(fallbackRes, isError = true)
        }

        private companion object {
            const val GGUF_SUFFIX = ".gguf"
        }
    }
