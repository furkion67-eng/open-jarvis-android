package com.openjarvis.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separate database keeps the new task schema independent from legacy graphify.db migrations. */
@Database(entities = [JarvisTaskEntity::class, DeviceActionEventEntity::class], version = 7, exportSchema = false)
abstract class JarvisTaskDatabase : RoomDatabase() {
    abstract fun taskDao(): JarvisTaskDao

    companion object {
        /** Version 1 had no explicit plan-acceptance timestamp; old tasks fail closed. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE jarvis_tasks ADD COLUMN planAcceptedAt INTEGER")
            }
        }

        /** Deduplicate legacy request keys before enforcing the unique index. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "DELETE FROM jarvis_tasks WHERE rowid NOT IN " +
                        "(SELECT MIN(rowid) FROM jarvis_tasks GROUP BY idempotencyKey)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_jarvis_tasks_idempotencyKey " +
                        "ON jarvis_tasks (idempotencyKey)"
                )
            }
        }

        /** Convert legacy overloaded waiting states to their durable truthful states. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE jarvis_tasks SET status = 'not_executed', progressPercent = 0, " +
                        "currentStep = 'Yürütülmedi · yürütücü yok', " +
                        "approvalGranted = 0, approvalReference = NULL " +
                        "WHERE status = 'waiting_for_network' AND errorCode = 'EXECUTOR_NOT_CONFIGURED'"
                )
                database.execSQL(
                    "UPDATE jarvis_tasks SET status = 'interrupted', progressPercent = 0, " +
                        "currentStep = 'Güvenli kurtarma beklemesi', " +
                        "approvalGranted = 0, approvalReference = NULL " +
                        "WHERE status = 'waiting_for_network' AND errorCode = 'APP_RESTARTED'"
                )
            }
        }

        /** Failed is terminal but not completed; old 100% values were synthetic. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE jarvis_tasks SET progressPercent = 0 WHERE status = 'failed'"
                )
            }
        }

        /** Persist verified video and captions content URIs without disturbing existing tasks. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE jarvis_tasks ADD COLUMN videoUri TEXT")
                database.execSQL("ALTER TABLE jarvis_tasks ADD COLUMN captionsUri TEXT")
                database.execSQL("ALTER TABLE jarvis_tasks ADD COLUMN videoArtifactMetadataJson TEXT")
            }
        }

        /** Add a privacy-minimal journal for confirmed device-action outcomes. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS jarvis_device_action_events (
                        id TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        outcomeCode TEXT NOT NULL,
                        safeSummary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_jarvis_device_action_events_createdAt " +
                        "ON jarvis_device_action_events (createdAt)"
                )
            }
        }

        @Volatile
        private var instance: JarvisTaskDatabase? = null

        fun getInstance(context: Context): JarvisTaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JarvisTaskDatabase::class.java,
                "jarvis_tasks.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
        }

        /** Closes the singleton so instrumentation can verify the file-backed DB after a process-style reopen. */
        internal fun closeInstanceForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
