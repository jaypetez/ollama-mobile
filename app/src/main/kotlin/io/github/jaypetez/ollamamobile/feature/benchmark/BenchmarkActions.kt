package io.github.jaypetez.ollamamobile.feature.benchmark

import androidx.compose.runtime.Immutable

/**
 * Callbacks the benchmark screen needs.
 *
 * Grouped into one type rather than passed as five parameters so a preview or a
 * Robolectric test can supply no-ops in one place, and so adding a control does
 * not change the signature of every composable between here and the button.
 */
@Immutable
public data class BenchmarkActions(
    val onBack: () -> Unit,
    val onSelectModel: (String) -> Unit,
    val onToggleSweep: (BenchmarkSweep) -> Unit,
    val onRun: () -> Unit,
    val onCancel: () -> Unit,
)
