package io.github.jaypetez.ollamamobile.data.engine

/**
 * Ollama's `keep_alive` grammar, as this app's idle-unload policy.
 *
 * The semantics are copied deliberately rather than invented, because the
 * setting is already in `AppSettings.keepAlive`, is already sent to remote
 * servers, and a user who typed `10m` there has every right to expect the same
 * thing to happen on the device. Ollama's rules:
 *
 *  * a bare number is **seconds** (`300` is five minutes),
 *  * a suffixed duration is `s`, `m` or `h` (`10m`, `1h`),
 *  * **zero unloads as soon as the generation finishes**,
 *  * **any negative value keeps the model resident indefinitely**,
 *  * anything unparseable falls back to the default rather than failing — a
 *    typo in a text box must not make inference stop working.
 */
object KeepAlive {
    /** What a fresh install uses. Ollama's own default is five minutes. */
    const val DEFAULT_MILLIS: Long = 5L * 60L * 1_000L

    /** [parse] returns this for a negative duration: never unload on a timer. */
    const val INDEFINITE: Long = Long.MAX_VALUE

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    /**
     * @return milliseconds of idleness before an unload, [INDEFINITE] for
     *   "never", or [DEFAULT_MILLIS] when [text] is absent or unparseable.
     */
    fun parse(text: String?): Long {
        val trimmed = text?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_MILLIS

        val multiplier = when (trimmed.last()) {
            's' -> MILLIS_PER_SECOND
            'm' -> MILLIS_PER_SECOND * SECONDS_PER_MINUTE
            'h' -> MILLIS_PER_SECOND * SECONDS_PER_HOUR
            else -> null
        }
        val number = if (multiplier == null) trimmed else trimmed.dropLast(1)
        val value = number.toLongOrNull() ?: return DEFAULT_MILLIS

        if (value < 0) return INDEFINITE
        // A bare number is seconds, which is the one part of the grammar that
        // is easy to read as milliseconds and be wrong by a factor of a
        // thousand in the direction of never unloading.
        return value * (multiplier ?: MILLIS_PER_SECOND)
    }
}
