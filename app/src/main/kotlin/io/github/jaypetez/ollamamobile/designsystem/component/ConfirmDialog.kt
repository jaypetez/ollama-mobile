package io.github.jaypetez.ollamamobile.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme

/**
 * A yes/no gate in front of something that cannot be undone.
 *
 * [confirmLabel] is required and must name the action — "Delete", not "OK". A
 * dialog whose buttons read "OK" and "Cancel" forces the user to re-read the
 * body to work out which one destroys their data, and half of them will not.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            OllamaButton(
                text = confirmLabel,
                onClick = onConfirm,
                style = if (destructive) OllamaButtonStyle.Destructive else OllamaButtonStyle.Text,
            )
        },
        dismissButton = {
            OllamaButton(
                text = dismissLabel ?: stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = OllamaButtonStyle.Text,
            )
        },
    )
}

@Preview
@Composable
private fun ConfirmDialogPreview() {
    OllamaPreviewTheme {
        ConfirmDialog(
            title = stringResource(R.string.server_delete_title),
            message = stringResource(R.string.server_delete_message, "Living room Pi"),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}
