package com.openjarvis.tasks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates one process-scoped value for concurrent Dashboard consumers.
 *
 * A failed or cancelled factory is not cached, so a later screen entry can retry safely.
 */
internal class JarvisTaskRepositoryBootstrap<T : Any> {
    private val mutex = Mutex()

    @Volatile
    private var value: T? = null

    suspend fun getOrCreate(factory: suspend () -> T): T {
        value?.let { return it }
        return mutex.withLock {
            value ?: factory().also { value = it }
        }
    }

    /** Used only after closing the process Room singleton in instrumentation tests. */
    fun resetForTesting() {
        value = null
    }
}
