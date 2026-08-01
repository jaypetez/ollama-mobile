package io.github.jaypetez.ollamamobile.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The embedded-server card, ready to drop into the settings screen.
 *
 * Split out of `SettingsScreen` deliberately: this is the one control in the
 * app that opens a listening socket, and it needs room to say what that means
 * without competing with theme and routing preferences for space.
 */
@Composable
fun ServerSettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ServerSettingsSection(
        state = state,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        modifier = modifier,
    )
}

@Composable
fun ServerSettingsSection(
    state: ServerSettingsUiState,
    onStart: (Boolean) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held in the composition rather than in the ViewModel: it is a choice
    // about the network the phone is on right now, and it must not survive as a
    // remembered preference that silently re-applies somewhere else.
    var lanExposure by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.server_settings_title),
            description = stringResource(R.string.server_settings_description),
        )
        OllamaCard {
            Column(
                modifier = Modifier.padding(Spacing.Lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Text(text = state.statusText(), style = MaterialTheme.typography.bodyLarge)
                if (state.isRunning) {
                    Text(
                        text = stringResource(R.string.server_settings_requests, state.requestCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                LanExposureRow(
                    checked = lanExposure,
                    enabled = !state.isRunning && !state.isBusy,
                    onCheckedChange = { lanExposure = it },
                )

                state.token?.let { token -> TokenRow(token) }

                Text(
                    text = stringResource(R.string.server_settings_example_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = state.exampleCommand, style = MonospaceTextStyle)

                Text(
                    text = stringResource(R.string.server_settings_never_on_boot),
                    style = MaterialTheme.typography.bodySmall,
                )

                OllamaButton(
                    text = if (state.isRunning) {
                        stringResource(R.string.server_settings_stop)
                    } else {
                        stringResource(R.string.server_settings_start)
                    },
                    onClick = { if (state.isRunning) onStop() else onStart(lanExposure) },
                    style = if (state.isRunning) OllamaButtonStyle.Secondary else OllamaButtonStyle.Primary,
                    loading = state.isBusy,
                )
            }
        }
    }
}

@Composable
private fun LanExposureRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.server_settings_lan_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.server_settings_lan_description),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TokenRow(token: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Android surfaces this label in the copy confirmation, so it is
    // user-facing text and belongs in strings.xml like any other.
    val clipLabel = stringResource(R.string.server_settings_token_label)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text = stringResource(R.string.server_settings_token_label),
            style = MaterialTheme.typography.labelLarge,
        )
        // The token itself is not announced: a screen reader reading 43 random
        // characters aloud is both useless and a disclosure in a shared room.
        // The copy button is the accessible affordance.
        Text(
            text = token,
            style = MonospaceTextStyle,
            modifier = Modifier.clearAndSetSemantics { },
        )
        OllamaButton(
            text = stringResource(R.string.server_settings_copy_token),
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, token)))
                }
            },
            style = OllamaButtonStyle.Text,
        )
    }
}

@Composable
private fun ServerSettingsUiState.statusText(): String = when {
    isBusy -> stringResource(R.string.server_settings_starting)
    isRunning -> stringResource(R.string.server_settings_running, address)
    else -> stringResource(R.string.server_settings_stopped)
}

@Preview
@Composable
private fun ServerSettingsSectionPreview() {
    OllamaPreviewTheme {
        ServerSettingsSection(
            state = ServerSettingsUiState(
                isRunning = true,
                address = "192.168.1.40:11434",
                lanExposed = true,
                token = "Zm9vYmFyLXRva2VuLWV4YW1wbGUtdmFsdWUtaGVyZQ",
                requestCount = 12,
            ),
            onStart = {},
            onStop = {},
        )
    }
}
