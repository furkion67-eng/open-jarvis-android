package com.openjarvis.tasks

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class JarvisStartupRecoveryGateTest {
    @Test
    fun concurrentDashboardConsumersRunRecoveryOnlyOnce() = runBlocking {
        val gate = JarvisStartupRecoveryGate()
        val calls = AtomicInteger(0)

        coroutineScope {
            repeat(8) {
                launch(Dispatchers.Default) {
                    gate.runOnce {
                        delay(10)
                        calls.incrementAndGet()
                    }
                }
            }
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun failedRecoveryDoesNotPermanentlyCloseTheGate() = runBlocking {
        val gate = JarvisStartupRecoveryGate()
        var calls = 0

        try {
            gate.runOnce {
                calls += 1
                throw IllegalStateException("temporary Room failure")
            }
        } catch (_: IllegalStateException) {
            // A transient startup failure must remain retryable.
        }

        gate.runOnce { calls += 1 }

        assertEquals(2, calls)
    }
}
