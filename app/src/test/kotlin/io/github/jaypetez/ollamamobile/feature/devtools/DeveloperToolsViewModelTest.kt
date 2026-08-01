package io.github.jaypetez.ollamamobile.feature.devtools

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.common.inspector.ApiExchange
import io.github.jaypetez.ollamamobile.common.inspector.ApiInspector
import io.github.jaypetez.ollamamobile.common.inspector.InspectedHeader
import io.github.jaypetez.ollamamobile.common.log.LogLevel
import io.github.jaypetez.ollamamobile.common.log.LogRecord
import io.github.jaypetez.ollamamobile.common.log.LogRing
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DeveloperToolsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val inspector = ApiInspector().apply { enabled = true }
    private val logRing = LogRing()

    private fun viewModel() = DeveloperToolsViewModel(inspector, logRing)

    @Test
    fun `a captured exchange is exposed with its headers already redacted`() = runTest {
        inspector.record(
            ApiExchange(
                id = 1L,
                startedAtMillis = 0L,
                method = "POST",
                url = "http://192.168.1.40:11434/api/chat",
                requestHeaders = listOf(ApiInspector.redact("Authorization", "Bearer sk-secret-value")),
                requestBodyPreview = """{"model":"qwen3","api_key":"sk-body-secret"}""",
                statusCode = 200,
            ),
        )

        viewModel().uiState.test {
            val state = awaitItem().takeIf { it.exchanges.isNotEmpty() } ?: awaitItem()
            val exchange = state.exchanges.single()
            assertThat(exchange.requestHeaders.single()).doesNotContain("sk-secret-value")
            assertThat(exchange.requestBody).doesNotContain("sk-body-secret")
            assertThat(exchange.curl).doesNotContain("sk-secret-value")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a token that leaked into a log line is scrubbed before it is rendered`() = runTest {
        logRing.add(
            LogRecord(
                timestampMillis = 1L,
                level = LogLevel.DEBUG,
                tag = "OllamaClient",
                message = "sending Authorization: Bearer sk-leaked-token",
            ),
        )

        viewModel().uiState.test {
            val state = awaitItem().takeIf { it.logs.isNotEmpty() } ?: awaitItem()
            assertThat(state.logs.single().message).doesNotContain("sk-leaked-token")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the shared export carries no credential either`() = runTest {
        logRing.add(
            LogRecord(
                timestampMillis = 1L,
                level = LogLevel.WARN,
                tag = "Auth",
                message = "token=sk-export-secret",
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            viewModel.onSelectTab(DeveloperToolsTab.Logs)
            viewModel.onShare()
            val export = expectMostRecentItem().pendingExport
            assertThat(export).isNotNull()
            assertThat(export).doesNotContain("sk-export-secret")

            viewModel.onExportConsume()
            assertThat(expectMostRecentItem().pendingExport).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
