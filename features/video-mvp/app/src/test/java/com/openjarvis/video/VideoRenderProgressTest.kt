package com.openjarvis.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRenderProgressTest {
    @Test
    fun acceptsWorkerThreadUpdatesAndIgnoresUpdatesAfterCompletion() = runBlocking {
        val progress = VideoRenderProgress()

        withContext(Dispatchers.Default) {
            progress.report(completedFrames = 3, totalFrames = 10)
        }
        assertEquals(0.3f, progress.progress.value, 0.0001f)

        progress.finish()
        withContext(Dispatchers.Default) {
            progress.report(completedFrames = 4, totalFrames = 10)
        }
        assertEquals(1f, progress.progress.value, 0f)
    }

    @Test
    fun cancellationStopsLaterProgressUpdates() = runBlocking {
        val progress = VideoRenderProgress()
        withContext(Dispatchers.Default) {
            progress.report(completedFrames = 2, totalFrames = 10)
        }
        progress.stop()

        withContext(Dispatchers.Default) {
            progress.report(completedFrames = 9, totalFrames = 10)
        }
        assertEquals(0.2f, progress.progress.value, 0.0001f)
    }

    @Test
    fun invalidFrameCountsDoNotProduceInvalidProgress() = runBlocking {
        val progress = VideoRenderProgress()
        withContext(Dispatchers.Default) {
            progress.report(completedFrames = 1, totalFrames = 0)
            progress.report(completedFrames = 12, totalFrames = 10)
        }
        assertEquals(1f, progress.progress.value, 0f)
    }
}
