package io.github.jaypetez.ollamamobile.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.data.repository.ThemeMode
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButton
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaButtonStyle
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaCard
import io.github.jaypetez.ollamamobile.designsystem.component.OllamaTextField
import io.github.jaypetez.ollamamobile.designsystem.component.SectionHeader
import io.github.jaypetez.ollamamobile.designsystem.theme.MonospaceTextStyle
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import io.github.jaypetez.ollamamobile.designsystem.theme.Sizes
import io.github.jaypetez.ollamamobile.designsystem.theme.Spacing
import io.github.jaypetez.ollamamobile.llm.RoutingPolicy
import io.github.jaypetez.ollamamobile.model.NetworkPolicy

@Composable
fun SettingsRoute(
    onOpenDeveloperTools: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenDeveloperTools, onOpenModels) {
        SettingsActions(
            onNetworkPolicy = viewModel::onNetworkPolicyChange,
            onRoutingPolicy = viewModel::onRoutingPolicyChange,
            onThemeMode = viewModel::onThemeModeChange,
            onDynamicColor = viewModel::onDynamicColorChange,
            onShowReasoning = viewModel::onShowReasoningChange,
            onRequestReasoning = viewModel::onRequestReasoningChange,
            onSamplingChange = viewModel::onSamplingChange,
            onShowLicences = viewModel::onShowLicences,
            onHideLicences = viewModel::onHideLicences,
            onOpenDeveloperTools = onOpenDeveloperTools,
            onOpenModels = onOpenModels,
        )
    }
    SettingsScreen(state = state, actions = actions, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text(text = stringResource(R.string.settings_title)) }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, bottom = Spacing.Huge),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                item { NetworkPolicySection(selected = state.networkPolicy, onSelect = actions.onNetworkPolicy) }
                item {
                    RoutingPolicySection(
                        selected = state.routingPolicy,
                        localAvailable = state.localInferenceAvailable,
                        onSelect = actions.onRoutingPolicy,
                    )
                }
                item { OnDeviceSection(localAvailable = state.localInferenceAvailable, actions = actions) }
                item { SamplingSection(sampling = state.sampling, onChange = actions.onSamplingChange) }
                item { AppearanceSection(state = state, actions = actions) }
                item { ReasoningSection(state = state, actions = actions) }
                item { AboutSection(state = state, actions = actions) }
            }
        }

        val licences = state.licences
        if (licences != null) {
            LicenceDialog(text = licences, onDismiss = actions.onHideLicences)
        }
    }
}

/**
 * The three network policies, each described by what it *blocks*.
 *
 * "LAN only" tells the user nothing; "blocks anything outside your local
 * network, including model downloads from Hugging Face" tells them whether the
 * thing they are about to try will work.
 */
@Composable
private fun NetworkPolicySection(
    selected: NetworkPolicy,
    onSelect: (NetworkPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_network_title),
            description = stringResource(R.string.settings_network_description),
        )
        OllamaCard {
            Column(modifier = Modifier.selectableGroup()) {
                NetworkPolicy.entries.forEach { policy ->
                    ChoiceRow(
                        selected = selected == policy,
                        titleRes = policy.titleRes(),
                        descriptionRes = policy.descriptionRes(),
                        onSelect = { onSelect(policy) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingPolicySection(
    selected: RoutingPolicy,
    localAvailable: Boolean,
    onSelect: (RoutingPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_routing_title),
            description = stringResource(R.string.settings_routing_description),
        )
        OllamaCard {
            if (!localAvailable) {
                Text(
                    text = stringResource(R.string.settings_routing_no_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.Sm),
                )
            }
            Column(modifier = Modifier.selectableGroup()) {
                RoutingPolicy.entries.forEach { policy ->
                    ChoiceRow(
                        selected = selected == policy,
                        titleRes = policy.titleRes(),
                        descriptionRes = policy.descriptionRes(),
                        onSelect = { onSelect(policy) },
                    )
                }
            }
        }
    }
}

/**
 * The way into the model manager, and the one place the build's honest state is
 * stated on the settings screen.
 *
 * The entry is offered even with no engine: the manager is where the user finds
 * out *why* nothing local is on offer, and hiding it would leave them looking
 * for a screen that does not exist.
 */
@Composable
private fun OnDeviceSection(
    localAvailable: Boolean,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_on_device_title))
        OllamaCard {
            Text(
                text = if (localAvailable) {
                    stringResource(R.string.settings_on_device_available)
                } else {
                    stringResource(R.string.settings_on_device_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OllamaButton(
                text = stringResource(R.string.settings_on_device_manage),
                onClick = actions.onOpenModels,
                style = OllamaButtonStyle.Secondary,
                modifier = Modifier.padding(top = Spacing.Sm),
            )
        }
    }
}

@Composable
private fun SamplingSection(
    sampling: SamplingUiState,
    onChange: (SamplingField, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.settings_sampling_title),
            description = stringResource(R.string.settings_sampling_description),
        )
        OllamaCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                SamplingBox(
                    value = sampling.temperature,
                    field = SamplingField.Temperature,
                    labelRes = R.string.settings_sampling_temperature,
                    decimal = true,
                    onChange = onChange,
                )
                SamplingBox(
                    value = sampling.topP,
                    field = SamplingField.TopP,
                    labelRes = R.string.settings_sampling_top_p,
                    decimal = true,
                    onChange = onChange,
                )
                SamplingBox(
                    value = sampling.topK,
                    field = SamplingField.TopK,
                    labelRes = R.string.settings_sampling_top_k,
                    decimal = false,
                    onChange = onChange,
                )
                SamplingBox(
                    value = sampling.numPredict,
                    field = SamplingField.NumPredict,
                    labelRes = R.string.settings_sampling_num_predict,
                    decimal = false,
                    onChange = onChange,
                )
                SamplingBox(
                    value = sampling.numCtx,
                    field = SamplingField.NumCtx,
                    labelRes = R.string.settings_sampling_num_ctx,
                    decimal = false,
                    onChange = onChange,
                )
            }
        }
    }
}

