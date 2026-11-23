package com.openjarvis.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class JarvisTaskRepositoryBootstrapTest {
    @Test
    fun concurrentDashboardConsumersShareOneRepositoryInstance() = runBlocking {
        val bootstrap = JarvisTaskRepositoryBootstrap<Any>()
        val creations = AtomicInteger(0)
        val expected = Any()

        val results = coroutineScope {
            (1..12).map {
                async(Dispatchers.Default) {
                    bootstrap.getOrCreate {
                        delay(25)
                        creations.incrementAndGet()
                        expected
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, creations.get())
        results.forEach { assertSame(expected, it) }
    }

    @Test
    fun failedOrCancelledFactoryIsNotCachedAndCanBeRetried() = runBlocking {
        val bootstrap = JarvisTaskRepositoryBootstrap<Any>()
        val creations = AtomicInteger(0)
        val expected = Any()

        try {
            bootstrap.getOrCreate {
                creations.incrementAndGet()
                throw IllegalStateException("temporary Room bootstrap failure")
            }
        } catch (_: IllegalStateException) {
            // A transient failure must not poison the process-scoped cache.
        }

        val recovered = bootstrap.getOrCreate {
            creations.incrementAndGet()
            expected
        }
        val reused = bootstrap.getOrCreate {
            creations.incrementAndGet()
            Any()
        }

        assertEquals(2, creations.get())
        assertSame(expected, recovered)
        assertSame(expected, reused)
    }

    @Test
    fun testResetAllowsAFileBackedDatabaseReopen() = runBlocking {
        val bootstrap = JarvisTaskRepositoryBootstrap<Any>()
        val first = Any()
        val second = Any()

        assertSame(first, bootstrap.getOrCreate { first })
        bootstrap.resetForTesting()
        assertSame(second, bootstrap.getOrCreate { second })
    }
}
