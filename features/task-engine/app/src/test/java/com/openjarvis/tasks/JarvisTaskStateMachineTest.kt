package com.openjarvis.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun unknownPersistedStateFailsClosed() {
        assertEquals(JarvisTaskStatus.FAILED, JarvisTaskStatus.fromStorage("unexpected"))
    }
}