@Composable
private fun SamplingBox(
    value: String,
    field: SamplingField,
    @StringRes labelRes: Int,
    decimal: Boolean,
    onChange: (SamplingField, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OllamaTextField(
        value = value,
        onValueChange = { text -> onChange(field, text) },
        label = stringResource(labelRes),
        modifier = modifier,
        supportingText = stringResource(R.string.settings_sampling_unset_hint),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
    )
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_appearance_title))
        OllamaCard {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    ChoiceRow(
                        selected = state.themeMode == mode,
                        titleRes = mode.titleRes(),
                        descriptionRes = null,
                        onSelect = { actions.onThemeMode(mode) },
                    )
                }
            }
            ToggleRow(
                checked = state.dynamicColor,
                titleRes = R.string.settings_dynamic_colour,
                descriptionRes = R.string.settings_dynamic_colour_description,
                onCheckedChange = actions.onDynamicColor,
            )
        }
    }
}

@Composable
private fun ReasoningSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_reasoning_title))
        OllamaCard {
            ToggleRow(
                checked = state.requestReasoning,
                titleRes = R.string.settings_request_reasoning,
                descriptionRes = R.string.settings_request_reasoning_description,
                onCheckedChange = actions.onRequestReasoning,
            )
            ToggleRow(
                checked = state.showReasoning,
                titleRes = R.string.settings_show_reasoning,
                descriptionRes = R.string.settings_show_reasoning_description,
                onCheckedChange = actions.onShowReasoning,
            )
        }
    }
}

@Composable
private fun AboutSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_about_title))
        OllamaCard {
            Text(
                text = stringResource(R.string.settings_about_version, state.versionName, state.versionCode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Xs),
            )
            if (state.licencesFailed) {
                Text(
                    text = stringResource(R.string.settings_licences_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                OllamaButton(
                    text = stringResource(R.string.settings_licences),
                    onClick = actions.onShowLicences,
                    style = OllamaButtonStyle.Text,
                )
                OllamaButton(
                    text = stringResource(R.string.settings_developer_tools),
                    onClick = actions.onOpenDeveloperTools,
                    style = OllamaButtonStyle.Text,
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = Spacing.Sm)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            if (descriptionRes != null) {
                Text(
                    text = stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LicenceDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_licences)) },
        text = {
            Text(
                text = text,
                style = MonospaceTextStyle,
                modifier = Modifier
                    .heightIn(max = Sizes.SheetMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            OllamaButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                style = OllamaButtonStyle.Text,
            )
        },
    )
}

@StringRes
private fun NetworkPolicy.titleRes(): Int = when (this) {
    NetworkPolicy.OPEN -> R.string.network_policy_open
    NetworkPolicy.LAN_ONLY -> R.string.network_policy_lan_only
    NetworkPolicy.OFFLINE -> R.string.network_policy_offline
}

@StringRes
private fun NetworkPolicy.descriptionRes(): Int = when (this) {
    NetworkPolicy.OPEN -> R.string.network_policy_open_description
    NetworkPolicy.LAN_ONLY -> R.string.network_policy_lan_only_description
    NetworkPolicy.OFFLINE -> R.string.network_policy_offline_description
}

@StringRes
private fun RoutingPolicy.titleRes(): Int = when (this) {
    RoutingPolicy.AUTO -> R.string.routing_policy_auto
    RoutingPolicy.PREFER_REMOTE -> R.string.routing_policy_prefer_remote
    RoutingPolicy.PREFER_LOCAL -> R.string.routing_policy_prefer_local
    RoutingPolicy.REMOTE_ONLY -> R.string.routing_policy_remote_only
    RoutingPolicy.LOCAL_ONLY -> R.string.routing_policy_local_only
}

@StringRes
private fun RoutingPolicy.descriptionRes(): Int = when (this) {
    RoutingPolicy.AUTO -> R.string.routing_policy_auto_description
    RoutingPolicy.PREFER_REMOTE -> R.string.routing_policy_prefer_remote_description
    RoutingPolicy.PREFER_LOCAL -> R.string.routing_policy_prefer_local_description
    RoutingPolicy.REMOTE_ONLY -> R.string.routing_policy_remote_only_description
    RoutingPolicy.LOCAL_ONLY -> R.string.routing_policy_local_only_description
}

@StringRes
private fun ThemeMode.titleRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    OllamaPreviewTheme {
        SettingsScreen(
            state = SettingsUiState(isLoading = false, versionName = "0.1.0", versionCode = 100),
            actions = SettingsActions(
                onNetworkPolicy = {},
                onRoutingPolicy = {},
                onThemeMode = {},
                onDynamicColor = {},
                onShowReasoning = {},
                onRequestReasoning = {},
                onSamplingChange = { _, _ -> },
                onShowLicences = {},
                onHideLicences = {},
                onOpenDeveloperTools = {},
                onOpenModels = {},
            ),
        )
    }
}
