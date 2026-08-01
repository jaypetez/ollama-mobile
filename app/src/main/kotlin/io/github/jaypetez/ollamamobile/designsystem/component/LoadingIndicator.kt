package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * Work in progress.
 *
 * Pass [progress] only when the total is genuinely known. A determinate bar
 * driven by a guess is worse than a spinner: it stalls at 90% and the user
 * concludes the app has hung.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null,
    progress: Float? = null,
) {
    val description = label ?: stringResource(R.string.loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.Lg)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun LoadingIndicatorPreview() {
    OllamaPreviewTheme {
        LoadingIndicator(label = stringResource(R.string.loading))
    }
}
