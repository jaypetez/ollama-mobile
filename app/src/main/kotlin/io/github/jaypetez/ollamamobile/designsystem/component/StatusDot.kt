package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.designsystem.theme.statusPalette

/**
 * A coloured dot with a text alternative.
 *
 * [statusText] is mandatory rather than derived, and the dot itself is
 * `clearAndSetSemantics` so the description is what gets announced. Colour is
 * never the only channel: roughly one man in twelve cannot reliably separate
 * the green from the red, so every use of this sits next to a written status.
 */
@Composable
fun StatusDot(
    status: StatusKind,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    val palette = statusPalette()
    val color = when (status) {
        StatusKind.Online -> palette.online
        StatusKind.Offline -> palette.offline
        StatusKind.Warning -> palette.warning
        StatusKind.Unknown -> palette.unknown
    }
    Box(
        modifier = modifier
            .size(Sizes.StatusDotRing)
            .clearAndSetSemantics { contentDescription = statusText },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.StatusDot)
                .background(color = color, shape = CircleShape)
                .border(width = Sizes.DividerThickness, color = color.copy(alpha = RING_ALPHA), shape = CircleShape),
        )
    }
}

/** The dot and its label, which is how it should almost always be used. */
@Composable
fun StatusLabel(
    status: StatusKind,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        // One announcement, not two: the dot already carries the same text, and
        // TalkBack reading "reachable, reachable" is a bug people report.
        modifier = modifier.clearAndSetSemantics { contentDescription = statusText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        StatusDot(status = status, statusText = statusText)
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RING_ALPHA = 0.35f

@Preview
@Composable
private fun StatusLabelPreview() {
    OllamaPreviewTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            StatusLabel(status = StatusKind.Online, statusText = stringResource(R.string.server_status_reachable))
            StatusLabel(status = StatusKind.Offline, statusText = stringResource(R.string.server_status_unreachable))
            StatusLabel(status = StatusKind.Unknown, statusText = stringResource(R.string.server_status_unknown))
        }
    }
}
