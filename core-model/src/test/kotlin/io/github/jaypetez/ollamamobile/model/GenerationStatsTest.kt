package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GenerationStatsTest {
    @Test
    fun `computes token rate from nanosecond durations`() {
        // Real terminal chunk from an Ollama /api/chat response:
        // eval_count 298, eval_duration 4535599000 ns -> ~65.7 tok/s.
        // If the durations were read as milliseconds this would come out at
        // 65_700_000 tok/s, which is exactly the mistake being guarded here.
        val stats = GenerationStats(
            promptTokens = 26,
            completionTokens = 298,
            promptEvalNanos = 325_953_000L,
            evalNanos = 4_535_599_000L,
            loadNanos = 1_067_138_958L,
            totalNanos = 4_883_583_458L,
        )

        assertThat(stats.tokensPerSecond).isWithin(0.05).of(65.70)
        assertThat(stats.promptTokensPerSecond).isWithin(0.05).of(79.77)
    }

    @Test
    fun `returns null when the duration is absent`() {
        // omitempty: a cached prompt carries no prompt_eval_duration.
        val stats = GenerationStats(promptTokens = 26, completionTokens = 298, evalNanos = 1_000_000_000L)

        assertThat(stats.promptTokensPerSecond).isNull()
        assertThat(stats.tokensPerSecond).isEqualTo(298.0)
    }

    @Test
    fun `returns null instead of Infinity when the duration is zero`() {
        val stats = GenerationStats(completionTokens = 5, evalNanos = 0L)

        // Double division would give Infinity here, which reaches the UI as
        // "Infinity tok/s" rather than as a crash anyone notices.
        assertThat(stats.tokensPerSecond).isNull()
    }

    @Test
    fun `returns null for a negative duration`() {
        val stats = GenerationStats(completionTokens = 5, evalNanos = -1L)

        assertThat(stats.tokensPerSecond).isNull()
    }

    @Test
    fun `returns null when the token count is absent`() {
        val stats = GenerationStats(evalNanos = 1_000_000_000L, promptEvalNanos = 1_000_000_000L)

        assertThat(stats.tokensPerSecond).isNull()
        assertThat(stats.promptTokensPerSecond).isNull()
    }

    @Test
    fun `zero tokens over a real duration is zero, not null`() {
        // A response that produced nothing still took time; that is a fact and
        // is different from "the server did not tell us".
        val stats = GenerationStats(completionTokens = 0, evalNanos = 500_000_000L)

        assertThat(stats.tokensPerSecond).isEqualTo(0.0)
    }

    @Test
    fun `an entirely absent stats block computes nothing and equals Empty`() {
        val stats = GenerationStats()

        assertThat(stats).isEqualTo(GenerationStats.Empty)
        assertThat(stats.isEmpty).isTrue()
        assertThat(stats.tokensPerSecond).isNull()
        assertThat(stats.promptTokensPerSecond).isNull()
    }

    @Test
    fun `every field defaults to null so absent stays distinct from zero`() {
        val stats = GenerationStats.Empty

        assertThat(stats.promptTokens).isNull()
        assertThat(stats.completionTokens).isNull()
        assertThat(stats.promptEvalNanos).isNull()
        assertThat(stats.evalNanos).isNull()
        assertThat(stats.loadNanos).isNull()
        assertThat(stats.totalNanos).isNull()
        assertThat(GenerationStats(completionTokens = 0, evalNanos = 1L).isEmpty).isFalse()
    }
}
