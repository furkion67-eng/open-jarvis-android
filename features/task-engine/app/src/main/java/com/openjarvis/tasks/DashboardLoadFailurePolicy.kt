package com.openjarvis.tasks

/**
 * Lifecycle cancellation is control flow, not a repository failure. The host Dashboard patch
 * delegates its catch branch here so the policy and error-state behavior share one test seam.
 */
object DashboardLoadFailurePolicy {
    inline fun handle(error: Exception, onRepositoryFailure: (Exception) -> Unit) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        onRepositoryFailure(error)
    }
}
