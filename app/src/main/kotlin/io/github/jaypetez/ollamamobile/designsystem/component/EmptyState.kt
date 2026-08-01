package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * What a screen shows when it has nothing to show.
 *
 * [description] is required, not optional. "No conversations" tells a
 * first-time user that the app is working and nothing about what to do next,
 * which is the entire job of this component; the empty state is the onboarding
 * for every screen the onboarding flow did not cover.
 *
 * The action is a slot rather than a label-plus-callback so that a screen can
 * offer two next steps where it genuinely has two.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Xl, vertical = Spacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the title immediately below carries the meaning, and
            // announcing the glyph as well just makes the list slower to skim.
            contentDescription = null,
            modifier = Modifier.size(Sizes.EmptyStateIcon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = Sizes.MaxContentWidth),
        )
        action?.invoke()
    }
}

@Preview
@Composable
private fun EmptyStatePreview() {
    OllamaPreviewTheme {
        EmptyState(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = stringResource(R.string.conversations_empty_title),
            description = stringResource(R.string.conversations_empty_description),
        )
    }
}
