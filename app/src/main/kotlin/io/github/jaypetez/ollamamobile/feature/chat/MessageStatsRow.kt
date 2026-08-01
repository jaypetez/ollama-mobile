package io.github.jaypetez.ollamamobile.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.jaypetez.ollamamobile.R

/**
 * The throughput line under a finished answer.
 *
 * Every figure here came off the wire. A null field draws nothing at all: the
 * Ollama response omits counters it did not measure, and rendering a missing
 * `eval_duration` as "0 tok/s" invents a measurement — which is worse than
 * silence, because it looks like a real number.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MessageStatsRow(
    stats: MessageStatsUi,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.chat_stats_label)
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        stats.completionTokens?.let { Figure(stringResource(R.string.chat_stats_completion_tokens, it)) }
        stats.tokensPerSecond?.let { Figure(stringResource(R.string.chat_stats_rate, it.toFloat())) }
        stats.secondsToFirstToken?.let { Figure(stringResource(R.string.chat_stats_first_token, it.toFloat())) }
        stats.promptTokens?.let { Figure(stringResource(R.string.chat_stats_prompt_tokens, it)) }
        stats.totalSeconds?.let { Figure(stringResource(R.string.chat_stats_total, it.toFloat())) }
    }
}

@Composable
private fun Figure(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
