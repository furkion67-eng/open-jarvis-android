package com.openjarvis.ui.player

/**
 * Pure state reducer for the embedded Android VideoView lifecycle.
 *
 * A URI is not considered playable until Android reports OnPrepared. Buffering disables
 * explicit playback controls, and a media error stays non-playable until the user retries.
 */
internal data class VideoPlayerPreparationState(
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

    fun onPrepared(): VideoPlayerPreparationState =
        copy(phase = Phase.READY, errorMessage = null)

    fun onBufferingStarted(): VideoPlayerPreparationState =
        if (phase == Phase.FAILED) this else copy(phase = Phase.BUFFERING, errorMessage = null)

    fun onBufferingEnded(): VideoPlayerPreparationState =
        if (phase == Phase.BUFFERING) copy(phase = Phase.READY) else this

    fun onError(message: String): VideoPlayerPreparationState =
        copy(
            phase = Phase.FAILED,
            errorMessage = message.ifBlank { "Video JARVIS içinde oynatılamadı." }
        )

    fun onRetry(): VideoPlayerPreparationState = preparing()

    companion object {
        fun preparing(): VideoPlayerPreparationState =
            VideoPlayerPreparationState(phase = Phase.PREPARING)
    }
}
