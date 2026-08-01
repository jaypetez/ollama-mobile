package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * The app's button.
 *
 * [loading] replaces the leading icon with a spinner and disables the button,
 * rather than the caller swapping in a different composable: a button that
 * changes size when it starts working moves everything under the user's thumb.
 */
@Composable
fun OllamaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OllamaButtonStyle = OllamaButtonStyle.Primary,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val active = enabled && !loading
    when (style) {
        OllamaButtonStyle.Primary -> Button(
            onClick = onClick,
            modifier = modifier.widthIn(min = Sizes.MinTouchTarget),
            enabled = active,
        ) {
            ButtonContent(text = text, icon = icon, iconDescription = iconContentDescription, loading = loading)
        }

        OllamaButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.widthIn(min = Sizes.MinTouchTarget),
            enabled = active,
        ) {
            ButtonContent(text = text, icon = icon, iconDescription = iconContentDescription, loading = loading)
        }

        OllamaButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = modifier.widthIn(min = Sizes.MinTouchTarget),
            enabled = active,
        ) {
            ButtonContent(text = text, icon = icon, iconDescription = iconContentDescription, loading = loading)
        }

        OllamaButtonStyle.Destructive -> TextButton(
            onClick = onClick,
            modifier = modifier.widthIn(min = Sizes.MinTouchTarget),
            enabled = active,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            ButtonContent(text = text, icon = icon, iconDescription = iconContentDescription, loading = loading)
        }
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, iconDescription: String?, loading: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(Sizes.InlineProgress),
                strokeWidth = Sizes.InlineProgressStroke,
                color = LocalContentColor.current,
            )

            icon != null -> Icon(
                imageVector = icon,
                // Null when the label already says it: a screen reader
                // announcing "save, save" is worse than announcing it once.
                contentDescription = iconDescription,
                modifier = Modifier.size(Sizes.ListIcon),
            )
        }
        Text(text = text)
    }
}

@Preview
@Composable
private fun OllamaButtonPreview() {
    OllamaPreviewTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OllamaButton(text = stringResource(R.string.action_save), onClick = {})
            OllamaButton(
                text = stringResource(R.string.action_cancel),
                onClick = {},
                style = OllamaButtonStyle.Secondary,
            )
            OllamaButton(
                text = stringResource(R.string.action_delete),
                onClick = {},
                style = OllamaButtonStyle.Destructive,
            )
        }
    }
}
