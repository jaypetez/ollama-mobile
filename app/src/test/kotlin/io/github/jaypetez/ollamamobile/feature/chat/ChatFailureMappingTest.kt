package io.github.jaypetez.ollamamobile.feature.chat

import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MemoryVerdict
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.SecretRef
import org.junit.Test

/**
 * The failure vocabulary the chat screen speaks.
 *
 * The three cases the product cares about — the server is unreachable, LAN-only
 * mode refused the request, the model is not loaded — must stay three distinct
 * states with three distinct sentences. Collapsing any pair of them is the
 * regression this file exists to catch.
 */
class ChatFailureMappingTest {
    @Test
    fun `unreachable, LAN-only and model-not-loaded are three different failures`() {
        val unreachable = AppError.Network.Unreachable().toChatFailureOrNull()
        val blocked = AppError.Policy.LanOnlyViolation(host = "example.com").toChatFailureOrNull()
        val notLoaded = AppError.Model.NotFound(ModelId("qwen3:1.7b")).toChatFailureOrNull()

        assertThat(unreachable).isEqualTo(ChatFailure.ServerUnreachable)
        assertThat(blocked).isEqualTo(ChatFailure.LanOnlyBlocked("example.com"))
        assertThat(notLoaded).isEqualTo(ChatFailure.ModelNotLoaded("qwen3:1.7b"))

        val titles = listOf(unreachable, blocked, notLoaded).map { it?.titleRes }
        assertThat(titles.toSet()).hasSize(3)
    }

    @Test
    fun `a timeout is not the same failure as an unreachable server`() {
        assertThat(AppError.Network.Timeout().toChatFailureOrNull()).isEqualTo(ChatFailure.ServerTimeout)
        assertThat(ChatFailure.ServerTimeout.titleRes).isNotEqualTo(ChatFailure.ServerUnreachable.titleRes)
    }

    @Test
    fun `HTTP status codes split into authentication, missing model and rejection`() {
        assertThat(AppError.Network.Http(UNAUTHORIZED).toChatFailureOrNull())
            .isEqualTo(ChatFailure.AuthenticationRequired(UNAUTHORIZED))
        assertThat(AppError.Network.Http(FORBIDDEN).toChatFailureOrNull())
            .isEqualTo(ChatFailure.AuthenticationRequired(FORBIDDEN))
        assertThat(AppError.Network.Http(NOT_FOUND).toChatFailureOrNull())
            .isEqualTo(ChatFailure.ModelNotLoaded(null))
        assertThat(AppError.Network.Http(SERVER_ERROR).toChatFailureOrNull())
            .isEqualTo(ChatFailure.ServerRejected(SERVER_ERROR))
    }

    @Test
    fun `policy failures keep their own identity`() {
        assertThat(AppError.Policy.OfflineMode().toChatFailureOrNull()).isEqualTo(ChatFailure.OfflineMode)
        assertThat(AppError.Policy.LocalNetworkPermissionDenied().toChatFailureOrNull())
            .isEqualTo(ChatFailure.LocalNetworkPermission)
    }

    @Test
    fun `an absent engine is reported as an engine problem, not a network one`() {
        assertThat(AppError.Engine.NotAvailable().toChatFailureOrNull())
            .isEqualTo(ChatFailure.OnDeviceUnavailable)
        val verdict = MemoryVerdict.Refuse(requiredBytes = 2L, availableBytes = 1L, reason = "no room")
        assertThat(AppError.Model.InsufficientMemory(verdict).toChatFailureOrNull())
            .isEqualTo(ChatFailure.OnDeviceUnavailable)
    }

    @Test
    fun `storage and TLS failures are distinguishable`() {
        assertThat(AppError.Storage.SecretUnavailable(SecretRef("alias")).toChatFailureOrNull())
            .isEqualTo(ChatFailure.StorageUnavailable)
        assertThat(AppError.Network.Tls(fingerprintSha256 = "ab:cd").toChatFailureOrNull())
            .isEqualTo(ChatFailure.CertificateUntrusted("ab:cd"))
    }

    /** Stop is not a fault. Reporting it as one apologises for obeying. */
    @Test
    fun `cancellation is not a failure at all`() {
        assertThat(AppError.Network.Cancelled().toChatFailureOrNull()).isNull()
    }

    @Test
    fun `statistics are null unless the server reported something`() {
        assertThat(MessageStatsUi.from(null)).isNull()
        assertThat(MessageStatsUi.from(GenerationStats.Empty)).isNull()

        val partial = MessageStatsUi.from(GenerationStats(completionTokens = 10))
        assertThat(partial).isNotNull()
        // Reported the count, reported no duration: no rate may be invented.
        assertThat(partial?.completionTokens).isEqualTo(10)
        assertThat(partial?.tokensPerSecond).isNull()
        assertThat(partial?.secondsToFirstToken).isNull()
        assertThat(partial?.totalSeconds).isNull()
    }

    @Test
    fun `time to first token needs the prompt evaluation the server measured`() {
        val loadOnly = MessageStatsUi.from(GenerationStats(loadNanos = ONE_SECOND_NANOS))
        assertThat(loadOnly?.secondsToFirstToken).isNull()

        val measured = MessageStatsUi.from(
            GenerationStats(loadNanos = ONE_SECOND_NANOS, promptEvalNanos = ONE_SECOND_NANOS),
        )
        assertThat(measured?.secondsToFirstToken).isEqualTo(2.0)
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 503
        const val ONE_SECOND_NANOS = 1_000_000_000L
    }
}
