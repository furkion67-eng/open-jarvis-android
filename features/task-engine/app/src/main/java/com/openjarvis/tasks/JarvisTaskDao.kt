package com.openjarvis.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JarvisTaskDao {
    @Query("SELECT * FROM jarvis_tasks ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<JarvisTaskEntity>>

    @Query("SELECT * FROM jarvis_tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): JarvisTaskEntity?

    @Query("SELECT * FROM jarvis_tasks WHERE status IN (:statuses)")
    suspend fun findByStatuses(statuses: List<String>): List<JarvisTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: JarvisTaskEntity)

    @Update
    suspend fun update(task: JarvisTaskEntity): Int

    /** One-time compare-and-set: reviewing the plan is not an external-action approval. */
    @Query("""
        UPDATE jarvis_tasks
        SET planAcceptedAt = :acceptedAt,
            checkpoint = :acceptedCheckpoint,
            updatedAt = :acceptedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND checkpoint = :expectedCheckpoint
          AND planAcceptedAt IS NULL
    """)
    suspend fun acceptPlanCheckpoint(
        id: String,
        expectedStatus: String,
        expectedCheckpoint: String,
        acceptedCheckpoint: String,
        acceptedAt: Long
    ): Int

    /**
     * Atomic execution claim. Only one caller can change the expected waiting state to running.
     * Plan acceptance and any separate side-effect approval are checked in the same SQL statement.
     */
    @Query("""
        UPDATE jarvis_tasks
        SET status = :runningStatus,
            currentStep = :currentStep,
            progressPercent = :progressPercent,
            errorCode = NULL,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND checkpoint = :acceptedCheckpoint
          AND planAcceptedAt IS NOT NULL
          AND (requiresApproval = 0 OR approvalGranted = 1)
    """)
    suspend fun claimForExecution(
        id: String,
        expectedStatus: String,
        runningStatus: String,
        acceptedCheckpoint: String,
        currentStep: String,
        progressPercent: Int,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET status = :waitingStatus,
            currentStep = :currentStep,
            progressPercent = :progressPercent,
            requiresNetwork = 1,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun waitForNetworkIfCurrent(
        id: String,
        expectedStatus: String,
        waitingStatus: String,
        currentStep: String,
        progressPercent: Int,
        errorCode: String,
        errorMessage: String,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET status = :waitingUserStatus,
            currentStep = :currentStep,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun waitForActionApprovalIfCurrent(
        id: String,
        expectedStatus: String,
        waitingUserStatus: String,
        currentStep: String,
        errorCode: String,
        errorMessage: String,
        updatedAt: Long
    ): Int

    /** A result may finish only the execution that still owns the running state. */
    @Query("""
        UPDATE jarvis_tasks
        SET status = :status,
            currentStep = :currentStep,
            progressPercent = :progressPercent,
            outputSummary = :outputSummary,
            artifactRefs = :artifactRefs,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun finishExecutionIfRunning(
        id: String,
        expectedStatus: String,
        status: String,
        currentStep: String,
        progressPercent: Int,
        outputSummary: String?,
        artifactRefs: String,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET status = :cancelledStatus,
            currentStep = :currentStep,
            errorCode = NULL,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun cancelIfCurrent(
        id: String,
        expectedStatus: String,
        cancelledStatus: String,
        currentStep: String,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET status = :queuedStatus,
            currentStep = :currentStep,
            progressPercent = :progressPercent,
            retryCount = retryCount + 1,
            errorCode = NULL,
            errorMessage = NULL,
            checkpoint = NULL,
            planAcceptedAt = NULL,
            approvalGranted = 0,
            approvalReference = NULL,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :failedStatus
    """)
    suspend fun requeueFailedIfCurrent(
        id: String,
        failedStatus: String,
        queuedStatus: String,
        currentStep: String,
        progressPercent: Int,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET status = :queuedStatus,
            currentStep = :currentStep,
            progressPercent = :progressPercent,
            retryCount = retryCount + 1,
            errorCode = NULL,
            errorMessage = NULL,
            checkpoint = NULL,
            planAcceptedAt = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND planAcceptedAt IS NULL
    """)
    suspend fun requeueUnacceptedWaitingTask(
        id: String,
        expectedStatus: String,
        queuedStatus: String,
        currentStep: String,
        progressPercent: Int,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE jarvis_tasks
        SET retryCount = retryCount + 1,
            updatedAt = :updatedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND planAcceptedAt IS NOT NULL
    """)
    suspend fun incrementAcceptedTaskRetryIfCurrent(
        id: String,
        expectedStatus: String,
        updatedAt: Long
    ): Int
}
