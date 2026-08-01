package io.github.jaypetez.ollamamobile.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.ErrorBanner
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaSecretField
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaTextField
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing

/**
 * Add or edit one server.
 *
 * A bottom sheet rather than a screen because it is a five-field form the user
 * is in the middle of something else to reach; a sheet keeps the list they came
 * from on screen behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditSheet(
    state: ServerEditorUiState,
    actions: ServerEditorActions,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        ServerEditForm(state = state, actions = actions)
    }
}

/**
 * The form itself, without the sheet.
 *
 * Separated so a Compose test can drive the fields without a
 * `ModalBottomSheet`, whose window is a separate composition that Robolectric
 * makes awkward to reach.
 */
@Composable
fun ServerEditForm(
    state: ServerEditorUiState,
    actions: ServerEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Lg)
            // Five fields on a phone in landscape is taller than the visible
            // area above the keyboard; without this the token field is under it.
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Text(
            text = stringResource(
                if (state.isEditing) R.string.server_edit_title else R.string.server_add_title,
            ),
            style = MaterialTheme.typography.titleLarge,
        )

        if (state.saveErrorRes != null) {
            ErrorBanner(message = stringResource(state.saveErrorRes))
        }

        OllamaTextField(
            value = state.label,
            onValueChange = actions.onLabelChange,
            label = stringResource(R.string.server_field_label),
            placeholder = stringResource(R.string.server_field_label_placeholder),
            supportingText = stringResource(R.string.server_field_label_supporting),
            errorText = state.labelErrorRes?.let { stringResource(it) },
            enabled = !state.isSaving,
        )

        OllamaTextField(
            value = state.baseUrl,
            onValueChange = actions.onUrlChange,
            label = stringResource(R.string.server_field_base_url),
            placeholder = stringResource(R.string.server_field_base_url_placeholder),
            supportingText = state.normalisedBaseUrl?.let { url ->
                stringResource(R.string.server_field_base_url_normalised, url)
            } ?: stringResource(R.string.server_field_base_url_supporting),
            errorText = state.urlErrorRes?.let { stringResource(it) },
            enabled = !state.isSaving,
            monospace = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        AuthModeSelector(
            selected = state.authMode,
            onSelect = actions.onAuthModeChange,
            enabled = !state.isSaving,
        )

        when (state.authMode) {
            ServerAuthMode.None -> Text(
                text = stringResource(R.string.server_auth_none_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ServerAuthMode.BearerToken -> OllamaSecretField(
                value = state.secret,
                onValueChange = actions.onSecretChange,
                label = stringResource(R.string.server_field_token),
                supportingText = stringResource(
                    if (state.isEditing) {
                        R.string.server_field_secret_unchanged
                    } else {
                        R.string.server_field_token_supporting
                    },
                ),
                errorText = state.secretErrorRes?.let { stringResource(it) },
                enabled = !state.isSaving,
            )

            ServerAuthMode.BasicAuth -> BasicAuthFields(state = state, actions = actions)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.Xl),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            OllamaButton(
                text = stringResource(R.string.action_save),
                onClick = actions.onSave,
                loading = state.isSaving,
            )
            OllamaButton(
                text = stringResource(R.string.action_cancel),
                onClick = actions.onDismiss,
                style = OllamaButtonStyle.Text,
                enabled = !state.isSaving,
            )
        }
    }
}

@Composable
private fun BasicAuthFields(
    state: ServerEditorUiState,
    actions: ServerEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        OllamaTextField(
            value = state.username,
            onValueChange = actions.onUsernameChange,
            label = stringResource(R.string.server_field_username),
            errorText = state.secretErrorRes?.let { stringResource(it) },
            enabled = !state.isSaving,
        )
        OllamaSecretField(
            value = state.secret,
            onValueChange = actions.onSecretChange,
            label = stringResource(R.string.server_field_password),
            supportingText = stringResource(
                if (state.isEditing) {
                    R.string.server_field_secret_unchanged
                } else {
                    R.string.server_field_password_supporting
                },
            ),
            enabled = !state.isSaving,
        )
    }
}

@Composable
private fun AuthModeSelector(
    selected: ServerAuthMode,
    onSelect: (ServerAuthMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.server_field_auth_mode),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            ServerAuthMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(text = stringResource(mode.labelRes())) },
                    enabled = enabled,
                )
            }
        }
    }
}

private fun ServerAuthMode.labelRes(): Int = when (this) {
    ServerAuthMode.None -> R.string.server_auth_none
    ServerAuthMode.BearerToken -> R.string.server_auth_bearer
    ServerAuthMode.BasicAuth -> R.string.server_auth_basic
}

@Preview
@Composable
private fun ServerEditFormPreview() {
    OllamaPreviewTheme {
        ServerEditForm(
            state = ServerEditorUiState(
                label = "Living room Pi",
                baseUrl = "192.168.1.40",
                authMode = ServerAuthMode.BearerToken,
            ),
            actions = ServerEditorActions(
                onLabelChange = {},
                onUrlChange = {},
                onAuthModeChange = {},
                onUsernameChange = {},
                onSecretChange = {},
                onSave = {},
                onDismiss = {},
            ),
        )
    }
}
