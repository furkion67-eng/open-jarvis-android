package com.openjarvis.ui.player

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.VideoView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.tasks.JarvisTaskDatabase
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VIDEO_TASK_EXECUTION_MODE
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoArtifactPlayerInstrumentedTest {
    @Test
    fun preparationAndBufferingCallbacksExposeOnlyTruthfulPlaybackReadiness() {
        var state = VideoPlayerPreparationState.preparing()
        assertTrue(state.isLoading)
        assertFalse(state.canPlay)

        state = state.onPrepared()
        assertEquals(VideoPlayerPreparationState.Phase.READY, state.phase)
        assertFalse(state.isLoading)
        assertTrue(state.canPlay)

        state = state.onBufferingStarted()
        assertEquals(VideoPlayerPreparationState.Phase.BUFFERING, state.phase)
        assertTrue(state.isLoading)
        assertFalse(state.canPlay)

        state = state.onBufferingEnded()
        assertEquals(VideoPlayerPreparationState.Phase.READY, state.phase)
        assertFalse(state.isLoading)
        assertTrue(state.canPlay)
    }

    @Test
    fun mediaErrorDisablesPlaybackAndRetryReturnsToPreparing() {
        val failed = VideoPlayerPreparationState.preparing().onError("Video açılamadı")
        assertEquals(VideoPlayerPreparationState.Phase.FAILED, failed.phase)
        assertFalse(failed.isLoading)
        assertFalse(failed.canPlay)
        assertEquals("Video açılamadı", failed.errorMessage)

        val retry = failed.onRetry()
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, retry.phase)
        assertTrue(retry.isLoading)
        assertFalse(retry.canPlay)
        assertEquals(null, retry.errorMessage)
    }

    @Test
    fun bufferingEventsBeforePreparedCannotEnablePlayback() {
        val preparing = VideoPlayerPreparationState.preparing()
        val afterBufferStart = preparing.onBufferingStarted()
        assertEquals(VideoPlayerPreparationState.Phase.PREPARING, afterBufferStart.phase)
        assertTrue(afterBufferStart.isLoading)
        assertFalse(afterBufferStart.canPlay)
        assertEquals(preparing, afterBufferStart.onBufferingEnded())
    }

    @Test
    fun revokedOrUnreadableContentUriReturnsUnavailableForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val metadata = com.openjarvis.tasks.VideoArtifactMetadata(
            durationMs = 6_000L,
            width = 360,
            height = 640,
            frameRate = 24,
            hasAudio = true,
            narrationLocale = "tr-TR"
        )
        val state = VideoArtifactPlaybackValidator.inspect(
            context,
            "content://com.openjarvis.revoked/video/1",
            "content://com.openjarvis.revoked/captions/1",
            VideoArtifactMetadataCodec.encode(metadata)
        )
        assertTrue(state is PlaybackAvailability.Unavailable)
    }

    @Test
    fun productionCreateAcceptExecutorPersistsAndDashboardPlaysOlderArchiveArtifactAfterRoomReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val repository = JarvisTaskRepository.getInstance(context)
        val requestKey = "player-e2e-" + UUID.randomUUID().toString()
        var taskId: String? = null
        var videoUri: Uri? = null
        var captionsUri: Uri? = null
        var thumbnailUri: Uri? = null
        var olderArtifactId: String? = null
        var olderVideoUri: Uri? = null
        var olderCaptionsUri: Uri? = null
        var olderThumbnailUri: Uri? = null
        var playerActivity: DashboardPlayerTestActivity? = null
        var actionViewMonitor: Instrumentation.ActivityMonitor? = null

        try {
            // Use the production repository and its registered VideoStoryTaskExecutor. The plan
            // must be durably accepted before rendering; no direct renderer call or manual task
            // insertion is allowed in this end-to-end acceptance path.
            val createdTaskId = repository.createTask(
                goal = "İstanbul'un Fethi hakkında video oluştur",
                idempotencyKey = requestKey
            )
            taskId = createdTaskId

            val database = JarvisTaskDatabase.getInstance(context)
            val preview = database.taskDao().findById(createdTaskId)
                ?: throw AssertionError("Production repository must persist the task preview.")
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
            assertEquals(VIDEO_TASK_EXECUTION_MODE, preview.ownerAgent)
            assertEquals("local_plan_preview", preview.executionMode)
            assertEquals(null, preview.planAcceptedAt)
            assertTrue(preview.videoUri.isNullOrBlank())
            assertTrue(preview.captionsUri.isNullOrBlank())

            repository.acceptPlan(createdTaskId)

            val completed = database.taskDao().findById(createdTaskId)
                ?: throw AssertionError("Accepted production task must remain in Room.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
            assertEquals(VIDEO_TASK_EXECUTION_MODE, completed.executionMode)
            assertTrue(completed.planAcceptedAt != null)
            assertTrue(completed.outputSummary.orEmpty().contains("MP4 hazır"))

            val artifacts = JSONArray(completed.artifactRefs)
            assertEquals("The executor must persist MP4, SRT and thumbnail together.", 3, artifacts.length())
            val persistedVideoUri = Uri.parse(artifacts.getString(0))
            val persistedCaptionsUri = Uri.parse(artifacts.getString(1))
            val persistedThumbnailUri = Uri.parse(artifacts.getString(2))
            videoUri = persistedVideoUri
            captionsUri = persistedCaptionsUri
            thumbnailUri = persistedThumbnailUri
            assertEquals(persistedVideoUri.toString(), completed.videoUri)
            assertEquals(persistedCaptionsUri.toString(), completed.captionsUri)
            assertTrue(persistedVideoUri.toString().startsWith("content://"))
            assertTrue(persistedCaptionsUri.toString().startsWith("content://"))
            assertTrue(persistedThumbnailUri.toString().startsWith("content://"))

            val metadata = VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)
                ?: throw AssertionError("Production renderer metadata must be persisted.")
            assertTrue(metadata.isValid())
            assertTrue(metadata.attributionManifestComplete)
            assertEquals(5, metadata.sceneCount)
            assertEquals(5, metadata.attributionManifest.size)
            assertEquals((0..4).toList(), metadata.attributionManifest.map { it.sceneIndex }.sorted())
            assertTrue(metadata.attributionManifest.all { it.isValid() })
            assertEquals(5, metadata.claimSources.size)
            assertTrue(metadata.claimSources.all { it.isValid() })
            assertEquals("tr-TR", metadata.narrationLocale)
            assertEquals("tr-TR", metadata.captionLocale)
            assertTrue((metadata.hookDurationMs ?: 0L) in 1L..2_000L)
            assertTrue(!metadata.thumbnailAltText.isNullOrBlank())
            assertTrue(!metadata.attributionCredits.isNullOrBlank())
            assertTrue(!metadata.researchCredits.isNullOrBlank())
            assertTrue(!metadata.audioDescription.isNullOrBlank())
            assertFalse(metadata.backgroundMusicIncluded)
            assertEquals(persistedThumbnailUri.toString(), metadata.thumbnailUri)
            assertTrue(completed.outputSummary.orEmpty().contains(persistedVideoUri.toString()))
            assertTrue(completed.outputSummary.orEmpty().contains(persistedCaptionsUri.toString()))
            assertTrue(completed.outputSummary.orEmpty().contains(persistedThumbnailUri.toString()))

            // Verify the exact persisted MP4 is readable and contains both expected tracks.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, persistedVideoUri, null)
                val mimeTypes = (0 until extractor.trackCount).mapNotNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }
                assertTrue("MP4 must contain H.264 video.", mimeTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC))
                assertTrue("MP4 must contain AAC audio.", mimeTypes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
                val audioTrack = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                }
                extractor.selectTrack(audioTrack)
                assertTrue(
                    "AAC sample from the persisted MediaStore URI must be readable.",
                    extractor.readSampleData(ByteBuffer.allocate(2 * 1024 * 1024), 0) > 0
                )
            } finally {
                extractor.release()
            }

            val captionsText = context.contentResolver.openInputStream(persistedCaptionsUri)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val cueBlocks = captionsText.trim().split(Regex("\\r?\\n\\s*\\r?\\n"))
            assertEquals("The renderer must persist one hook cue and five scene cues.", 6, cueBlocks.size)
            assertTrue(cueBlocks.all { it.contains("-->") })

            val thumbnailHeader = context.contentResolver.openInputStream(persistedThumbnailUri)!!.use { input ->
                ByteArray(3).also { header ->
                    var offset = 0
                    while (offset < header.size) {
                        val count = input.read(header, offset, header.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    assertEquals("Thumbnail must be a complete JPEG header.", 3, offset)
                }
            }
            assertEquals(0xff, thumbnailHeader[0].toInt() and 0xff)
            assertEquals(0xd8, thumbnailHeader[1].toInt() and 0xff)
            assertEquals(0xff, thumbnailHeader[2].toInt() and 0xff)

            val initialAvailability = VideoArtifactPlaybackValidator.inspect(
                context,
                persistedVideoUri.toString(),
                persistedCaptionsUri.toString(),
                completed.videoArtifactMetadataJson
            )
            assertTrue("The persisted MP4/SRT pair must pass the same validator used by the Dashboard.", initialAvailability is PlaybackAvailability.Ready)

            // Duplicate the real renderer-produced bytes into distinct app-owned MediaStore
            // rows. This creates a genuinely older, independently playable archive item without
            // fabricating an MP4 fixture or invoking the renderer twice.
            val olderId = "older-archive-" + UUID.randomUUID()
            olderArtifactId = olderId
            olderVideoUri = copyContentUriToMediaStore(
                context,
                persistedVideoUri,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "jarvis-older-" + UUID.randomUUID() + ".mp4",
                "video/mp4",
                Environment.DIRECTORY_MOVIES + "/JARVIS"
            )
            olderCaptionsUri = copyContentUriToMediaStore(
                context,
                persistedCaptionsUri,
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "jarvis-older-" + UUID.randomUUID() + ".srt",
                "application/x-subrip",
                Environment.DIRECTORY_DOWNLOADS + "/JARVIS"
            )
            olderThumbnailUri = copyContentUriToMediaStore(
                context,
                persistedThumbnailUri,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "jarvis-older-" + UUID.randomUUID() + ".jpg",
                "image/jpeg",
                Environment.DIRECTORY_PICTURES + "/JARVIS"
            )
            val olderMetadataJson = VideoArtifactMetadataCodec.encode(
                metadata.copy(thumbnailUri = requireNotNull(olderThumbnailUri).toString())
            )
            val olderArtifact = completed.copy(
                id = olderId,
                goal = "Older archive acceptance fixture",
                outputSummary = "Older archive acceptance fixture",
                artifactRefs = JSONArray()
                    .put(olderVideoUri.toString())
                    .put(olderCaptionsUri.toString())
                    .put(olderThumbnailUri.toString())
                    .toString(),
                createdAt = completed.createdAt - 1L,
                updatedAt = completed.updatedAt - 1L,
                idempotencyKey = requestKey + "-older",
                videoUri = olderVideoUri.toString(),
                captionsUri = olderCaptionsUri.toString(),
                videoArtifactMetadataJson = olderMetadataJson
            )
            database.taskDao().insert(olderArtifact)
            assertTrue(
                "The duplicated older MP4/SRT pair must pass the production player validator.",
                VideoArtifactPlaybackValidator.inspect(
                    context,
                    olderVideoUri.toString(),
                    olderCaptionsUri.toString(),
                    olderMetadataJson
                ) is PlaybackAvailability.Ready
            )

            // Render the production player in an isolated test host. MainActivity's explicit
            // Accessibility/Overlay gate is validated separately by the launcher test; this
            // test must not bypass or auto-grant those production capabilities.
            playerActivity = launchDashboardPlayerHost()
            awaitDashboardPlayer(playerActivity!!, persistedVideoUri.toString())
            val firstPlayerActivity = playerActivity!!
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                firstPlayerActivity.recreate()
            }
            playerActivity = awaitDashboardPlayerHost(previous = firstPlayerActivity)
            val recreatedPlayer = awaitDashboardPlayer(playerActivity!!, persistedVideoUri.toString())
            assertEquals(persistedVideoUri.toString(), recreatedPlayer.tag)

            // Simulate process loss: close the Activity and production Room singleton, then
            // reopen the same on-disk database before launching a fresh host Activity.
            closeDashboardPlayerHost(playerActivity)
            playerActivity = null
            JarvisTaskDatabase.closeInstanceForTesting()
            val reopenedDatabase = JarvisTaskDatabase.getInstance(context)
            val restoredTask = reopenedDatabase.taskDao().findById(createdTaskId)
                ?: throw AssertionError("The production-created task must survive Room reopen.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, restoredTask.status)
            assertEquals(persistedVideoUri.toString(), restoredTask.videoUri)
            assertEquals(persistedCaptionsUri.toString(), restoredTask.captionsUri)
            assertTrue(restoredTask.artifactRefs.contains(persistedVideoUri.toString()))
            assertTrue(restoredTask.artifactRefs.contains(persistedCaptionsUri.toString()))
            assertTrue(restoredTask.artifactRefs.contains(persistedThumbnailUri.toString()))

            val restoredMetadata = VideoArtifactMetadataCodec.decode(restoredTask.videoArtifactMetadataJson)
                ?: throw AssertionError("Renderer metadata must survive Room reopen.")
            assertEquals(metadata, restoredMetadata)
            assertTrue(restoredMetadata.attributionManifestComplete)
            assertTrue(restoredMetadata.attributionManifest.all { it.isValid() })
            assertTrue(restoredMetadata.claimSources.all { it.isValid() })
            assertEquals(metadata.attributionCredits, restoredMetadata.attributionCredits)
            assertEquals(metadata.researchCredits, restoredMetadata.researchCredits)
            assertEquals(metadata.thumbnailUri, restoredMetadata.thumbnailUri)
            assertEquals(metadata.attributionManifest.first().sha256, restoredMetadata.attributionManifest.first().sha256)
            assertEquals(metadata.attributionManifest.first().licenseUrl, restoredMetadata.attributionManifest.first().licenseUrl)
            assertEquals(metadata.attributionManifest.first().cacheMetadata, restoredMetadata.attributionManifest.first().cacheMetadata)

            val restoredOlder = reopenedDatabase.taskDao().findById(requireNotNull(olderArtifactId))
                ?: throw AssertionError("The older archive artifact must survive Room reopen.")
            assertEquals(olderVideoUri.toString(), restoredOlder.videoUri)
            assertEquals(olderCaptionsUri.toString(), restoredOlder.captionsUri)
            assertEquals(
                olderThumbnailUri.toString(),
                VideoArtifactMetadataCodec.decode(restoredOlder.videoArtifactMetadataJson)?.thumbnailUri
            )

            val reopenedAvailability = VideoArtifactPlaybackValidator.inspect(
                context,
                restoredTask.videoUri,
                restoredTask.captionsUri,
                restoredTask.videoArtifactMetadataJson
            )
            assertTrue("The restored URI pair must remain playable after Room reopen.", reopenedAvailability is PlaybackAvailability.Ready)

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            actionViewMonitor = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_VIEW),
                null,
                false
            )
            playerActivity = launchDashboardPlayerHost()
            val restoredPlayer = awaitDashboardPlayer(playerActivity!!, persistedVideoUri.toString())
            assertEquals(videoUri.toString(), restoredPlayer.tag)

            // Select the older archive row through its accessible Compose button. The selected
            // item must bind its own distinct MediaStore URI to the production embedded VideoView.
            awaitAccessibleTextAndClick(
                playerActivity!!,
                "Older archive acceptance fixture",
                requireEnabled = true
            )
            val exactOlderVideoUri = requireNotNull(olderVideoUri).toString()
            val olderPlayer = awaitDashboardPlayer(playerActivity!!, exactOlderVideoUri)
            assertEquals(exactOlderVideoUri, olderPlayer.tag)

            // The selected older artifact must remain selected after Activity recreation.
            val selectedOlderActivity = playerActivity!!
            instrumentation.runOnMainSync { selectedOlderActivity.recreate() }
            playerActivity = awaitDashboardPlayerHost(previous = selectedOlderActivity)
            val recreatedOlderPlayer = awaitDashboardPlayer(playerActivity!!, exactOlderVideoUri)
            assertEquals(exactOlderVideoUri, recreatedOlderPlayer.tag)

            // The Oynat control is enabled only after the actual VideoView onPrepared callback.
            // Clicking it must advance the selected older URI inside JARVIS, not launch ACTION_VIEW.
            awaitAccessibleTextAndClick(playerActivity!!, "Oynat", requireEnabled = true)
            val playbackProgressMs = awaitPlaybackProgress(playerActivity!!)
            assertTrue(
                "The selected older archive item's embedded player must advance beyond 500 ms.",
                playbackProgressMs >= 500
            )
            assertFalse(
                "Selecting or playing an archived video must never launch an external ACTION_VIEW player.",
                instrumentation.checkMonitorHit(requireNotNull(actionViewMonitor), 1)
            )
        } finally {
            closeDashboardPlayerHost(playerActivity)
            JarvisTaskDatabase.closeInstanceForTesting()
            runCatching {
                val cleanupDatabase = JarvisTaskDatabase.getInstance(context)
                taskId?.let { cleanupDatabase.taskDao().deleteById(it) }
                olderArtifactId?.let { cleanupDatabase.taskDao().deleteById(it) }
            }
            JarvisTaskDatabase.closeInstanceForTesting()
            actionViewMonitor?.let { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
            listOf(
                videoUri,
                captionsUri,
                thumbnailUri,
                olderVideoUri,
                olderCaptionsUri,
                olderThumbnailUri
            ).forEach { uri ->
                if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    /**
     * Launch the dedicated Activity declared in the extracted host's debug-only app manifest.
     * The Activity belongs to the target app package, so instrumentation and player share the
     * target process and production Room/MediaStore context. No app-op, permission grant, or
     * production Accessibility/Overlay gate is bypassed.
     */
    private fun launchDashboardPlayerHost(): DashboardPlayerTestActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val intent = Intent(targetContext, DashboardPlayerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = instrumentation.startActivitySync(intent)
        return launched as? DashboardPlayerTestActivity
            ?: throw AssertionError("The target-app debug player host did not launch.")
    }

    private fun awaitDashboardPlayerHost(
        previous: DashboardPlayerTestActivity? = null
    ): DashboardPlayerTestActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var current: DashboardPlayerTestActivity? = null
            instrumentation.runOnMainSync {
                current = DashboardPlayerTestActivity.current
                    ?.takeIf { it !== previous && !it.isFinishing }
            }
            if (current != null) return current!!
            SystemClock.sleep(50)
        }
        throw AssertionError("The target-app debug embedded-player host did not start.")
    }

    private fun closeDashboardPlayerHost(activity: DashboardPlayerTestActivity?) {
        if (activity == null) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            if (!activity.isFinishing) activity.finish()
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (DashboardPlayerTestActivity.current !== activity) return
            SystemClock.sleep(50)
        }
        throw AssertionError("The embedded-player test host did not finish cleanly.")
    }

    private fun awaitDashboardPlayer(
        activity: DashboardPlayerTestActivity,
        expectedUri: String
    ): VideoView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var found: VideoView? = null
            instrumentation.runOnMainSync {
                if (!activity.isFinishing && DashboardPlayerTestActivity.current === activity) {
                    found = findVideoView(activity.window.decorView)
                        ?.takeIf { it.tag == expectedUri }
                }
            }
            if (found != null) return found!!
            SystemClock.sleep(100)
        }
        throw AssertionError("Production VideoArtifactPlayerPanel did not restore the persisted URI in the test host.")
    }

    private fun awaitPlaybackProgress(activity: DashboardPlayerTestActivity): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var progressMs = 0
            instrumentation.runOnMainSync {
                val player = findVideoView(activity.window.decorView)
                    ?: throw AssertionError("The production embedded VideoView disappeared.")
                // Require actual playback-time advancement, not merely an isPlaying flag.
                // A start request can be accepted before the decoder has rendered a frame.
                if (player.currentPosition >= 500) {
                    progressMs = player.currentPosition
                }
            }
            if (progressMs > 0) return progressMs
            SystemClock.sleep(100)
        }
        throw AssertionError("The production embedded player did not begin playback after the user play action.")
    }

    private fun copyContentUriToMediaStore(
        context: android.content.Context,
        source: Uri,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Uri {
        val resolver = context.contentResolver
        val destination = requireNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        ) { "A second app-owned MediaStore artifact could not be created." }

        try {
            requireNotNull(resolver.openInputStream(source)) {
                "The source artifact could not be read for the archive acceptance fixture."
            }.use { sourceStream ->
                requireNotNull(resolver.openOutputStream(destination, "w")) {
                    "The destination MediaStore artifact could not be opened."
                }.use { destinationStream ->
                    sourceStream.copyTo(destinationStream)
                }
            }
            val finalized = resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            check(finalized == 1) { "The copied MediaStore artifact was not finalized." }
            requireNotNull(resolver.openFileDescriptor(destination, "r")) {
                "The copied MediaStore artifact is not readable after finalization."
            }.use { }
            return destination
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun awaitAccessibleTextAndClick(
        activity: DashboardPlayerTestActivity,
        text: String,
        requireEnabled: Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (activity.isFinishing || DashboardPlayerTestActivity.current !== activity) {
                throw AssertionError("The embedded-player Activity changed while waiting for: " + text)
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val matches = root?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (match in matches) {
                var clickable: AccessibilityNodeInfo? = match
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && (!requireEnabled || clickable.isEnabled)) {
                    if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Could not find and click enabled Compose control: " + text)
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
