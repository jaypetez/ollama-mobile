package io.github.jaypetez.ollamamobile.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.Role
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rendering property the whole screen is built around.
 *
 * A finalised bubble must not be re-evaluated because another message is
 * streaming. Asserted with a counter rather than by eye, because the symptom —
 * a long conversation degrading to single-digit frames per second as the answer
 * grows — does not show up in a screenshot or in a short manual test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatStreamRecompositionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = MessageActions(onCopy = {}, onCopyCode = {}, onRegenerate = {})

    private val finalised = List(MESSAGE_COUNT) { index ->
        ChatMessageUi(
            id = MessageId("m$index"),
            role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
            text = "Finalised turn $index",
            reasoning = null,
            stats = null,
            outcome = MessageOutcome.COMPLETE,
            isLastAssistant = false,
        )
    }.toImmutableList()

    @Test
    fun `a finalised message does not recompose while a new one streams`() {
        val frames = MutableStateFlow<StreamFrame?>(StreamFrame("", "", StreamPhase.WAITING))
        val handle = StreamingFrames(frames)
        val compositions = mutableMapOf<String, Int>()

        composeRule.setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                val stickToBottom = rememberStickToBottom(listState)
                MessageList(
                    messages = finalised,
                    listState = listState,
                    stickToBottom = stickToBottom,
                    streamingItem = { StreamingMessage(frames = handle) },
                ) { message ->
                    compositions[message.id.value] = (compositions[message.id.value] ?: 0) + 1
                    MessageBubble(message = message, actions = actions)
                }
            }
        }
        composeRule.waitForIdle()

        val baseline = compositions.toMap()
        assertThat(baseline).isNotEmpty()

        // Fixed-width text: the bubble's height never changes, so nothing here
        // is measuring "the list did not scroll" by accident.
        repeat(FRAME_COUNT) { index ->
            frames.value = StreamFrame(
                text = "token %03d".format(index),
                reasoning = "",
                phase = StreamPhase.GENERATING,
            )
            composeRule.waitForIdle()
        }

        assertThat(compositions).containsExactlyEntriesIn(baseline)
        assertThat(compositions.values).containsNoneIn(listOf(0))
        compositions.values.forEach { count -> assertThat(count).isEqualTo(1) }
    }

    /**
     * The control, so the assertion above cannot pass by accident.
     *
     * This is the shape the screen deliberately does *not* have: the in-flight
     * text read by the caller of the list. The same counter then climbs on every
     * frame, which is the behaviour the streaming split exists to avoid.
     */
    @Test
    fun `the counter does move when the caller reads the in-flight text`() {
        val text = mutableStateOf("")
        val compositions = mutableMapOf<String, Int>()

        composeRule.setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                val stickToBottom = rememberStickToBottom(listState)
                val current = text.value
                MessageList(
                    messages = finalised,
                    listState = listState,
                    stickToBottom = stickToBottom,
                    streamingItem = { Text(text = current) },
                ) { message ->
                    compositions[message.id.value] = (compositions[message.id.value] ?: 0) + 1
                    MessageBubble(message = message, actions = actions)
                }
            }
        }
        composeRule.waitForIdle()

        val baseline = compositions.toMap()
        repeat(CONTROL_FRAMES) { index ->
            text.value = "token %03d".format(index)
            composeRule.waitForIdle()
        }

        val grew = compositions.any { (key, count) -> count > (baseline[key] ?: 0) }
        assertThat(grew).isTrue()
    }

    /** The counter above is only meaningful if the streaming bubble did update. */
    @Test
    fun `the streaming bubble announces the finished answer once it settles`() {
        val frames = MutableStateFlow<StreamFrame?>(StreamFrame("", "", StreamPhase.WAITING))
        val handle = StreamingFrames(frames)

        composeRule.setContent {
            MaterialTheme {
                val listState = rememberLazyListState()
                val stickToBottom = rememberStickToBottom(listState)
                MessageList(
                    messages = finalised,
                    listState = listState,
                    stickToBottom = stickToBottom,
                    streamingItem = { StreamingMessage(frames = handle) },
                ) { message ->
                    MessageBubble(message = message, actions = actions)
                }
            }
        }
        composeRule.waitForIdle()

        frames.value = StreamFrame("partial", "", StreamPhase.GENERATING)
        composeRule.waitForIdle()
        // While generating, the live region carries a constant label rather than
        // the text, so a screen reader is not read every token.
        composeRule.onNodeWithContentDescription(GENERATING_LABEL).assertIsDisplayed()

        frames.value = StreamFrame(FINISHED_ANSWER, "", StreamPhase.SETTLING)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(FINISHED_ANSWER).assertIsDisplayed()
    }

    private companion object {
        const val MESSAGE_COUNT = 4
        const val FRAME_COUNT = 40
        const val CONTROL_FRAMES = 5
        const val FINISHED_ANSWER = "the finished answer"
        const val GENERATING_LABEL = "Generating a response"
    }
}
