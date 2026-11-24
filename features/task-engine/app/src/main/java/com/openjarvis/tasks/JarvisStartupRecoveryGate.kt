package com.openjarvis.tasks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs process-start recovery exactly once for concurrent Dashboard consumers.
 * A failed recovery remains retryable; successful recovery never replays a task.
 */
internal class JarvisStartupRecoveryGate {
    private val mutex = Mutex()
    @Volatile private var completed = false

    /** Clears process state between file-backed database reopen tests only. */
    fun resetForTesting() {
        completed = false
    }

    suspend fun runOnce(recover: suspend () -> Unit) {
        mutex.withLock {
            if (!completed) {
                recover()
                completed = true
            }
        }
    }
}
