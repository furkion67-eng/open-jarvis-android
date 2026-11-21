package com.openjarvis.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerCallbackBridgeTest {
    @Test
    fun productionCallbackBridgeDrivesPreparationBufferingAndFailureStates() {
        val emitted = mutableListOf<VideoPlayerPreparationState>()
        val bridge = VideoPlayerCallbackBridge("attempt-1", emitted::add)

        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, bridge.state.phase)
        assertTrue(bridge.state.isLoading)
        assertFalse(bridge.state.canPlay)

        // Android may report buffering before OnPrepared; it must not enable playback.
        bridge.onBufferingStarted("attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, bridge.state.phase)
        assertTrue(emitted.isEmpty())

        bridge.onPrepared("attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.READY, bridge.state.phase)
        assertTrue(bridge.state.canPlay)

        bridge.onBufferingStarted("attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.BUFFERING, bridge.state.phase)
        assertFalse(bridge.state.canPlay)

        bridge.onBufferingEnded("attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.READY, bridge.state.phase)

        bridge.onError("decoder failed", "attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.FAILED, bridge.state.phase)
        assertFalse(bridge.state.isLoading)
        assertFalse(bridge.state.canPlay)
        assertEquals("decoder failed", bridge.state.errorMessage)
        assertEquals(
            listOf(
                VideoPlayerPreparationState.Phase.READY,
                VideoPlayerPreparationState.Phase.BUFFERING,
                VideoPlayerPreparationState.Phase.READY,
                VideoPlayerPreparationState.Phase.FAILED
            ),
            emitted.map { it.phase }
        )
    }

    @Test
    fun callbacksFromDisposedVideoViewCannotMutateReplacementAttempt() {
        val emitted = mutableListOf<VideoPlayerPreparationState>()
        val bridge = VideoPlayerCallbackBridge("attempt-1", emitted::add)

        bridge.onPrepared("attempt-1")
        bridge.beginAttempt("attempt-2")
        val replacement = bridge.state
        assertEquals("attempt-2", replacement.attemptId)
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, replacement.phase)
        assertNull(replacement.errorMessage)

        bridge.onPrepared("attempt-1")
        bridge.onBufferingStarted("attempt-1")
        bridge.onBufferingEnded("attempt-1")
        bridge.onError("late callback", "attempt-1")
        assertEquals(replacement, bridge.state)

        bridge.onPrepared("attempt-2")
        assertEquals(VideoPlayerPreparationState.Phase.READY, bridge.state.phase)
        assertTrue(bridge.state.canPlay)
        assertEquals(3, emitted.size)
    }

    @Test
    fun retryClearsFailureAndOnlyCurrentAttemptCanBecomeReady() {
        val bridge = VideoPlayerCallbackBridge("attempt-1") { }

        bridge.onError("failed", "attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.FAILED, bridge.state.phase)

        bridge.beginAttempt("attempt-2")
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, bridge.state.phase)
        assertNull(bridge.state.errorMessage)

        bridge.onPrepared("attempt-1")
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, bridge.state.phase)

        bridge.onPrepared("attempt-2")
        assertEquals(VideoPlayerPreparationState.Phase.READY, bridge.state.phase)
    }
}
