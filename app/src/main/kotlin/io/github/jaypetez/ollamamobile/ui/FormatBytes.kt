package io.github.jaypetez.ollamamobile.ui

import java.util.Locale

private const val UNIT = 1024.0
private val SUFFIXES = listOf("B", "KiB", "MiB", "GiB", "TiB")

/**
 * A byte count as a short, readable string.
 *
 * IEC units (KiB, not kB) because that is what the numbers actually are: a
 * GGUF's on-disk size and the free space on the volume are both powers of two,
 * and rendering them in powers of ten makes a 4 GiB model look like it fits in
 * 4 GB of free space when it does not.
 *
 * The suffixes are not translated. They are unit symbols, and IEC symbols are
 * the same in every locale; the *number* is formatted for the locale.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    var value = bytes.toDouble()
    var index = 0
    while (value >= UNIT && index < SUFFIXES.lastIndex) {
        value /= UNIT
        index++
    }
    val pattern = if (index == 0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, value, SUFFIXES[index])
}
