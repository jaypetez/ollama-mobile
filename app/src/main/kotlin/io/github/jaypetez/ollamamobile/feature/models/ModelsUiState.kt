package io.github.jaypetez.ollamamobile.feature.models

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.data.repository.LocalModelRecord
import io.github.jaypetez.ollamamobile.download.DownloadProgress
import io.github.jaypetez.ollamamobile.download.DownloadStatus
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.ui.formatBytes
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The model manager's state.
 *
 * [engineAvailable] is the field everything else is read through, and it is
 * separate from `models.isEmpty()` on purpose. On the default
 * `-Pollama.nativeSource=none` build there is no inference engine at all, and an
 * empty list rendered on its own reads as "you have not downloaded anything
 * yet" — which sends the user to download a multi-gigabyte file that this build
 * can never load. The screen must say which of the two it is.
 */
@Immutable
data class ModelsUiState(
    val isLoading: Boolean = true,
    /** False for a build with no native engine. See the class KDoc. */
    val engineAvailable: Boolean = false,
    val models: ImmutableList<LocalModelUi> = persistentListOf(),
    /** The model currently held in memory, if any. */
    val residentModelId: ModelId? = null,
    /** A load or unload is in flight for this model; its row shows a spinner. */
    val busyModelId: ModelId? = null,
    /** Non-null while the delete confirmation is up. */
    val pendingDelete: LocalModelUi? = null,
    val message: ModelsMessage? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && models.isEmpty()
}

/** One installed model, already reduced to strings the row can draw. */
@Immutable
data class LocalModelUi(
    val id: ModelId,
    val displayName: String,
    val fileName: String,
    val sizeLabel: String,
    /** `Q4_K_M`, or null when neither the header nor the filename said. */
    val quantizationLabel: String?,
    /** `1.7B`, or null when the converter did not write a parameter count. */
    val parameterLabel: String?,
    /** The trained context length, not the configured one. */
    val contextLabel: String?,
    val originLabel: String,
    val verdict: MemoryVerdictUi,
    val resident: Boolean,
) {
    /**
     * Whether Load may be offered at all.
     *
     * A refused verdict is not a warning to click through. The load would fail,
     * or would succeed and get the process killed part-way through the first
     * answer, so the button is disabled and the reason is on the row.
     */
    val loadable: Boolean
        get() = verdict.kind != VerdictKind.REFUSE
}

/** The memory estimate, as the row shows it. */
@Immutable
data class MemoryVerdictUi(
    val kind: VerdictKind,
    /** Already unit-formatted and safe to display; comes from `MemoryVerdict.explain()`. */
    val explanation: String,
) {
    companion object {
        fun from(verdict: MemoryVerdict): MemoryVerdictUi = MemoryVerdictUi(
            kind = when (verdict) {
                is MemoryVerdict.Fits -> VerdictKind.FITS
                is MemoryVerdict.Tight -> VerdictKind.TIGHT
                is MemoryVerdict.Refuse -> VerdictKind.REFUSE
            },
            explanation = verdict.explain(),
        )
    }
}

/** Three cases, not a boolean: see `MemoryVerdict`. */
enum class VerdictKind(
    @param:StringRes val labelRes: Int,
) {
    FITS(R.string.models_verdict_fits),

    /** Loadable, but the user should be told why it is close. */
    TIGHT(R.string.models_verdict_tight),

    /** Not loadable on this device. Visibly refused, never silently offered. */
    REFUSE(R.string.models_verdict_refuse),
}

/** A sentence the screen shows once, in a banner or a snackbar. */
@Immutable
data class ModelsMessage(
    @param:StringRes val messageRes: Int,
    /** A safe detail — a filename, a shortfall. Never a path outside the app. */
    val detail: String? = null,
    val isError: Boolean = false,
)

/** Everything the model manager can be asked to do. */
@Immutable
data class ModelsActions(
    val onRefresh: () -> Unit,
    val onLoad: (ModelId) -> Unit,
    val onUnload: () -> Unit,
    val onDeleteRequested: (ModelId) -> Unit,
    val onDeleteConfirmed: () -> Unit,
    val onDeleteDismissed: () -> Unit,
    val onImportPicked: (android.net.Uri, String) -> Unit,
    val onDismissMessage: () -> Unit,
    val onDiscover: () -> Unit,
    val onBack: () -> Unit,
)

// ---------------------------------------------------------------------------
// Discover / download
// ---------------------------------------------------------------------------

/** Which source of downloadable models the discover screen is showing. */
enum class DiscoverTab(
    @param:StringRes val labelRes: Int,
) {
    /** The catalogue bundled in the APK. Works offline, on a first run. */
    CATALOGUE(R.string.discover_tab_catalogue),

    /** A live Hugging Face search. */
    SEARCH(R.string.discover_tab_search),

    /** A URL the user pasted. No hash, no provenance; the screen says so. */
    URL(R.string.discover_tab_url),
}

