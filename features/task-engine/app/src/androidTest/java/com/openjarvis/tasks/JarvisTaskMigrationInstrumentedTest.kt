package com.openjarvis.tasks

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JarvisTaskMigrationInstrumentedTest {
    @Test
    fun versionOneDatabaseMigratesAndPreservesTasksWithoutInventingPlanAcceptance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "jarvis_tasks_v1_migration_test.db"
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val legacy = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        legacy.execSQL("""
            CREATE TABLE jarvis_tasks (
                id TEXT NOT NULL,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                ownerAgent TEXT,
                currentStep TEXT,
                progressPercent INTEGER NOT NULL,
                outputSummary TEXT,
                artifactRefs TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                retryCount INTEGER NOT NULL,
                errorCode TEXT,
                errorMessage TEXT,
                requiresNetwork INTEGER NOT NULL,
                requiresApproval INTEGER NOT NULL,
                approvalGranted INTEGER NOT NULL,
                approvalReference TEXT,
                checkpoint TEXT,
                idempotencyKey TEXT NOT NULL,
                executionMode TEXT NOT NULL,
                estimatedCostMicros INTEGER NOT NULL,
                actualCostMicros INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
        """.trimIndent())
        legacy.execSQL(
            "CREATE INDEX index_jarvis_tasks_status_updatedAt ON jarvis_tasks (status, updatedAt)"
        )
        legacy.execSQL(
            "CREATE INDEX index_jarvis_tasks_createdAt ON jarvis_tasks (createdAt)"
        )
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros
            ) VALUES (
                'legacy-task', 'Eski görev', 'waiting_for_user', NULL, NULL, 0, NULL,
                '[]', 1, 1, 0, NULL, NULL, 0, 0, 0, NULL,
                'plan_created', 'stable-legacy-key', 'local_plan_preview', 0, 0
            )
        """.trimIndent())
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros
            ) VALUES (
                'duplicate-legacy-task', 'Eski yinelenen görev', 'queued', NULL, NULL, 0, NULL,
                '[]', 2, 2, 0, NULL, NULL, 0, 0, 0, NULL,
                NULL, 'stable-legacy-key', 'local_plan_preview', 0, 0
            )
        """.trimIndent())
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros
            ) VALUES (
                'legacy-not-executed', 'Yürütülmeyen eski görev', 'waiting_for_network', NULL, NULL, 25, NULL,
                '[]', 3, 3, 0, 'EXECUTOR_NOT_CONFIGURED', 'Yürütücü yok', 0, 1, 1, 'stale-approval',
                'plan_reviewed', 'legacy-not-executed-key', 'local_plan_preview', 0, 0
            )
        """.trimIndent())
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros
            ) VALUES (
                'legacy-interrupted', 'Kesilen eski görev', 'waiting_for_network', NULL, NULL, 65, NULL,
                '[]', 4, 4, 0, 'APP_RESTARTED', 'Uygulama yeniden başladı', 0, 1, 1, 'stale-approval',
                'plan_reviewed', 'legacy-interrupted-key', 'remote.device-action', 0, 0
            )
        """.trimIndent())
        legacy.execSQL("PRAGMA user_version = 1")
        legacy.close()

        val migrated = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).addMigrations(JarvisTaskDatabase.MIGRATION_1_2, JarvisTaskDatabase.MIGRATION_2_3, JarvisTaskDatabase.MIGRATION_3_4).build()

        try {
            val task = migrated.taskDao().findById("legacy-task")!!
            assertEquals("Eski görev", task.goal)
            assertEquals("stable-legacy-key", task.idempotencyKey)
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, task.status)
            assertNull(task.planAcceptedAt)
            val duplicateCount = migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM jarvis_tasks WHERE idempotencyKey = ?",
                arrayOf("stable-legacy-key")
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(1, duplicateCount)
            assertEquals("legacy-task", migrated.taskDao().findByIdempotencyKey("stable-legacy-key")?.id)

            val notExecuted = migrated.taskDao().findById("legacy-not-executed")!!
            assertEquals(JarvisTaskStatus.NOT_EXECUTED.storageValue, notExecuted.status)
            assertEquals(0, notExecuted.progressPercent)
            assertEquals("Yürütülmedi · yürütücü yok", notExecuted.currentStep)
            assertEquals(false, notExecuted.approvalGranted)
            assertNull(notExecuted.approvalReference)

            val interrupted = migrated.taskDao().findById("legacy-interrupted")!!
            assertEquals(JarvisTaskStatus.INTERRUPTED.storageValue, interrupted.status)
            assertEquals(0, interrupted.progressPercent)
            assertEquals("Güvenli kurtarma beklemesi", interrupted.currentStep)
            assertEquals(false, interrupted.approvalGranted)
            assertNull(interrupted.approvalReference)
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }
}
