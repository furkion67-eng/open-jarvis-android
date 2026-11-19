package com.openjarvis.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe bridge from the IO renderer to Compose.
 *
 * Updates are serialized with terminal transitions so a late frame callback
 * cannot move progress backwards after completion or cancellation.
 */
class VideoRenderProgress {
    private val mutableProgress = MutableStateFlow(0f)
    private var acceptsUpdates = true

    val progress: StateFlow<Float> = mutableProgress.asStateFlow()

    @Synchronized
    fun report(completedFrames: Int, totalFrames: Int) {
        if (!acceptsUpdates || totalFrames <= 0) return
        mutableProgress.value =
            (completedFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
    }

    @Synchronized
    fun finish() {
        if (!acceptsUpdates) return
        acceptsUpdates = false
        mutableProgress.value = 1f
    }

    @Synchronized
    fun stop() {
        acceptsUpdates = false
    }
}
