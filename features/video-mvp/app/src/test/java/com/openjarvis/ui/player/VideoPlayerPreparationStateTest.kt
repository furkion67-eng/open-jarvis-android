package com.openjarvis.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerPreparationStateTest {
    @Test
    fun initialStateWaitsForAndroidPreparedCallback() {
        val state = VideoPlayerPreparationState.preparing()

        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, state.phase)
        assertTrue(state.isLoading)
        assertFalse(state.canPlay)
        assertNull(state.errorMessage)
    }

    @Test
    fun preparedCallbackEnablesPlaybackAndClearsLoading() {
        val state = VideoPlayerPreparationState.preparing()
            .onError("old failure")
            .onRetry()
            .onPrepared()

        assertEquals(VideoPlayerPreparationState.Phase.READY, state.phase)
        assertFalse(state.isLoading)
        assertTrue(state.canPlay)
        assertNull(state.errorMessage)
    }

    @Test
    fun bufferingStartAndEndToggleOnlyTheBusyState() {
        val ready = VideoPlayerPreparationState.preparing().onPrepared()
        val buffering = ready.onBufferingStarted()

        assertEquals(VideoPlayerPreparationState.Phase.BUFFERING, buffering.phase)
        assertTrue(buffering.isLoading)
        assertFalse(buffering.canPlay)

        val resumed = buffering.onBufferingEnded()
        assertEquals(VideoPlayerPreparationState.Phase.READY, resumed.phase)
        assertFalse(resumed.isLoading)
        assertTrue(resumed.canPlay)
    }

    @Test
    fun mediaErrorStopsLoadingAndKeepsPlaybackDisabledUntilRetry() {
        val failed = VideoPlayerPreparationState.preparing()
            .onError("Video açılamadı")

        assertEquals(VideoPlayerPreparationState.Phase.FAILED, failed.phase)
        assertFalse(failed.isLoading)
        assertFalse(failed.canPlay)
        assertEquals("Video açılamadı", failed.errorMessage)

        val retried = failed.onRetry()
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, retried.phase)
        assertTrue(retried.isLoading)
        assertFalse(retried.canPlay)
        assertNull(retried.errorMessage)
    }

    @Test
    fun bufferingCallbacksCannotEndPreparationBeforeOnPrepared() {
        val preparing = VideoPlayerPreparationState.preparing()
        val afterBufferStart = preparing.onBufferingStarted()
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, afterBufferStart.phase)
        assertTrue(afterBufferStart.isLoading)
        assertFalse(afterBufferStart.canPlay)
        assertEquals(preparing, afterBufferStart.onBufferingEnded())
    }

    @Test
    fun bufferingCallbacksCannotEraseFailure() {
        val failed = VideoPlayerPreparationState.preparing().onError("failed")
        assertEquals(failed, failed.onBufferingStarted())
        assertEquals(failed, failed.onBufferingEnded())
        assertEquals(failed, failed.onPrepared())
    }
}
