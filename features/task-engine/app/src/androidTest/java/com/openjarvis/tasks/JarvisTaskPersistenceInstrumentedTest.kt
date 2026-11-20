package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JarvisTaskPersistenceInstrumentedTest {
    private lateinit var database: JarvisTaskDatabase
    private lateinit var repository: JarvisTaskRepository
    private lateinit var dao: JarvisTaskDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, JarvisTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.taskDao()
        val registry = JarvisTaskCapabilityRegistry.localPreviewOnly()
        repository = JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun concurrentSameRequestKeyCreatesSinglePersistedTask() = runBlocking {
        val ids = coroutineScope {
            awaitAll(
                async(Dispatchers.IO) { repository.createTask("Aynı görev", "same-request-001") },
                async(Dispatchers.IO) { repository.createTask("Aynı görev", "same-request-001") }
            )
        }

        assertEquals(ids[0], ids[1])
        val persisted = dao.observeRecent(20).first()
        assertEquals(1, persisted.count { it.idempotencyKey == "same-request-001" })
    }

    @Test
    fun sameGoalWithDifferentRequestKeysCreatesDistinctTasks() = runBlocking {
        val first = repository.createTask("Aynı hedef", "request-first")
        val second = repository.createTask("Aynı hedef", "request-second")

        assertNotEquals(first, second)
        val persisted = dao.observeRecent(20).first()
        assertEquals(2, persisted.count { it.goal == "Aynı hedef" })
    }

    @Test
    fun sameRequestKeyAfterRepositoryRecreationReturnsExistingTask() = runBlocking {
        val first = repository.createTask("Uygulamayı yeniden başlat", "persisted-request-001")
        val recreated = JarvisTaskRepository(
            dao,
            JarvisTaskOrchestrator(dao, JarvisTaskCapabilityRegistry.localPreviewOnly())
        )

        val second = recreated.createTask("Uygulamayı yeniden başlat", "persisted-request-001")

        assertEquals(first, second)
        assertEquals(1, dao.observeRecent(20).first().count { it.idempotencyKey == "persisted-request-001" })
    }

    @Test
    fun planIsPersistedAndNeverReportedAsExecuted() = runBlocking {
        val id = repository.createTask("Sakarya etkinliklerini araştır")
        val planned = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, planned.status)
        assertTrue(planned.outputSummary!!.contains("gerçek cihaz"))
        assertEquals(JarvisTaskCheckpoint.PLAN_CREATED, planned.checkpoint)
        assertNull(planned.planAcceptedAt)
        assertFalse(planned.requiresApproval)

        repository.acceptPlan(id)
        val accepted = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.NOT_EXECUTED.storageValue, accepted.status)
        assertEquals(JarvisTaskCheckpoint.PLAN_REVIEWED, accepted.checkpoint)
        assertNotNull(accepted.planAcceptedAt)
        assertEquals("EXECUTOR_NOT_CONFIGURED", accepted.errorCode)
        assertTrue(accepted.errorMessage!!.contains("başlatılmadı"))
        assertFalse(accepted.status == JarvisTaskStatus.SUCCEEDED.storageValue)
    }

    @Test
    fun cancelIsPersistedAndCannotBeAcceptedLater() = runBlocking {
        val id = repository.createTask("Bir not hazırla")
        repository.cancel(id)
        repository.acceptPlan(id)
        assertEquals(JarvisTaskStatus.CANCELLED.storageValue, dao.findById(id)!!.status)
    }

    @Test
    fun interruptedQueuedTaskIsPausedInsteadOfReplayed() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(
            JarvisTaskEntity(
                id = "interrupted-task",
                goal = "Bir e-posta taslağı hazırla",
                status = JarvisTaskStatus.QUEUED.storageValue,
                currentStep = "Önceki adım",
                progressPercent = 65,
                createdAt = now,
                updatedAt = now,
                idempotencyKey = "stable-idempotency-key"
            )
        )

        repository.recoverInterruptedTasks()
        val recovered = dao.findById("interrupted-task")!!
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, recovered.status)
        assertEquals("APP_RESTARTED", recovered.errorCode)
        assertEquals(0, recovered.progressPercent)
        assertTrue(recovered.errorMessage!!.contains("otomatik yürütülmedi"))

        repository.retry("interrupted-task")
        val replanned = dao.findById("interrupted-task")!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, replanned.status)
        assertEquals(JarvisTaskCheckpoint.PLAN_CREATED, replanned.checkpoint)
        assertNull(replanned.planAcceptedAt)
    }

    @Test
    fun interruptedRunningTaskResetsStaleProgressAndIsNotAutoReplayed() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(
            JarvisTaskEntity(
                id = "interrupted-running-task",
                goal = "Onaylı yerel işlem",
                status = JarvisTaskStatus.RUNNING.storageValue,
                ownerAgent = "remote.device-action",
                currentStep = "Önceki yürütme",
                progressPercent = 65,
                checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
                planAcceptedAt = now,
                createdAt = now,
                updatedAt = now,
                idempotencyKey = "interrupted-running-key",
                executionMode = "remote.device-action"
            )
        )

        repository.recoverInterruptedTasks()

        val recovered = dao.findById("interrupted-running-task")!!
        assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, recovered.status)
        assertEquals("APP_RESTARTED", recovered.errorCode)
        assertEquals(0, recovered.progressPercent)
        assertEquals(now, recovered.planAcceptedAt)
        assertFalse(recovered.status == JarvisTaskStatus.SUCCEEDED.storageValue)
    }

    @Test
    fun retryWithoutBackendDoesNotClaimProgress() = runBlocking {
        val id = repository.createTask("Bir araştırma özeti hazırla")
        repository.acceptPlan(id)
        val acceptedKey = dao.findById(id)!!.idempotencyKey
        repository.retry(id)
        val retried = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.NOT_EXECUTED.storageValue, retried.status)
        assertEquals(1, retried.retryCount)
        assertEquals(acceptedKey, retried.idempotencyKey)
        assertEquals(0, retried.progressPercent)
        assertTrue(retried.errorMessage!!.contains("yapılandırılmadı"))
    }

    @Test
    fun smsOutcomeIsDurablyJournaledWithoutContactOrMessageData() = runBlocking {
        val eventId = repository.recordDeviceActionOutcome(
            actionType = "sms",
            outcomeCode = "SENT",
            safeSummary = "Tüm SMS_SENT callback'leri RESULT_OK döndü; teslimat doğrulanmadı."
        )

        val event = dao.findDeviceActionEventById(eventId)!!
        assertEquals("sms", event.actionType)
        assertEquals("SENT", event.outcomeCode)
        assertTrue(event.safeSummary.contains("RESULT_OK"))
        assertFalse(event.safeSummary.contains("+905551112233"))
        assertFalse(event.safeSummary.contains("Özel mesaj metni"))
        assertEquals(eventId, dao.observeRecentDeviceActionEvents(10).first().single().id)
    }

    @Test
    fun planReviewDoesNotGrantActionApproval() = runBlocking {
        val now = System.currentTimeMillis()
        val id = "approval-gated-task"
        dao.insert(
            JarvisTaskEntity(
                id = id,
                goal = "Bir SMS gönder",
                status = JarvisTaskStatus.WAITING_FOR_USER.storageValue,
                ownerAgent = "local.plan-preview",
                requiresApproval = true,
                approvalGranted = false,
                checkpoint = JarvisTaskCheckpoint.PLAN_REVIEWED,
                planAcceptedAt = now,
                createdAt = now,
                updatedAt = now,
                idempotencyKey = "approval-gated-key"
            )
        )
        val registry = JarvisTaskCapabilityRegistry.localPreviewOnly()
        JarvisTaskOrchestrator(dao, registry).execute(id)
        val paused = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, paused.status)
        assertEquals("APPROVAL_REQUIRED", paused.errorCode)
        assertFalse(paused.approvalGranted)
    }
}
