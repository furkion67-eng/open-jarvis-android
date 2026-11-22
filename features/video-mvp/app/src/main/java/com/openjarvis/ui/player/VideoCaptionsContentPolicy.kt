package com.openjarvis.ui.player

import com.openjarvis.tasks.VideoArtifactCaptionsPolicy
import java.io.InputStream

/**
 * Player-facing adapter to the shared task artifact policy.
 *
 * The same strict byte ceiling and UTF-8/SRT validation is used before a task is persisted and
 * again when the player restores it after process recreation.
 */
internal object VideoCaptionsContentPolicy {
    const val MAX_CAPTIONS_BYTES: Int = VideoArtifactCaptionsPolicy.MAX_CAPTIONS_BYTES

    fun readValidated(
        input: InputStream,
        maxBytes: Int = MAX_CAPTIONS_BYTES
    ): String? = VideoArtifactCaptionsPolicy.readValidated(input, maxBytes)
}
