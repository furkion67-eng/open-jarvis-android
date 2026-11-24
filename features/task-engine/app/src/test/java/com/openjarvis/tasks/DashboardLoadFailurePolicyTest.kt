package com.openjarvis.tasks

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLoadFailurePolicyTest {
    @Test
    fun composeCancellationIsRethrownInsteadOfBecomingAnEmptyRepositoryResult() {
        assertTrue(DashboardLoadFailurePolicy.shouldRethrow(CancellationException("composition left")))
    }

    @Test
    fun genuineRepositoryFailureRemainsInTheExistingGenericErrorBranch() {
        assertFalse(DashboardLoadFailurePolicy.shouldRethrow(IllegalStateException("database unavailable")))
    }
}
