package io.github.jaypetez.ollamamobile.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.util.Date

/**
 * "3 minutes ago", in the device's language.
 *
 * [DateUtils] rather than a hand-rolled formatter and a plurals resource:
 * relative time is a localisation minefield (Polish has three plural forms,
 * Arabic six, and several languages inflect the unit) and the platform already
 * ships a correct implementation for every locale the device has.
 */
@Composable
fun relativeTime(epochMillis: Long): String = remember(epochMillis) {
    DateUtils
        .getRelativeTimeSpanString(
            epochMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
}

/** An absolute timestamp, for a log line where ordering and precision matter more than readability. */
@Composable
fun absoluteTime(epochMillis: Long): String = remember(epochMillis) { formatAbsoluteTime(epochMillis) }

/** As [absoluteTime], outside composition — for export text. */
fun formatAbsoluteTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))
