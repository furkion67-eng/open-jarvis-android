package com.openjarvis.ui.player

/**
 * Production boundary between Android VideoView callbacks and the preparation state reducer.
 *
 * VideoView listeners call these methods directly. Keeping attempt identity here makes the
 * callback path independently testable without creating a real MediaPlayer or sending media
 * through Android's decoder in JVM tests.
 */
internal class VideoPlayerCallbackBridge(
    initialAttemptId: String,
    private val onStateChanged: (VideoPlayerPreparationState) -> Unit
) {
    var state: VideoPlayerPreparationState =
        VideoPlayerPreparationState.preparing(initialAttemptId)
        private set

    fun beginAttempt(attemptId: String) {
        dispatch(state.onRetry(attemptId))
    }

    fun onPrepared(callbackAttemptId: String) {
        dispatch(state.onPrepared(callbackAttemptId))
    }

    fun onBufferingStarted(callbackAttemptId: String) {
        dispatch(state.onBufferingStarted(callbackAttemptId))
    }

    fun onBufferingEnded(callbackAttemptId: String) {
        dispatch(state.onBufferingEnded(callbackAttemptId))
    }

    fun onError(message: String, callbackAttemptId: String) {
        dispatch(state.onError(message, callbackAttemptId))
    }

    private fun dispatch(nextState: VideoPlayerPreparationState) {
        if (nextState != state) {
            state = nextState
            onStateChanged(nextState)
        }
    }
}
