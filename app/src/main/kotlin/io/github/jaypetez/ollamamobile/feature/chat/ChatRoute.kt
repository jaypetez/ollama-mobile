package io.github.jaypetez.ollamamobile.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaypetez.ollamamobile.R
import io.github.jaypetez.ollamamobile.model.ConversationId
import timber.log.Timber

/**
 * The chat screen's entry point, and the only thing the navigation graph needs.
 *
 * [conversationId] is null for a thread that does not exist yet; it is created
 * on the first message, and [onNewConversation] reports the identity back
 * so the graph can replace the route without losing the transcript.
 */
@Composable
fun ChatRoute(
    conversationId: ConversationId?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    onNewConversation: (ConversationId) -> Unit = {},
) {
    val context = LocalContext.current
    // Resources rather than the context: a Configuration change invalidates a
    // LocalResources read, so a locale switch does not leave stale sentences
    // behind. `Context.getString` from composition would not.
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, conversationId) { viewModel.openConversation(conversationId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val sampling by viewModel.sampling.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()

    // Built once: a fresh instance per recomposition would make the message list
    // unskippable and undo the whole point of the streaming split.
    val frames = remember(viewModel) { StreamingFrames(viewModel.stream) }
    val chooserTitle = stringResource(R.string.chat_export_chooser)
    val copy = rememberCopyToClipboard(
        clipboardLabel = stringResource(R.string.app_name),
        viewModel = viewModel,
    )
    val actions = rememberChatActions(viewModel = viewModel, copy = copy, onBack = onBack)

    val created by rememberUpdatedState(onNewConversation)
    LaunchedEffect(viewModel, context, resources) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.Created -> created(event.conversationId)

                is ChatEvent.Notice -> snackbarHostState.showSnackbar(resources.getString(event.messageRes))

                is ChatEvent.Share -> share(context, chooserTitle, event) {
                    viewModel.showNotice(R.string.chat_export_failed)
                }
            }
        }
    }

    ChatScreen(
        state = state,
        frames = frames,
        targets = targets,
        sampling = sampling,
        systemPrompt = systemPrompt,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
private fun rememberChatActions(
    viewModel: ChatViewModel,
    copy: (String) -> Unit,
    onBack: () -> Unit,
): ChatScreenActions {
    val back by rememberUpdatedState(onBack)
    return remember(viewModel, copy) {
        ChatScreenActions(
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onRegenerate = viewModel::regenerate,
            onCopy = copy,
            onSelectModel = viewModel::selectModel,
            onSetSystemPrompt = viewModel::setSystemPrompt,
            onSetSampling = viewModel::setSampling,
            onExport = viewModel::export,
            onDismissFailure = viewModel::dismissFailure,
            onBack = { back() },
        )
    }
}

/**
 * Copies text, and says so only where the platform does not.
 *
 * Android 13 shows its own copy confirmation, so a snackbar on top of it is a
 * duplicate the user has to dismiss.
 */
@Composable
private fun rememberCopyToClipboard(clipboardLabel: String, viewModel: ChatViewModel): (String) -> Unit {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    return remember(clipboard, clipboardLabel, viewModel) {
        { text: String ->
            clipboard?.setPrimaryClip(ClipData.newPlainText(clipboardLabel, text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                viewModel.showNotice(R.string.chat_copied)
            }
        }
    }
}

/**
 * Hands an export to the system share sheet.
 *
 * Guarded on size: `EXTRA_TEXT` crosses a Binder transaction, and a long
 * transcript will exceed its limit and take the process down rather than fail
 * politely.
 */
private fun share(context: Context, chooserTitle: String, event: ChatEvent.Share, onFailure: () -> Unit) {
    if (event.text.length > MAX_SHARE_CHARS) {
        onFailure()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = event.mimeType
        putExtra(Intent.EXTRA_TEXT, event.text)
        putExtra(Intent.EXTRA_TITLE, event.fileName)
        putExtra(Intent.EXTRA_SUBJECT, event.fileName)
    }
    val chooser = Intent
        .createChooser(intent, chooserTitle)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (missing: ActivityNotFoundException) {
        Timber.w(missing, "No activity can receive the exported conversation")
        onFailure()
    }
}

/** Comfortably under the 1 MB Binder transaction budget, with room for the rest of the parcel. */
private const val MAX_SHARE_CHARS = 200_000
