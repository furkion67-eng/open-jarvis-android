package com.openjarvis.tasks

/** Lifecycle cancellation is control flow, not a repository failure. */
object DashboardLoadFailurePolicy {
    fun shouldRethrow(error: Exception): Boolean =
        error is kotlinx.coroutines.CancellationException
}
