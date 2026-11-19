package com.openjarvis.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisTaskStateMachineTest {
    @Test
    fun queuedTaskMustEnterPlanningBeforeRunning() {
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.QUEUED, JarvisTaskStatus.PLANNING))
        assertFalse(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.QUEUED, JarvisTaskStatus.SUCCEEDED))
    }

    @Test
    fun planningCanWaitForUserOrNetwork() {
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.PLANNING, JarvisTaskStatus.WAITING_FOR_USER))
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.PLANNING, JarvisTaskStatus.WAITING_FOR_NETWORK))
    }

    @Test
    fun terminalSuccessAndCancellationCannotBeReplayed() {
        assertFalse(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.SUCCEEDED, JarvisTaskStatus.QUEUED))
        assertFalse(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.CANCELLED, JarvisTaskStatus.RUNNING))
    }

    @Test
    fun failedAndWaitingTasksHaveExplicitRetryPaths() {
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.FAILED, JarvisTaskStatus.QUEUED))
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.WAITING_FOR_NETWORK, JarvisTaskStatus.QUEUED))
    }

    @Test
    fun localRegistryHasPlannerButNoExecutionBackend() {
        val registry = JarvisTaskCapabilityRegistry.localPreviewOnly()
        assertNotNull(registry.resolve("Türkçe bir görev"))
        assertNull(registry.executorFor("local.plan-preview"))
    }

    @Test
    fun unknownPersistedStateFailsClosed() {
        assertEquals(JarvisTaskStatus.FAILED, JarvisTaskStatus.fromStorage("unexpected"))
    }

    @Test
    fun nonExecutionAndInterruptionAreDurableRetryableStates() {
        assertEquals(JarvisTaskStatus.NOT_EXECUTED, JarvisTaskStatus.fromStorage("not_executed"))
        assertEquals(JarvisTaskStatus.INTERRUPTED, JarvisTaskStatus.fromStorage("interrupted"))
        assertEquals(0, JarvisTaskStatus.NOT_EXECUTED.defaultProgress)
        assertEquals(0, JarvisTaskStatus.INTERRUPTED.defaultProgress)
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.WAITING_FOR_USER, JarvisTaskStatus.NOT_EXECUTED))
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.RUNNING, JarvisTaskStatus.INTERRUPTED))
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.NOT_EXECUTED, JarvisTaskStatus.RUNNING))
        assertTrue(JarvisTaskStateMachine.canTransition(JarvisTaskStatus.INTERRUPTED, JarvisTaskStatus.RUNNING))
    }

}
