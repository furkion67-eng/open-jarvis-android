package com.openjarvis.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VideoPlayerPreparationControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controlsFollowPreparationBufferingFailureAndAttemptScopedRetry() {
        val firstAttempt = "task-1|content://media/1|retry-0"
        val secondAttempt = "task-1|content://media/1|retry-1"
        val state = mutableStateOf(VideoPlayerPreparationState.preparing(firstAttempt))
        val playClicks = AtomicInteger(0)
        val replayClicks = AtomicInteger(0)

        composeRule.setContent {
            MaterialTheme {
                VideoPlayerPreparationControls(
                    state = state.value,
                    canPlay = state.value.canPlay,
                    onPlay = { playClicks.incrementAndGet() },
                onReplay = { replayClicks.incrementAndGet() },
                    onRetry = { state.value = state.value.onRetry(secondAttempt) }
                )
            }
        }

        assertTagCount("video-player-loading", 1)
        assertDisabled("video-player-play")
        assertDisabled("video-player-replay")

        composeRule.runOnIdle {
            state.value = state.value.onBufferingStarted(firstAttempt)
        }
        composeRule.waitForIdle()
        assertDisabled("video-player-play")

        composeRule.runOnIdle {
            state.value = state.value.onPrepared(firstAttempt)
        }
        composeRule.waitForIdle()
        assertTagCount("video-player-loading", 0)
        assertEnabled("video-player-play")
        assertEnabled("video-player-replay")

        composeRule.onNodeWithTag("video-player-play").performClick()
        composeRule.onNodeWithTag("video-player-replay").performClick()
        assertEquals(1, playClicks.get())
        assertEquals(1, replayClicks.get())

        composeRule.runOnIdle {
            state.value = state.value.onBufferingStarted(firstAttempt)
        }
        composeRule.waitForIdle()
        assertTagCount("video-player-loading", 1)
        assertDisabled("video-player-play")
        assertDisabled("video-player-replay")

        composeRule.runOnIdle {
            state.value = state.value.onBufferingEnded(firstAttempt)
        }
        composeRule.waitForIdle()
        assertEnabled("video-player-play")

        composeRule.runOnIdle {
            state.value = state.value.onError("Decoder failed", firstAttempt)
        }
        composeRule.waitForIdle()
        assertTagCount("video-player-loading", 0)
        assertTagCount("video-player-error", 1)
        assertDisabled("video-player-play")

        // A late prepared callback from the failed attempt cannot clear its failure.
        composeRule.runOnIdle {
            state.value = state.value.onPrepared(firstAttempt)
        }
        composeRule.waitForIdle()
        assertTagCount("video-player-error", 1)
        assertDisabled("video-player-play")

        // The visible retry action starts a new attempt and clears the old error.
        composeRule.onNodeWithTag("video-player-retry").performClick()
        composeRule.waitForIdle()
        assertEquals(secondAttempt, state.value.attemptId)
        assertTagCount("video-player-loading", 1)
        assertTagCount("video-player-error", 0)
        assertDisabled("video-player-play")

        // Old callbacks are ignored after retry; only the new VideoView can enable playback.
        composeRule.runOnIdle {
            state.value = state.value.onPrepared(firstAttempt)
            state.value = state.value.onError("late error", firstAttempt)
        }
        composeRule.waitForIdle()
        assertDisabled("video-player-play")
        assertTagCount("video-player-error", 0)

        composeRule.runOnIdle {
            state.value = state.value.onPrepared(secondAttempt)
        }
        composeRule.waitForIdle()
        assertTagCount("video-player-loading", 0)
        assertEnabled("video-player-play")
        assertFalse(state.value.isLoading)
        assertTrue(state.value.canPlay)
    }

    private fun assertDisabled(tag: String) {
        val config = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
        assertTrue("$tag must expose the disabled semantics property", config.contains(SemanticsProperties.Disabled))
    }

    private fun assertEnabled(tag: String) {
        val config = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
        assertFalse("$tag must not expose the disabled semantics property", config.contains(SemanticsProperties.Disabled))
    }

    private fun assertTagCount(tag: String, expected: Int) {
        val actual = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
        assertEquals("$tag node count", expected, actual)
    }
}
