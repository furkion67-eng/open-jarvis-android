package com.openjarvis.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.VideoView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.tasks.JarvisTaskDatabase
import com.openjarvis.tasks.JarvisTaskRepository
import com.openjarvis.tasks.JarvisTaskStatus
import com.openjarvis.tasks.VideoArtifactMetadataCodec
import com.openjarvis.ui.MainActivity
import com.openjarvis.tasks.VIDEO_TASK_EXECUTION_MODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product-level player acceptance on the real extracted-host MainActivity.
 *
 * Unlike VideoArtifactPlayerInstrumentedTest's isolated DashboardPlayerTestActivity, this test
 * launches the actual app launcher Activity, waits for the usable Dashboard (not merely a drawn
 * splash), creates a video through the production task repository/executor, selects its archive
 * row and verifies playback advances inside the real Dashboard without ACTION_VIEW.
 */
@RunWith(AndroidJUnit4::class)
class VideoArtifactPlayerMainActivityInstrumentedTest {
    @Test
    fun realMainActivityShowsDashboardAndPlaysProductionArchiveArtifactInApp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        var activity: Activity? = null
        var taskId: String? = null
        var videoUri: String? = null
        var captionsUri: String? = null
        var thumbnailUri: String? = null
        var monitor: Instrumentation.ActivityMonitor? = null

