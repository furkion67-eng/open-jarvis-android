package com.openjarvis.tasks

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable, local record. It deliberately contains references/summaries, not raw credentials. */
@Entity(
    tableName = "jarvis_tasks",
    indices = [Index(value = ["status", "updatedAt"]), Index(value = ["createdAt"]), Index(value = ["idempotencyKey"], unique = true)]
)
data class JarvisTaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val status: String,
    val ownerAgent: String? = null,
    val currentStep: String? = null,
    val progressPercent: Int = 0,
    val outputSummary: String? = null,
    val artifactRefs: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requiresNetwork: Boolean = false,
    val requiresApproval: Boolean = false,
    val approvalGranted: Boolean = false,
    val approvalReference: String? = null,
    val checkpoint: String? = null,
    val idempotencyKey: String,
    val executionMode: String = "local_plan_preview",
    val estimatedCostMicros: Long = 0L,
    val actualCostMicros: Long = 0L,
    /** Null until the user explicitly accepts the current plan preview. */
    val planAcceptedAt: Long? = null
)

object JarvisTaskCheckpoint {
    const val PLAN_CREATED = "plan_created"
    const val PLAN_REVIEWED = "plan_reviewed"
}

enum class JarvisTaskStatus(
    val storageValue: String,
    val turkishLabel: String,
    val defaultProgress: Int
) {
    QUEUED("queued", "Sırada", 0),
    PLANNING("planning", "Planlanıyor", 10),
    RUNNING("running", "Çalışıyor", 50),
    WAITING_FOR_USER("waiting_for_user", "Senden bilgi / plan onayı bekliyor", 25),
    WAITING_FOR_NETWORK("waiting_for_network", "Ağ bağlantısı bekleniyor", 25),
    NOT_EXECUTED("not_executed", "Yürütülmedi", 0),
    INTERRUPTED("interrupted", "Kesintiye uğradı", 0),
    SUCCEEDED("succeeded", "Tamamlandı", 100),
    FAILED("failed", "Başarısız", 100),
    CANCELLED("cancelled", "İptal edildi", 0);

    val isFinal: Boolean
        get() = this == SUCCEEDED || this == CANCELLED

    companion object {
        fun fromStorage(value: String): JarvisTaskStatus =
            entries.firstOrNull { it.storageValue == value } ?: FAILED
    }
}

object JarvisTaskStateMachine {
    private val allowedTransitions = mapOf(
        JarvisTaskStatus.QUEUED to setOf(
            JarvisTaskStatus.PLANNING,
            JarvisTaskStatus.WAITING_FOR_NETWORK,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.PLANNING to setOf(
            JarvisTaskStatus.RUNNING,
            JarvisTaskStatus.WAITING_FOR_USER,
            JarvisTaskStatus.WAITING_FOR_NETWORK,
            JarvisTaskStatus.FAILED,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.RUNNING to setOf(
            JarvisTaskStatus.WAITING_FOR_USER,
            JarvisTaskStatus.WAITING_FOR_NETWORK,
            JarvisTaskStatus.SUCCEEDED,
            JarvisTaskStatus.FAILED,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.WAITING_FOR_USER to setOf(
            JarvisTaskStatus.RUNNING,
            JarvisTaskStatus.WAITING_FOR_NETWORK,
            JarvisTaskStatus.NOT_EXECUTED,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.FAILED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.WAITING_FOR_NETWORK to setOf(
            JarvisTaskStatus.QUEUED,
            JarvisTaskStatus.RUNNING,
            JarvisTaskStatus.NOT_EXECUTED,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.FAILED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.NOT_EXECUTED to setOf(
            JarvisTaskStatus.QUEUED,
            JarvisTaskStatus.RUNNING,
            JarvisTaskStatus.WAITING_FOR_USER,
            JarvisTaskStatus.INTERRUPTED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.INTERRUPTED to setOf(
            JarvisTaskStatus.QUEUED,
            JarvisTaskStatus.RUNNING,
            JarvisTaskStatus.WAITING_FOR_USER,
            JarvisTaskStatus.NOT_EXECUTED,
            JarvisTaskStatus.CANCELLED
        ),
        JarvisTaskStatus.FAILED to setOf(JarvisTaskStatus.QUEUED, JarvisTaskStatus.CANCELLED),
        JarvisTaskStatus.SUCCEEDED to emptySet(),
        JarvisTaskStatus.CANCELLED to emptySet()
    )

    fun canTransition(from: JarvisTaskStatus, to: JarvisTaskStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true
}