@Immutable
data class ModelDiscoverUiState(
    val engineAvailable: Boolean = false,
    val tab: DiscoverTab = DiscoverTab.CATALOGUE,
    val catalogue: ImmutableList<DiscoverEntryUi> = persistentListOf(),
    val query: String = "",
    val searching: Boolean = false,
    val results: ImmutableList<DiscoverEntryUi> = persistentListOf(),
    /** Distinguishes "no results" from "you have not searched yet". */
    val searched: Boolean = false,
    val customUrl: String = "",
    val inspectingUrl: Boolean = false,
    val customUrlPreview: DiscoverEntryUi? = null,
    /** Live progress, keyed by model id. Only holds entries with a transfer. */
    val downloads: Map<String, DownloadUi> = emptyMap(),
    val message: ModelsMessage? = null,
) {
    fun downloadFor(entry: DiscoverEntryUi): DownloadUi? = downloads[entry.id]
}

/** One downloadable model, from any of the three sources. */
@Immutable
data class DiscoverEntryUi(
    /** The [ModelId] value the download will be filed under. */
    val id: String,
    val displayName: String,
    /** `owner/repo`, a hostname for a pasted URL. */
    val sourceLabel: String,
    val fileName: String,
    val sizeLabel: String?,
    val quantizationLabel: String?,
    val parameterLabel: String?,
    /** Already on disk. The button says "Installed" and does nothing. */
    val installed: Boolean = false,
    /**
     * False when the source supplies no SHA-256.
     *
     * A pasted URL never does, and neither does an unverified catalogue entry.
     * Shown rather than hidden: it is a real reduction in guarantee, and the
     * download is still allowed.
     */
    val hashVerified: Boolean = true,
)

/** A transfer in flight, as the row draws it. */
@Immutable
data class DownloadUi(
    val status: DownloadStatus,
    /** Null means indeterminate — the honest bar before the total size is known. */
    val fraction: Float?,
    val bytesLabel: String,
    val restartedFromZero: Boolean,
) {
    val canPause: Boolean
        get() = status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED

    val canResume: Boolean
        get() = status == DownloadStatus.PAUSED || status == DownloadStatus.FAILED

    val canCancel: Boolean
        get() = status != DownloadStatus.COMPLETED && status != DownloadStatus.CANCELLED

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED

    companion object {
        fun from(progress: DownloadProgress): DownloadUi = DownloadUi(
            status = progress.status,
            fraction = progress.fraction,
            bytesLabel = progress.totalBytes
                ?.let { "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(it)}" }
                ?: formatBytes(progress.bytesDownloaded),
            restartedFromZero = progress.restartedFromZero,
        )
    }
}

/** What the discover screen can be asked to do. */
@Immutable
data class ModelDiscoverActions(
    val onTab: (DiscoverTab) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSearch: () -> Unit,
    val onCustomUrlChange: (String) -> Unit,
    val onInspectUrl: () -> Unit,
    val onDownload: (DiscoverEntryUi) -> Unit,
    val onPause: (DiscoverEntryUi) -> Unit,
    val onResume: (DiscoverEntryUi) -> Unit,
    val onCancel: (DiscoverEntryUi) -> Unit,
    val onDismissMessage: () -> Unit,
    val onBack: () -> Unit,
)

// ---------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------

private const val BILLION = 1_000_000_000.0
private const val MILLION = 1_000_000.0
private const val THOUSAND_TOKENS = 1024

/**
 * `1.7B`, `340M`, or null.
 *
 * Null rather than "unknown": plenty of third-party GGUFs omit
 * `general.parameter_count`, and a row that reads "unknown parameters" is
 * noise, whereas a row with no parameter chip simply does not claim one.
 */
fun formatParameters(count: Long?): String? = when {
    count == null || count <= 0 -> null
    count >= BILLION -> String.format(Locale.getDefault(), "%.1fB", count / BILLION)
    count >= MILLION -> String.format(Locale.getDefault(), "%.0fM", count / MILLION)
    else -> count.toString()
}

/** `32K`, `4096`, or null. The trained context, not the configured one. */
fun formatContext(tokens: Int?): String? = when {
    tokens == null || tokens <= 0 -> null
    tokens % THOUSAND_TOKENS == 0 -> "${tokens / THOUSAND_TOKENS}K"
    else -> tokens.toString()
}

fun LocalModelRecord.toUi(residentId: ModelId?): LocalModelUi = LocalModelUi(
    id = id,
    displayName = ref.displayName,
    fileName = ref.name,
    sizeLabel = formatBytes(sizeBytes),
    quantizationLabel = quantization?.label,
    parameterLabel = formatParameters(parameterCount),
    contextLabel = formatContext(ref.contextLength),
    originLabel = origin,
    verdict = MemoryVerdictUi.from(verdict),
    resident = id == residentId,
)
