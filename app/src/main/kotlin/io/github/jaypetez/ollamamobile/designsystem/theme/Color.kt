package io.github.jaypetez.ollamamobile.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The dark scheme is the one that was tuned; the light one is derived from it.
// A chat client is read for long stretches, often in bed, and the failure mode
// of a "dark" theme built by inverting a light one is a #000000 background with
// #FFFFFF text: maximum contrast, maximum eye strain, and no way to tell an
// elevated surface from the page behind it. So the background is a desaturated
// blue-black rather than true black (OLED smearing is real, but so is the
// halation of pure white on pure black), the on-surface colour stops short of
// white, and the five container steps are spaced far enough apart to be
// distinguishable at low screen brightness.

private val DarkPrimary = Color(0xFF7FD1C1)
private val DarkOnPrimary = Color(0xFF00382F)
private val DarkPrimaryContainer = Color(0xFF005046)
private val DarkOnPrimaryContainer = Color(0xFF9BEDDC)
private val DarkSecondary = Color(0xFFB0CCC5)
private val DarkOnSecondary = Color(0xFF1B3530)
private val DarkSecondaryContainer = Color(0xFF324B46)
private val DarkOnSecondaryContainer = Color(0xFFCCE8E1)
private val DarkTertiary = Color(0xFFAFC8E8)
private val DarkOnTertiary = Color(0xFF16324B)
private val DarkTertiaryContainer = Color(0xFF2E4863)
private val DarkOnTertiaryContainer = Color(0xFFCFE4FF)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkBackground = Color(0xFF0B0F14)
private val DarkOnBackground = Color(0xFFDDE3E6)
private val DarkSurface = Color(0xFF0B0F14)
private val DarkOnSurface = Color(0xFFDDE3E6)
private val DarkSurfaceVariant = Color(0xFF3B4A48)
private val DarkOnSurfaceVariant = Color(0xFFB9C6C3)
private val DarkOutline = Color(0xFF83918E)
private val DarkOutlineVariant = Color(0xFF3B4A48)
private val DarkInverseSurface = Color(0xFFDDE3E6)
private val DarkInverseOnSurface = Color(0xFF161B1F)
private val DarkInversePrimary = Color(0xFF00695C)
private val DarkSurfaceDim = Color(0xFF0B0F14)
private val DarkSurfaceBright = Color(0xFF313940)
private val DarkSurfaceContainerLowest = Color(0xFF06090C)
private val DarkSurfaceContainerLow = Color(0xFF12181E)
private val DarkSurfaceContainer = Color(0xFF161D24)
private val DarkSurfaceContainerHigh = Color(0xFF20282F)
private val DarkSurfaceContainerHighest = Color(0xFF2B333A)

private val LightPrimary = Color(0xFF00695C)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF9BEDDC)
private val LightOnPrimaryContainer = Color(0xFF00201B)
private val LightSecondary = Color(0xFF4A635E)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFCCE8E1)
private val LightOnSecondaryContainer = Color(0xFF06201C)
private val LightTertiary = Color(0xFF45617D)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFCFE4FF)
private val LightOnTertiaryContainer = Color(0xFF001D34)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightBackground = Color(0xFFFAFDFB)
private val LightOnBackground = Color(0xFF181C1B)
private val LightSurface = Color(0xFFFAFDFB)
private val LightOnSurface = Color(0xFF181C1B)
private val LightSurfaceVariant = Color(0xFFDAE5E1)
private val LightOnSurfaceVariant = Color(0xFF3F4947)
private val LightOutline = Color(0xFF6F7977)
private val LightOutlineVariant = Color(0xFFBFC9C6)
private val LightInverseSurface = Color(0xFF2D3131)
private val LightInverseOnSurface = Color(0xFFEFF1EF)
private val LightInversePrimary = Color(0xFF7FD1C1)
private val LightSurfaceDim = Color(0xFFDADDDA)
private val LightSurfaceBright = Color(0xFFFAFDFB)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF4F7F4)
private val LightSurfaceContainer = Color(0xFFEEF1EE)
private val LightSurfaceContainerHigh = Color(0xFFE8EBE9)
private val LightSurfaceContainerHighest = Color(0xFFE2E5E3)

/** The hand-tuned dark scheme. Used whenever dynamic colour is off or unavailable. */
val OllamaDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

/** The light fallback. */
val OllamaLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

/**
 * Semantic colours that Material's scheme has no slot for.
 *
 * "Reachable" is not `primary` and "degraded" is not `error`: a server that has
 * not answered in a while is not a failure the user has to act on, and painting
 * it the same red as a real error trains people to ignore red.
 */
data class StatusPalette(
    val online: Color,
    val offline: Color,
    val warning: Color,
    val unknown: Color,
)

private val DarkStatusPalette = StatusPalette(
    online = Color(0xFF6FD08C),
    offline = Color(0xFFFF8A80),
    warning = Color(0xFFFFC46B),
    unknown = Color(0xFF7E8B93),
)

private val LightStatusPalette = StatusPalette(
    online = Color(0xFF1E7D3C),
    offline = Color(0xFFB3261E),
    warning = Color(0xFF8A5A00),
    unknown = Color(0xFF6F7977),
)

/**
 * The status palette matching the current scheme.
 *
 * Darkness is read off the surface luminance rather than from a
 * `CompositionLocal` we would have to remember to provide, which matters
 * because dynamic colour can hand us a scheme neither of the two above.
 */
@Composable
@ReadOnlyComposable
fun statusPalette(): StatusPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < LUMINANCE_MIDPOINT) {
        DarkStatusPalette
    } else {
        LightStatusPalette
    }

private const val LUMINANCE_MIDPOINT = 0.5f
