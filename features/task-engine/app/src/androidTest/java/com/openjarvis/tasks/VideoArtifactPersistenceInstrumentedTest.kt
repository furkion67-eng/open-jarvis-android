package com.openjarvis.tasks

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoArtifactPersistenceInstrumentedTest {
    @Test
    fun verifiedMediaStoreUrisAndTypedMetadataSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "jarvis_video_artifact_reopen_test.db"
        context.deleteDatabase(databaseName)
        val metadata = VideoArtifactMetadata(
            durationMs = 54_583L,
            audioDurationMs = 53_900L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR",
            sceneCount = 5,
            sourceLabel = "instrumented verified fixture",
            attributionManifest = emptyList(),
            attributionManifestComplete = false
        )
        val videoUri = "content://media/external/video/media/123"
        val captionsUri = "content://media/external/downloads/456"
        val task = JarvisTaskEntity(
            id = "video-task-reopen",
            goal = "İstanbul hakkında video oluştur",
            status = JarvisTaskStatus.SUCCEEDED.storageValue,
            outputSummary = "MP4 ve SRT doğrulandı.",
            artifactRefs = encodeArtifactReferences(listOf(videoUri, captionsUri)),
            idempotencyKey = "video-task-reopen-key",
            videoUri = videoUri,
            captionsUri = captionsUri,
            videoArtifactMetadataJson = VideoArtifactMetadataCodec.encode(metadata)
        )

        val first = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        first.taskDao().insert(task)
        first.close()

        val reopened = Room.databaseBuilder(
            context,
            JarvisTaskDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
        try {
            val restored = reopened.taskDao().findById(task.id)
            assertNotNull(restored)
            assertEquals(videoUri, restored!!.videoUri)
            assertEquals(captionsUri, restored.captionsUri)
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, restored.status)
            assertEquals(metadata, VideoArtifactMetadataCodec.decode(restored.videoArtifactMetadataJson))
            assertTrue(restored.artifactRefs.contains(videoUri))
            assertTrue(restored.artifactRefs.contains(captionsUri))
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }
}
