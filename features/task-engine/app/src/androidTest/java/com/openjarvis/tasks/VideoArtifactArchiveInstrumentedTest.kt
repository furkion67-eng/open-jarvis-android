package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoArtifactArchiveInstrumentedTest {
    @Test
    fun twoSuccessfulArtifactsArePaginatedAndOlderSelectionSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "video-archive-" + UUID.randomUUID() + ".db"
        var database: JarvisTaskDatabase? = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).build()

        try {
            val dao = requireNotNull(database).taskDao()
            val newer = artifact("newer", updatedAt = 200L)
            val older = artifact("older", updatedAt = 100L)
            val failed = artifact("failed", updatedAt = 300L).copy(
                status = JarvisTaskStatus.FAILED.storageValue
            )
            val missingCaptions = artifact("missing-captions", updatedAt = 400L).copy(
                captionsUri = null
            )
            val nonContentUri = artifact("non-content-uri", updatedAt = 500L).copy(
                videoUri = "file:///tmp/not-playable.mp4"
            )
            dao.insert(newer)
            dao.insert(older)
            dao.insert(failed)
            dao.insert(missingCaptions)
            dao.insert(nonContentUri)

            val firstPage = dao.observeSuccessfulVideoArtifacts(
                succeededStatus = JarvisTaskStatus.SUCCEEDED.storageValue,
                limit = 1,
                offset = 0
            ).first()
            val secondPage = dao.observeSuccessfulVideoArtifacts(
                succeededStatus = JarvisTaskStatus.SUCCEEDED.storageValue,
                limit = 1,
                offset = 1
            ).first()

            assertEquals(listOf("newer"), firstPage.map { it.id })
            assertEquals(listOf("older"), secondPage.map { it.id })

            // A process-style database close/reopen must restore the selected older artifact,
            // including the exact paired content URIs and typed metadata payload.
            database?.close()
            database = null
            database = Room.databaseBuilder(
                context,
                JarvisTaskDatabase::class.java,
                databaseName
            ).build()

            val reopenedDao = requireNotNull(database).taskDao()
            val restored = reopenedDao.observeById("older").first()
            assertNotNull(restored)
            assertEquals(older.videoUri, restored?.videoUri)
            assertEquals(older.captionsUri, restored?.captionsUri)
            assertEquals(older.videoArtifactMetadataJson, restored?.videoArtifactMetadataJson)
            assertEquals(
                listOf("older"),
                reopenedDao.observeSuccessfulVideoArtifacts(
                    succeededStatus = JarvisTaskStatus.SUCCEEDED.storageValue,
                    limit = 1,
                    offset = 1
                ).first().map { it.id }
            )
        } finally {
            database?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun artifact(id: String, updatedAt: Long) = JarvisTaskEntity(
        id = id,
        goal = "Video " + id,
        status = JarvisTaskStatus.SUCCEEDED.storageValue,
        updatedAt = updatedAt,
        idempotencyKey = "archive-key-" + id,
        videoUri = "content://media/external/video/media/" + id,
        captionsUri = "content://media/external/file/" + id,
        videoArtifactMetadataJson = "{\"artifact\":\"" + id + "\"}"
    )
}
