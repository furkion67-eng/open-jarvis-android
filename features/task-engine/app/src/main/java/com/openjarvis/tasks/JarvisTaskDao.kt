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
}
