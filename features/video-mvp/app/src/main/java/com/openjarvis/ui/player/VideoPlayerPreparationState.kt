package com.openjarvis.ui.player

/**
 * Pure state reducer for the embedded Android VideoView lifecycle.
 *
 * Every callback is scoped to the URI/task attempt that created its VideoView. A late callback
 * from a disposed player cannot mark a replacement URI ready or overwrite its error state.
 */
internal data class VideoPlayerPreparationState(
    val attemptId: String,
    val phase: Phase,
    val errorMessage: String? = null
) {
    enum class Phase {
        PREPARING,
        READY,
        BUFFERING,
        FAILED
    }

    val isLoading: Boolean
        get() = phase == Phase.PREPARING || phase == Phase.BUFFERING

    val canPlay: Boolean
        get() = phase == Phase.READY

    fun onPrepared(callbackAttemptId: String = attemptId): VideoPlayerPreparationState =
        if (callbackAttemptId != attemptId || phase == Phase.FAILED) {
            this
        } else {
            copy(phase = Phase.READY, errorMessage = null)
        }

    fun onBufferingStarted(callbackAttemptId: String = attemptId): VideoPlayerPreparationState =
        if (callbackAttemptId == attemptId && phase == Phase.READY) {
            copy(phase = Phase.BUFFERING)
        } else {
            this
        }

    fun onBufferingEnded(callbackAttemptId: String = attemptId): VideoPlayerPreparationState =
        if (callbackAttemptId == attemptId && phase == Phase.BUFFERING) {
            copy(phase = Phase.READY)
        } else {
            this
        }

    fun onError(
        message: String,
        callbackAttemptId: String = attemptId
    ): VideoPlayerPreparationState =
        if (callbackAttemptId != attemptId) {
            this
        } else {
            copy(
                phase = Phase.FAILED,
                errorMessage = message.ifBlank { "Video JARVIS içinde oynatılamadı." }
            )
        }

    fun onRetry(nextAttemptId: String = attemptId): VideoPlayerPreparationState =
        preparing(nextAttemptId)

    companion object {
        fun preparing(attemptId: String = "initial"): VideoPlayerPreparationState =
            VideoPlayerPreparationState(
                attemptId = attemptId,
                phase = Phase.PREPARING
            )
    }
}