        try {
            withContext(Dispatchers.IO) { JarvisTaskDatabase.closeInstanceForTesting() }
            val launchStartedAt = SystemClock.elapsedRealtime()
            monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW), null, false)
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            val firstFrameMs = awaitFirstDrawnFrame(
                activity = activity!!,
                launchStartedAt = launchStartedAt,
                timeoutMs = DASHBOARD_READY_BUDGET_MS
            )
            val dashboardReadyMs = awaitDashboardReady(
                instrumentation = instrumentation,
                activity = activity!!,
                launchStartedAt = launchStartedAt,
                timeoutMs = DASHBOARD_READY_BUDGET_MS
            )
            assertTrue("The Dashboard must become usable within 20 seconds.", dashboardReadyMs <= DASHBOARD_READY_BUDGET_MS)
            assertTrue("The real MainActivity must own window focus after Dashboard readiness.", activity!!.window.decorView.hasWindowFocus())
            Log.i(
                ACCEPTANCE_TAG,
                "first_observed_draw_ms=$firstFrameMs dashboard_ready_ms=$dashboardReadyMs budget_ms=$DASHBOARD_READY_BUDGET_MS"
            )

            val repository = JarvisTaskRepository.getInstanceOnIo(context)
            val requestKey = "main-activity-player-" + UUID.randomUUID()
            val createdTaskId = repository.createTask(
                goal = "İstanbul'un Fethi hakkında video oluştur",
                idempotencyKey = requestKey
            )
            taskId = createdTaskId

            val database = withContext(Dispatchers.IO) { JarvisTaskDatabase.getInstance(context) }
            val preview = withContext(Dispatchers.IO) { database.taskDao().findById(createdTaskId) }
                ?: throw AssertionError("The production task preview must be persisted in Room.")
            assertEquals(JarvisTaskStatus.WAITING_FOR_USER.storageValue, preview.status)
            assertEquals(VIDEO_TASK_EXECUTION_MODE, preview.ownerAgent)
            assertTrue(preview.videoUri.isNullOrBlank())
            assertTrue(preview.captionsUri.isNullOrBlank())

            repository.acceptPlan(createdTaskId)

            val completed = withContext(Dispatchers.IO) { database.taskDao().findById(createdTaskId) }
                ?: throw AssertionError("The accepted video task must remain in Room.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
            assertTrue(completed.planAcceptedAt != null)
            assertTrue(completed.videoUri.orEmpty().startsWith("content://"))
            assertTrue(completed.captionsUri.orEmpty().startsWith("content://"))
            assertTrue(completed.videoArtifactMetadataJson.isNotBlank())
            videoUri = completed.videoUri
            captionsUri = completed.captionsUri
            val metadata = VideoArtifactMetadataCodec.decode(completed.videoArtifactMetadataJson)
            assertNotNull("Typed renderer metadata must be persisted with the task.", metadata)
            assertTrue(requireNotNull(metadata).isValid())
            assertTrue(requireNotNull(metadata).attributionManifestComplete)
            assertEquals("tr-TR", requireNotNull(metadata).narrationLocale)
            thumbnailUri = requireNotNull(metadata).thumbnailUri
            assertTrue(thumbnailUri.orEmpty().startsWith("content://"))
            assertEquals(3, JSONArray(completed.artifactRefs).length())
            val goal = completed.goal

            awaitAccessibleTextAndClick(
                instrumentation = instrumentation,
                text = goal,
                timeoutMs = 30_000L
            )
            awaitVideoView(activity!!, requireNotNull(videoUri), timeoutMs = 30_000L)
            awaitAccessibleTextAndClick(
                instrumentation = instrumentation,
                text = "Oynat",
                timeoutMs = 30_000L
            )

            val playedMs = awaitPlaybackProgress(
                instrumentation = instrumentation,
                activity = activity!!,
                expectedUri = requireNotNull(videoUri),
                timeoutMs = 30_000L
            )
            assertTrue("The real Dashboard VideoView must advance at least 500 ms.", playedMs >= 500)
            assertFalse(
                "Playing the archive artifact on MainActivity must not launch an external ACTION_VIEW player.",
                instrumentation.checkMonitorHit(requireNotNull(monitor), 1)
            )
            assertNotNull("The renderer's captions URI must remain persisted with the artifact.", captionsUri)
            assertNotNull("The renderer's thumbnail URI must remain persisted with the artifact.", thumbnailUri)
        } finally {
            activity?.let { launched ->
                instrumentation.runOnMainSync {
                    if (!launched.isFinishing) launched.finish()
                }
            }
            monitor?.let { instrumentation.removeMonitor(it) }

            withContext(Dispatchers.IO) {
                JarvisTaskDatabase.closeInstanceForTesting()
                val cleanupDatabase = JarvisTaskDatabase.getInstance(context)
                taskId?.let { cleanupDatabase.taskDao().deleteById(it) }
                JarvisTaskDatabase.closeInstanceForTesting()
            }
            listOf(videoUri, captionsUri, thumbnailUri).forEach { value ->
                if (!value.isNullOrBlank()) {
                    runCatching { context.contentResolver.delete(android.net.Uri.parse(value), null, null) }
                }
            }
        }
    }

    private fun awaitFirstDrawnFrame(
        activity: Activity,
        launchStartedAt: Long,
        timeoutMs: Long
    ): Long {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = launchStartedAt + timeoutMs
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val observer = decor.viewTreeObserver
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    drawn.countDown()
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                    }
                }
            }
            observer.addOnDrawListener(listener)
            decor.invalidate()
        }
        val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (!drawn.await(remainingMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("MainActivity did not draw its first frame within ${timeoutMs} ms.")
        }
        return SystemClock.elapsedRealtime() - launchStartedAt
    }

    private fun awaitDashboardReady(
        instrumentation: Instrumentation,
        activity: Activity,
        launchStartedAt: Long,
        timeoutMs: Long
    ): Long {
        val required = listOf(
            "OPEN JARVIS",
            "Türkçe sesli video üretimi",
            "JARVIS video arşivi"
        )
        val found = linkedSetOf<String>()
        val deadline = launchStartedAt + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            required.forEach { text ->
                if (containsVisibleText(root, text)) found += text
            }
            if (found.containsAll(required) && activity.window.decorView.hasWindowFocus()) {
                return SystemClock.elapsedRealtime() - launchStartedAt
            }
            scrollForward(root)
            SystemClock.sleep(100)
        }
        throw AssertionError(
            "Real Dashboard was not ready within ${timeoutMs} ms. Visible milestones: " +
                found.joinToString(", ") + "; required: " + required.joinToString(", ")
        )
    }

    private fun awaitAccessibleText(
        instrumentation: Instrumentation,
        text: String,
        timeoutMs: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (containsVisibleText(instrumentation.uiAutomation.rootInActiveWindow, text)) return
            scrollForward(instrumentation.uiAutomation.rootInActiveWindow)
            SystemClock.sleep(100)
        }
        throw AssertionError("Real MainActivity did not expose the expected text: $text")
    }

    private fun awaitAccessibleTextAndClick(
        instrumentation: Instrumentation,
        text: String,
        timeoutMs: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isEnabled &&
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return
            }
            scrollForward(root)
            SystemClock.sleep(100)
        }
        throw AssertionError("Could not find and click the real Dashboard control: $text")
    }

    private fun awaitVideoView(activity: Activity, expectedUri: String, timeoutMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var found = false
            instrumentation.runOnMainSync {
                found = findVideoView(activity.window.decorView)?.tag == expectedUri
            }
            if (found) return
            SystemClock.sleep(100)
        }
        throw AssertionError("The actual MainActivity Dashboard did not bind its VideoView to $expectedUri")
    }

    private fun awaitPlaybackProgress(
        instrumentation: Instrumentation,
        activity: Activity,
        expectedUri: String,
        timeoutMs: Long
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var position = 0
            instrumentation.runOnMainSync {
                val player = findVideoView(activity.window.decorView)
                if (player?.tag == expectedUri) position = player.currentPosition
            }
            if (position >= 500) return position
            SystemClock.sleep(100)
        }
        throw AssertionError("The embedded MainActivity player did not advance the persisted URI by 500 ms.")
    }

    private fun containsVisibleText(root: AccessibilityNodeInfo?, text: String): Boolean =
        root?.findAccessibilityNodeInfosByText(text).orEmpty().any { it.isVisibleToUser }

    private fun scrollForward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (root.isScrollable && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until root.childCount) {
            if (scrollForward(root.getChild(index))) return true
        }
        return false
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

    private companion object {
        const val DASHBOARD_READY_BUDGET_MS = 20_000L
        const val ACCEPTANCE_TAG = "JARVIS_ACCEPTANCE"
    }
}
