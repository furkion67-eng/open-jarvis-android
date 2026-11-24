package com.openjarvis.tasks

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DashboardLoadFailurePolicyTest {
    @Test
    fun composeCancellationIsRethrownWithoutClearingDashboardState() {
        val cancellation = CancellationException("composition left")
        val recentTasks = mutableListOf("recent-task")
        val mostUsedApps = mutableListOf("most-used-app")
        var repositoryFailure: Exception? = null
        var thrown: CancellationException? = null

        try {
            DashboardLoadFailurePolicy.handle(cancellation) { error ->
                repositoryFailure = error
                recentTasks.clear()
                mostUsedApps.clear()
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(cancellation, thrown)
        assertNull(repositoryFailure)
        assertEquals(listOf("recent-task"), recentTasks)
        assertEquals(listOf("most-used-app"), mostUsedApps)
    }

    @Test
    fun genuineRepositoryExceptionRunsExistingErrorStateHandler() {
        val repositoryError = IllegalStateException("database unavailable")
        val recentTasks = mutableListOf("recent-task")
        val mostUsedApps = mutableListOf("most-used-app")
        var handled: Exception? = null

        DashboardLoadFailurePolicy.handle(repositoryError) { error ->
            handled = error
            recentTasks.clear()
            mostUsedApps.clear()
        }

        assertSame(repositoryError, handled)
        assertEquals(emptyList<String>(), recentTasks)
        assertEquals(emptyList<String>(), mostUsedApps)
    }
}
