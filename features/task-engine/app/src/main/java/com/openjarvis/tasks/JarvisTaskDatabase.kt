package com.openjarvis.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separate database keeps the new task schema independent from legacy graphify.db migrations. */
@Database(entities = [JarvisTaskEntity::class], version = 2, exportSchema = false)
abstract class JarvisTaskDatabase : RoomDatabase() {
    abstract fun taskDao(): JarvisTaskDao

    companion object {
        /** Version 1 had no explicit plan-acceptance timestamp; old tasks fail closed. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE jarvis_tasks ADD COLUMN planAcceptedAt INTEGER")
            }
        }

        @Volatile
        private var instance: JarvisTaskDatabase? = null

        fun getInstance(context: Context): JarvisTaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JarvisTaskDatabase::class.java,
                "jarvis_tasks.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
