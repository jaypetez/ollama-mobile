package io.github.jaypetez.ollamamobile.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme.
 *
 * Dynamic colour is honoured on Android 12+ because a user who set a wallpaper
 * palette asked for it, but it is a *setting* and not a compile-time choice:
 * some wallpapers produce a scheme with far less contrast than the tuned one,
 * and the settings screen can turn it off.
 */
@Composable
fun OllamaMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> {
            OllamaDarkColorScheme
        }

        else -> {
            OllamaLightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OllamaTypography,
        shapes = OllamaShapes,
        content = content,
    )
}

/**
 * Theme plus a background, for `@Preview`.
 *
 * Dynamic colour is forced off: a preview rendered against the IDE host's
 * wallpaper palette is not the thing being reviewed, and it changes underneath
 * you between machines.
 */
@Composable
fun OllamaPreviewTheme(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    OllamaMobileTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
