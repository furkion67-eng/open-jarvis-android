package com.openjarvis.voice

/**
 * Tracks one-shot AUTO_VAD handoffs for queued TTS utterances.
 *
 * All methods are called on Android's main thread by AndroidTTSEngine.
 * Completing all callbacks drains the registry before invoking user code,
 * so callbacks can safely re-enter the engine without being delivered twice.
 */
internal class PendingUtteranceCallbacks {
    private val callbacks = linkedMapOf<String, () -> Unit>()

    fun register(utteranceId: String, callback: () -> Unit) {
        callbacks[utteranceId] = callback
    }

    /** A newer QUEUE_FLUSH utterance owns the next STT handoff. */
    fun discardAll() {
        callbacks.clear()
    }

    fun complete(utteranceId: String) {
        callbacks.remove(utteranceId)?.let { callback ->
            runCatching { callback() }
        }
    }

    /** An explicit stop must release the caller waiting for the current utterance. */
    fun completeAll() {
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { callback -> runCatching { callback() } }
    }
}
