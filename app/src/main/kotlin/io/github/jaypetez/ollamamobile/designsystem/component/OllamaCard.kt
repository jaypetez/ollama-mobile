package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * A container for one logical thing: a server, a conversation, a setting group.
 *
 * [clickLabel] exists because a clickable card is a button as far as TalkBack
 * is concerned, and without a label it is announced as "double tap to activate"
 * with no statement of what activating it does.
 *
 * The descendants are merged into one semantics node deliberately: a card with
 * a title, a URL and three status chips is *one* thing to a screen-reader user
 * swiping through a list, not five stops.
 */
@Composable
fun OllamaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
    }
    Card(
        modifier = modifier
            .semantics(mergeDescendants = true) { }
            .then(interaction)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(Spacing.Lg), content = content)
    }
}

@Preview
@Composable
private fun OllamaCardPreview() {
    OllamaPreviewTheme {
        OllamaCard {
            Text(text = stringResource(R.string.servers_title), style = MaterialTheme.typography.titleMedium)
        }
    }
}
