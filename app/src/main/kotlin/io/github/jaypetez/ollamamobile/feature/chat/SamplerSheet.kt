package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.model.SamplingParams
import java.util.Locale

/**
 * Sampling settings for this thread.
 *
 * Every control has a *Default* position that is not a number. `SamplingParams`
 * documents that a null field means "whatever the server decides", and sending
 * an explicit `temperature = 0.0` because a slider had to start somewhere is a
 * materially different request. The reset button restores the null, and a
 * control the user has never touched stays null.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SamplerSheet(
    sampling: SamplingParams,
    onSave: (SamplingParams) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(sampling) { mutableStateOf(sampling) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_GUTTER),
            verticalArrangement = Arrangement.spacedBy(SHEET_GAP),
        ) {
            Text(
                text = stringResource(R.string.chat_sampler_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.chat_sampler_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SamplerSliders(draft = draft, onChange = { draft = it })
            SamplerFields(draft = draft, onChange = { draft = it })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SHEET_GUTTER),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onSave(draft) }) {
                    Text(text = stringResource(R.string.chat_sampler_save))
                }
            }
        }
    }
}

@Composable
private fun SamplerSliders(draft: SamplingParams, onChange: (SamplingParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        SamplerSlider(
            label = stringResource(R.string.chat_sampler_temperature),
            value = draft.temperature?.toFloat(),
            range = TEMPERATURE_MIN..TEMPERATURE_MAX,
            onChange = { onChange(draft.copy(temperature = it?.toDouble())) },
        )
        SamplerSlider(
            label = stringResource(R.string.chat_sampler_top_p),
            value = draft.topP?.toFloat(),
            range = PROBABILITY_MIN..PROBABILITY_MAX,
            onChange = { onChange(draft.copy(topP = it?.toDouble())) },
        )
        SamplerSlider(
            label = stringResource(R.string.chat_sampler_min_p),
            value = draft.minP?.toFloat(),
            range = PROBABILITY_MIN..PROBABILITY_MAX,
            onChange = { onChange(draft.copy(minP = it?.toDouble())) },
        )
        SamplerSlider(
            label = stringResource(R.string.chat_sampler_repeat_penalty),
            value = draft.repeatPenalty?.toFloat(),
            range = PENALTY_MIN..PENALTY_MAX,
            onChange = { onChange(draft.copy(repeatPenalty = it?.toDouble())) },
        )
        SamplerSlider(
            label = stringResource(R.string.chat_sampler_top_k),
            value = draft.topK?.toFloat(),
            range = TOP_K_MIN..TOP_K_MAX,
            decimals = 0,
            onChange = { onChange(draft.copy(topK = it?.toInt())) },
        )
    }
}

@Composable
private fun SamplerFields(draft: SamplingParams, onChange: (SamplingParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_GAP)) {
        OutlinedTextField(
            value = draft.seed?.toString().orEmpty(),
            onValueChange = { text -> onChange(draft.copy(seed = text.trim().toLongOrNull())) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.chat_sampler_seed)) },
            placeholder = { Text(text = stringResource(R.string.chat_sampler_seed_hint)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.stop.joinToString(separator = "\n"),
            onValueChange = { text ->
                onChange(draft.copy(stop = text.lines().map { it.trim() }.filter { it.isNotEmpty() }))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.chat_sampler_stop)) },
            placeholder = { Text(text = stringResource(R.string.chat_sampler_stop_hint)) },
            maxLines = STOP_MAX_LINES,
        )
    }
}

/**
 * One numeric setting, with an explicit way back to "unset".
 *
 * The slider needs a position even when the value is null, so it parks at the
 * bottom of the range — but the *label* says Default until the user moves it,
 * and nothing is sent until then.
 */
@Composable
private fun SamplerSlider(
    label: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value?.let { format(it, decimals) } ?: stringResource(R.string.chat_sampler_default),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onChange(null) }, enabled = value != null) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = stringResource(R.string.chat_sampler_reset, label),
                    modifier = Modifier.size(RESET_ICON),
                )
            }
        }
        Slider(
            value = value ?: range.start,
            onValueChange = { onChange(it) },
            valueRange = range,
        )
    }
}

private fun format(value: Float, decimals: Int): String =
    String.format(Locale.getDefault(), "%.${decimals}f", value)

private const val TEMPERATURE_MIN = 0f
private const val TEMPERATURE_MAX = 2f
private const val PROBABILITY_MIN = 0f
private const val PROBABILITY_MAX = 1f
private const val PENALTY_MIN = 0.5f
private const val PENALTY_MAX = 2f
private const val TOP_K_MIN = 0f
private const val TOP_K_MAX = 200f
private const val STOP_MAX_LINES = 4

private val RESET_ICON = 18.dp
