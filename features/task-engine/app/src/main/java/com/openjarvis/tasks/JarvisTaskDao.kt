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

    /** Paginated archive of only verified video task rows; URI validation remains in the player. */
    @Query("""
        SELECT * FROM jarvis_tasks
        WHERE status = :succeededStatus
          AND videoUri LIKE 'content://%'
          AND captionsUri LIKE 'content://%'
        ORDER BY updatedAt DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeSuccessfulVideoArtifacts(
        succeededStatus: String,
        limit: Int,
        offset: Int
    ): Flow<List<JarvisTaskEntity>>

    @Query("SELECT * FROM jarvis_tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<JarvisTaskEntity?>

    @Query("SELECT * FROM jarvis_device_action_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentDeviceActionEvents(limit: Int): Flow<List<DeviceActionEventEntity>>

    @Query("SELECT * FROM jarvis_device_action_events WHERE id = :id LIMIT 1")
    suspend fun findDeviceActionEventById(id: String): DeviceActionEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDeviceActionEvent(event: DeviceActionEventEntity)

    @Query("""
        DELETE FROM jarvis_device_action_events
        WHERE id NOT IN (
            SELECT id FROM jarvis_device_action_events
            ORDER BY createdAt DESC LIMIT :keep
        )
    """)
    suspend fun pruneDeviceActionEvents(keep: Int): Int

    @Query("SELECT * FROM jarvis_tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): JarvisTaskEntity?

    @Query("SELECT * FROM jarvis_tasks WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(idempotencyKey: String): JarvisTaskEntity?

    @Query("DELETE FROM jarvis_tasks WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM jarvis_tasks WHERE status IN (:statuses)")
    suspend fun findByStatuses(statuses: List<String>): List<JarvisTaskEntity>

    /**
     * One atomic startup repair for abandoned active tasks. Only QUEUED, PLANNING and RUNNING
     * are passed by the repository; user-waiting and terminal tasks are never touched.
     */
    @Query("""
        UPDATE jarvis_tasks
        SET status = :interruptedStatus,
            currentStep = :currentStep,
            progressPercent = 0,
            approvalGranted = 0,
            approvalReference = NULL,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE status IN (:expectedStatuses)
    """)
    suspend fun markActiveTasksInterrupted(
        expectedStatuses: List<String>,
        interruptedStatus: String,
        currentStep: String,
        errorCode: String,
        errorMessage: String,
        updatedAt: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: JarvisTaskEntity)

    /** Atomic idempotent insert: -1 means an existing request key won the race. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: JarvisTaskEntity): Long

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
            executionMode = :executionMode,
            progressPercent = :progressPercent,
            artifactRefs = '[]',
            videoUri = NULL,
            captionsUri = NULL,
            videoArtifactMetadataJson = NULL,
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
        executionMode: String,
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

    /** Persist that no executor was available; this is not a network wait or running task. */
    @Query("""
        UPDATE jarvis_tasks
        SET status = :notExecutedStatus,
            currentStep = :currentStep,
            progressPercent = 0,
            approvalGranted = 0,
            approvalReference = NULL,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun markNotExecutedIfCurrent(
        id: String,
        expectedStatus: String,
        notExecutedStatus: String,
        currentStep: String,
        errorCode: String,
        errorMessage: String,
        updatedAt: Long
    ): Int

    /** Persist process death as an interrupted task and revoke any stale side-effect approval. */
    @Query("""
        UPDATE jarvis_tasks
        SET status = :interruptedStatus,
            currentStep = :currentStep,
            progressPercent = 0,
            approvalGranted = 0,
            approvalReference = NULL,
            errorCode = :errorCode,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
    """)
    suspend fun markInterruptedIfCurrent(
        id: String,
        expectedStatus: String,
        interruptedStatus: String,
        currentStep: String,
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
            videoUri = :videoUri,
            captionsUri = :captionsUri,
            videoArtifactMetadataJson = :videoArtifactMetadataJson,
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
        videoUri: String?,
        captionsUri: String?,
        videoArtifactMetadataJson: String?,
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
