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
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros
            ) VALUES (
                'legacy-failed', 'Başarısız eski görev', 'failed', NULL, 'İşlem başarısız', 100, NULL,
                '[]', 5, 5, 0, 'EXECUTION_FAILED', 'Görev tamamlanamadı', 0, 0, 0, NULL,
                'plan_reviewed', 'legacy-failed-key', 'remote.device-action', 0, 0
            )
        """.trimIndent())
        legacy.execSQL("PRAGMA user_version = 1")
        legacy.close()

        val migrated = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).addMigrations(JarvisTaskDatabase.MIGRATION_1_2, JarvisTaskDatabase.MIGRATION_2_3, JarvisTaskDatabase.MIGRATION_3_4, JarvisTaskDatabase.MIGRATION_4_5, JarvisTaskDatabase.MIGRATION_5_6, JarvisTaskDatabase.MIGRATION_6_7).build()

        try {
            val task = migrated.taskDao().findById("legacy-task")!!
            assertEquals("Eski görev", task.goal)
            assertEquals("stable-legacy-key", task.idempotencyKey)
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, task.status)
            assertNull(task.planAcceptedAt)
            assertNull(task.videoUri)
            assertNull(task.captionsUri)
            assertNull(task.videoArtifactMetadataJson)
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

            val failed = migrated.taskDao().findById("legacy-failed")!!
            assertEquals(JarvisTaskStatus.FAILED.storageValue, failed.status)
            assertEquals(0, failed.progressPercent)

            migrated.taskDao().insertDeviceActionEvent(
                DeviceActionEventEntity(
                    id = "migration-sms-event",
                    actionType = "sms",
                    outcomeCode = "UNKNOWN",
                    safeSummary = "SMS callback yok; otomatik tekrar yapılmadı.",
                    createdAt = 6L
                )
            )
            assertEquals(
                "UNKNOWN",
                migrated.taskDao().findDeviceActionEventById("migration-sms-event")?.outcomeCode
            )
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionSixDatabaseMigratesDirectlyToSevenAndPreservesVideoArtifact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "jarvis_tasks_v6_to_v7_migration_test.db"
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
                planAcceptedAt INTEGER,
                videoUri TEXT,
                captionsUri TEXT,
                videoArtifactMetadataJson TEXT,
                PRIMARY KEY(id)
            )
        """.trimIndent())
        legacy.execSQL(
            "CREATE INDEX index_jarvis_tasks_status_updatedAt ON jarvis_tasks (status, updatedAt)"
        )
        legacy.execSQL(
            "CREATE INDEX index_jarvis_tasks_createdAt ON jarvis_tasks (createdAt)"
        )
        legacy.execSQL(
            "CREATE UNIQUE INDEX index_jarvis_tasks_idempotencyKey ON jarvis_tasks (idempotencyKey)"
        )
        legacy.execSQL("""
            INSERT INTO jarvis_tasks (
                id, goal, status, ownerAgent, currentStep, progressPercent, outputSummary,
                artifactRefs, createdAt, updatedAt, retryCount, errorCode, errorMessage,
                requiresNetwork, requiresApproval, approvalGranted, approvalReference,
                checkpoint, idempotencyKey, executionMode, estimatedCostMicros, actualCostMicros,
                planAcceptedAt, videoUri, captionsUri, videoArtifactMetadataJson
            ) VALUES (
                'v6-video-task', 'Üretilmiş videoyu oynat', 'succeeded', 'video.story',
                'Video hazır', 100, 'MP4 ve altyazı kaydedildi', '[]', 100, 200, 0,
                NULL, NULL, 0, 0, 0, NULL, 'plan_reviewed', 'v6-video-key',
                'video.story', 0, 0, 150,
                'content://media/external/video/media/42',
                'content://media/external/downloads/43',
                '{"durationMs":1234,"attributionManifestComplete":true}'
            )
        """.trimIndent())
        legacy.execSQL("PRAGMA user_version = 6")
        legacy.close()

        val migrated = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).addMigrations(JarvisTaskDatabase.MIGRATION_6_7).build()

        try {
            val task = migrated.taskDao().findById("v6-video-task")!!
            assertEquals("Üretilmiş videoyu oynat", task.goal)
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, task.status)
            assertEquals(150L, task.planAcceptedAt)
            assertEquals("content://media/external/video/media/42", task.videoUri)
            assertEquals("content://media/external/downloads/43", task.captionsUri)
            assertEquals(
                """{"durationMs":1234,"attributionManifestComplete":true}""",
                task.videoArtifactMetadataJson
            )

            val journalTableCount = migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'jarvis_device_action_events'"
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(1, journalTableCount)

            migrated.taskDao().insertDeviceActionEvent(
                DeviceActionEventEntity(
                    id = "v6-upgrade-unknown",
                    actionType = "sms",
                    outcomeCode = "UNKNOWN",
                    safeSummary = DeviceActionOutcomeCode.UNKNOWN.safeSummary,
                    createdAt = 300L
                )
            )
            assertEquals(
                "UNKNOWN",
                migrated.taskDao().findDeviceActionEventById("v6-upgrade-unknown")?.outcomeCode
            )
            migrated.taskDao().insertDeviceActionEvent(
                DeviceActionEventEntity(
                    id = "v6-upgrade-draft",
                    actionType = "sms",
                    outcomeCode = "DRAFT",
                    safeSummary = DeviceActionOutcomeCode.DRAFT.safeSummary,
                    createdAt = 301L
                )
            )
            assertEquals(
                "DRAFT",
                migrated.taskDao().findDeviceActionEventById("v6-upgrade-draft")?.outcomeCode
            )
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

}
