package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * An inline, non-blocking failure notice.
 *
 * Marked as a polite live region so TalkBack announces it when it appears — an
 * error that only exists visually is not an error the user was told about.
 *
 * [message] must be a resolved, user-facing sentence. Never pass
 * `AppError.message`: `:core-model` documents that field as developer-facing,
 * so the caller maps the error *type* to a string resource.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.Md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.ListIcon),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                        )
                    }
                }
            }
            if (onRetry != null && retryLabel != null) {
                OllamaButton(
                    text = retryLabel,
                    onClick = onRetry,
                    style = OllamaButtonStyle.Text,
                )
            }
        }
    }
}

@Preview
@Composable
private fun ErrorBannerPreview() {
    OllamaPreviewTheme {
        ErrorBanner(
            message = stringResource(R.string.error_network_unreachable),
            onDismiss = {},
            onRetry = {},
            retryLabel = stringResource(R.string.action_retry),
        )
    }
}
