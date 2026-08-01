package io.github.jaypetez.ollamamobile.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// No bundled font file. The system font is already tuned for the device's
// screen and language, ships zero bytes in the APK, and — the part that
// actually matters for a chat client — is the one the user has scaled with the
// accessibility font-size setting. A packaged display face would look sharper
// in a screenshot and worse for everyone reading a long answer.

private val ReadingLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private val Default = Typography()

/**
 * Body styles are slightly looser than Material's defaults.
 *
 * Model output is long-form prose, and the default 1.43 ratio on `bodyLarge` is
 * tight for a paragraph read at arm's length on a phone.
 */
val OllamaTypography = Typography(
    displayLarge = Default.displayLarge,
    displayMedium = Default.displayMedium,
    displaySmall = Default.displaySmall,
    headlineLarge = Default.headlineLarge,
    headlineMedium = Default.headlineMedium,
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Default.titleSmall,
    bodyLarge = Default.bodyLarge.copy(
        lineHeight = 26.sp,
        lineHeightStyle = ReadingLineHeight,
    ),
    bodyMedium = Default.bodyMedium.copy(
        lineHeight = 22.sp,
        lineHeightStyle = ReadingLineHeight,
    ),
    bodySmall = Default.bodySmall,
    labelLarge = Default.labelLarge,
    labelMedium = Default.labelMedium,
    labelSmall = Default.labelSmall,
)

/**
 * The style for anything that must not be re-wrapped or misread: a base URL, a
 * model tag, a certificate fingerprint, a log line.
 *
 * Monospace is a correctness decision here rather than an aesthetic one —
 * `l`/`1` and `0`/`O` in a hostname are the difference between a server that
 * connects and a bug report.
 */
val MonospaceTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)
