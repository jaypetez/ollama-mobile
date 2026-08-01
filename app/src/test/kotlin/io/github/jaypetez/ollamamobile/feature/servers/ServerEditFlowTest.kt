package io.github.jaypetez.ollamamobile.feature.servers

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.designsystem.theme.OllamaPreviewTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The add/edit form, driven the way a user drives it.
 *
 * Robolectric rather than an instrumentation test: none of this needs a device,
 * and a form-validation regression should fail in seconds on a laptop rather
 * than in ten minutes on an emulator in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerEditFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var label: String? = null
    private var url: String? = null
    private var saved = false
    private var dismissed = false
    private var mode: ServerAuthMode? = null

    private val actions = ServerEditorActions(
        onLabelChange = { label = it },
        onUrlChange = { url = it },
        onAuthModeChange = { mode = it },
        onUsernameChange = {},
        onSecretChange = {},
        onSave = { saved = true },
        onDismiss = { dismissed = true },
    )

    @Test
    fun `an empty form is titled for adding and writes what is typed back up`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(state = ServerEditorUiState(), actions = actions)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.server_add_title)).assertIsDisplayed()

        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Living room Pi")
        assertThat(label).isEqualTo("Living room Pi")
    }

    @Test
    fun `validation errors are rendered as sentences, not just a red outline`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(
                    state = ServerEditorUiState(
                        labelErrorRes = R.string.server_error_label_required,
                        urlErrorRes = R.string.server_error_url_invalid,
                    ),
                    actions = actions,
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.server_error_label_required))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.server_error_url_invalid))
            .assertIsDisplayed()
    }

    @Test
    fun `the normalised address is shown under the field before saving`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(
                    state = ServerEditorUiState(label = "Pi", baseUrl = "192.168.1.40"),
                    actions = actions,
                )
            }
        }

        composeRule
            .onNodeWithText(
                context.getString(R.string.server_field_base_url_normalised, "http://192.168.1.40:11434"),
            ).assertIsDisplayed()
    }

    @Test
    fun `saving and cancelling report up, and both are disabled mid-save`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(state = ServerEditorUiState(label = "Pi", baseUrl = "1.2.3.4"), actions = actions)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
        assertThat(saved).isTrue()

        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun `an in-flight save cannot be cancelled into an inconsistent state`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(
                    state = ServerEditorUiState(label = "Pi", baseUrl = "1.2.3.4", isSaving = true),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsNotEnabled()
    }

    @Test
    fun `editing tells the user an empty token box keeps the stored credential`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(
                    state = ServerEditorUiState(
                        serverId = "pi",
                        label = "Pi",
                        baseUrl = "1.2.3.4",
                        authMode = ServerAuthMode.BearerToken,
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.server_edit_title)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.server_field_secret_unchanged))
            .assertIsDisplayed()
    }

    @Test
    fun `choosing an authentication mode reports it up`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServerEditForm(state = ServerEditorUiState(), actions = actions)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.server_auth_bearer)).performClick()
        assertThat(mode).isEqualTo(ServerAuthMode.BearerToken)
    }

    /**
     * The VPN limitation is a product requirement, not a nicety: a subnet sweep
     * structurally cannot see a Tailscale peer, and a user whose only server is
     * one needs to be told before they wait for a scan that will find nothing.
     */
    @Test
    // A tall window so the whole list composes: a LazyColumn only composes what
    // fits, and the caveat sits below the empty state on a phone-sized screen.
    @Config(sdk = [34], qualifiers = "w411dp-h2400dp")
    fun `the servers screen states that discovery cannot find VPN peers`() {
        composeRule.setContent {
            OllamaPreviewTheme {
                ServersScreen(
                    state = ServersUiState(isLoading = false, servers = persistentListOf()),
                    actions = noOpServersActions(),
                    scanActions = ScanActions(onStart = {}, onStop = {}, onAdopt = {}),
                    editorActions = actions,
                )
            }
        }

        composeRule.onNode(hasText("Tailscale", substring = true)).assertIsDisplayed()
    }

    private fun noOpServersActions() = ServersActions(
        onAdd = {},
        onEdit = {},
        onOpenDetail = {},
        onToggleEnabled = { _, _ -> },
        onRequestDelete = {},
        onConfirmDelete = {},
        onCancelDelete = {},
        onProbe = {},
        onDismissMessage = {},
    )
}
