package io.github.jaypetez.ollamamobile.feature.servers

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.common.result.AppResult
import io.github.jaypetez.ollamamobile.data.repository.ServerCredential
import io.github.jaypetez.ollamamobile.data.repository.ServerRepository
import io.github.jaypetez.ollamamobile.data.repository.ServerStatus
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.remote.discovery.DiscoveredServer
import io.github.jaypetez.ollamamobile.remote.discovery.DiscoveryEvent
import io.github.jaypetez.ollamamobile.remote.discovery.SubnetScanner
import io.github.jaypetez.ollamamobile.remote.discovery.SweepRefusal
import io.github.jaypetez.ollamamobile.testing.MainDispatcherRule
import io.github.jaypetez.ollamamobile.testing.awaitUntil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ServersViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pi = ServerRef(
        id = ServerId("pi"),
        label = "Living room Pi",
        baseUrl = "http://192.168.1.40:11434",
        auth = ServerAuth.BearerToken(SecretRef.forServer(ServerId("pi"), "token")),
    )

    private val statuses = MutableStateFlow(
        listOf(ServerStatus(server = pi, reachable = true, version = "0.12.3", lastCheckedAtMillis = 1L)),
    )

    private val repository = mockk<ServerRepository>(relaxed = true) {
        every { this@mockk.statuses } returns this@ServersViewModelTest.statuses
        coEvery { findServer(ServerId("pi")) } returns pi
        coEvery { addServer(any(), any(), any(), any()) } answers {
            AppResult.Success(
                ServerRef(id = ServerId.random(), label = firstArg(), baseUrl = secondArg()),
            )
        }
        coEvery { saveServer(any(), any()) } answers { AppResult.Success(firstArg()) }
    }

    private val scanner = mockk<SubnetScanner>(relaxed = true) {
        every { scan(any()) } returns flowOf(DiscoveryEvent.Finished(probed = 0, found = 0))
    }

    private fun viewModel() = ServersViewModel(repository, scanner)

    @Test
    fun `configured servers are exposed with their health`() = runTest {
        viewModel().uiState.test {
            val loaded = awaitUntil { !it.isLoading }
            assertThat(loaded.servers).hasSize(1)
            val row = loaded.servers.first()
            assertThat(row.label).isEqualTo("Living room Pi")
            assertThat(row.reachable).isTrue()
            assertThat(row.everChecked).isTrue()
            assertThat(row.authMode).isEqualTo(ServerAuthMode.BearerToken)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding refuses a blank name and an unusable address, and says which is which`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onAddServer()
            viewModel.onEditorUrlChange("ws://192.168.1.40")
            viewModel.onSaveServer()

            val editor = expectMostRecentItem().editor
            assertThat(editor).isNotNull()
            assertThat(editor?.labelErrorRes).isEqualTo(R.string.server_error_label_required)
            assertThat(editor?.urlErrorRes).isEqualTo(R.string.server_error_url_invalid)
            coVerify(exactly = 0) { repository.addServer(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a bare address is shown normalised before it is saved`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onAddServer()
            viewModel.onEditorUrlChange("192.168.1.55")
            // The scheme and Ollama's port are added for the user; the sheet has
            // to show that, or nobody believes the address they typed was kept.
            assertThat(expectMostRecentItem().editor?.normalisedBaseUrl)
                .isEqualTo("http://192.168.1.55:11434")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a valid add stores the token and closes the sheet`() = runTest {
        val credential = slot<ServerCredential>()
        coEvery { repository.addServer(any(), any(), capture(credential), any()) } answers {
            AppResult.Success(ServerRef(id = ServerId("new"), label = firstArg(), baseUrl = secondArg()))
        }
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onAddServer()
            viewModel.onEditorLabelChange("Study NUC")
            viewModel.onEditorUrlChange("192.168.1.55")
            viewModel.onEditorAuthModeChange(ServerAuthMode.BearerToken)
            viewModel.onEditorSecretChange("sk-secret")
            viewModel.onSaveServer()

            assertThat(expectMostRecentItem().editor).isNull()
            coVerify { repository.addServer("Study NUC", "192.168.1.55", any(), any()) }
            assertThat(credential.captured).isEqualTo(ServerCredential.BearerToken("sk-secret"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing never pre-fills the stored secret and an untouched field leaves it alone`() = runTest {
        val credential = slot<ServerCredential>()
        coEvery { repository.saveServer(any(), capture(credential)) } answers { AppResult.Success(firstArg()) }
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onEditServer("pi")
            val editor = expectMostRecentItem().editor
            assertThat(editor?.isEditing).isTrue()
            assertThat(editor?.label).isEqualTo("Living room Pi")
            assertThat(editor?.authMode).isEqualTo(ServerAuthMode.BearerToken)
            // The token is in the Keystore, not in Compose state.
            assertThat(editor?.secret).isEmpty()

            viewModel.onEditorLabelChange("Hallway Pi")
            viewModel.onSaveServer()

            assertThat(credential.captured).isEqualTo(ServerCredential.Unchanged)
            coVerify { repository.saveServer(match { it.label == "Hallway Pi" }, any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a discovered host is offered for adoption and marked when it is already configured`() = runTest {
        every { scanner.scan(any()) } returns flowOf(
            DiscoveryEvent.Started(candidateCount = 254),
            DiscoveryEvent.Found(
                DiscoveredServer(address = "192.168.1.40", port = 11434, version = "0.12.3", roundTripMillis = 9L),
            ),
            DiscoveryEvent.Found(
                DiscoveredServer(address = "192.168.1.77", port = 11434, version = "0.12.3", roundTripMillis = 11L),
            ),
            DiscoveryEvent.Finished(probed = 254, found = 2),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onStartScan()
            val scan = expectMostRecentItem().scan
            assertThat(scan.isScanning).isFalse()
            assertThat(scan.finished).isTrue()
            assertThat(scan.found).hasSize(2)
            assertThat(scan.found.single { it.address == "192.168.1.40" }.alreadyConfigured).isTrue()
            assertThat(scan.found.single { it.address == "192.168.1.77" }.alreadyConfigured).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a refused sweep reports why instead of pretending nothing was found`() = runTest {
        every { scanner.scan(any()) } returns flowOf(
            DiscoveryEvent.Refused(
                SweepRefusal.SubnetTooWide(prefixLength = 16, addressCount = 65_536, minimumPrefixLength = 22),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onStartScan()
            val scan = expectMostRecentItem().scan
            assertThat(scan.refusalRes).isEqualTo(R.string.discovery_refused_subnet_too_wide)
            assertThat(scan.refusalDetail).isEqualTo("/16")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adopting a discovered host opens the sheet pre-filled rather than saving silently`() = runTest {
        every { scanner.scan(any()) } returns flowOf(
            DiscoveryEvent.Started(candidateCount = 254),
            DiscoveryEvent.Found(
                DiscoveredServer(address = "192.168.1.77", port = 11434, version = "0.12.3", roundTripMillis = 11L),
            ),
            DiscoveryEvent.Finished(probed = 254, found = 1),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onStartScan()
            viewModel.onAdoptDiscovered("http://192.168.1.77:11434")

            val editor = expectMostRecentItem().editor
            assertThat(editor?.baseUrl).isEqualTo("http://192.168.1.77:11434")
            assertThat(editor?.isEditing).isFalse()
            coVerify(exactly = 0) { repository.addServer(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forgetting a server is confirmed first and then deletes`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitUntil { !it.isLoading }

            viewModel.onRequestDelete("pi")
            assertThat(expectMostRecentItem().deleteTarget?.label).isEqualTo("Living room Pi")
            coVerify(exactly = 0) { repository.deleteServer(any()) }

            viewModel.onConfirmDelete()
            coVerify(exactly = 1) { repository.deleteServer(ServerId("pi")) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
