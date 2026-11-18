package com.openjarvis.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Separate database keeps the new task schema independent from legacy graphify.db migrations. */
@Database(entities = [JarvisTaskEntity::class], version = 1, exportSchema = false)
abstract class JarvisTaskDatabase : RoomDatabase() {
    abstract fun taskDao(): JarvisTaskDao

    companion object {
        @Volatile
        private var instance: JarvisTaskDatabase? = null

        fun getInstance(context: Context): JarvisTaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JarvisTaskDatabase::class.java,
                "jarvis_tasks.db"
            ).build().also { instance = it }
        }
    }
}
