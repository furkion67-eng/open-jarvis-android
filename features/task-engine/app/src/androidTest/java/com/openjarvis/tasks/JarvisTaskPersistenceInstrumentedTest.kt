package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        repository = JarvisTaskRepository(dao, JarvisTaskOrchestrator(dao, registry), registry)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun planIsPersistedAndNeverReportedAsExecuted() = runBlocking {
        val id = repository.createTask("Sakarya etkinliklerini araştır")
        val planned = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, planned.status)
        assertTrue(planned.outputSummary!!.contains("gerçek cihaz"))
        assertFalse(planned.requiresApproval)

        repository.acceptPlan(id)
        val accepted = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue, accepted.status)
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
    fun interruptedRunningTaskIsPausedInsteadOfReplayed() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(
            JarvisTaskEntity(
                id = "interrupted-task",
                goal = "Bir e-posta taslağı hazırla",
                status = JarvisTaskStatus.QUEUED.storageValue,
                currentStep = "Önceki adım",
                createdAt = now,
                updatedAt = now,
                idempotencyKey = "stable-idempotency-key"
            )
        )

        repository.recoverInterruptedTasks()
        val recovered = dao.findById("interrupted-task")!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue, recovered.status)
        assertEquals("APP_RESTARTED", recovered.errorCode)
        assertTrue(recovered.errorMessage!!.contains("otomatik yürütülmedi"))
    }

    @Test
    fun retryWithoutBackendDoesNotClaimProgress() = runBlocking {
        val id = repository.createTask("Bir araştırma özeti hazırla")
        repository.acceptPlan(id)
        repository.retry(id)
        val retried = dao.findById(id)!!
        assertEquals(JarvisTaskStatus.WAITING_FOR_NETWORK.storageValue, retried.status)
        assertEquals(1, retried.retryCount)
        assertTrue(retried.errorMessage!!.contains("kayıtlı değil"))
    }
}
