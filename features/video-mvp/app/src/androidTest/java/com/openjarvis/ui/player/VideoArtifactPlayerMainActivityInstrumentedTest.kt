package com.openjarvis.ui.player

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.graphics.Rect
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
        val activityRef = AtomicReference<Activity?>()
        var taskId: String? = null
        var videoUri: String? = null
        var captionsUri: String? = null
        var thumbnailUri: String? = null
        var monitor: Instrumentation.ActivityMonitor? = null
        var firstDrawCallbacks: Application.ActivityLifecycleCallbacks? = null
        val firstFrameElapsedMs = AtomicLong(-1L)
        val firstFrameDrawn = CountDownLatch(1)
        val application = context as Application

        try {
            withContext(Dispatchers.IO) { JarvisTaskDatabase.closeInstanceForTesting() }
            val launchStartedAt = SystemClock.elapsedRealtime()
            firstDrawCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(created: Activity, savedInstanceState: Bundle?) {
                    if (created !is MainActivity) return
                    activityRef.set(created)
                    // Register during Activity creation, before startActivitySync returns and
                    // before the first window traversal. Do not invalidate the view: that would
                    // measure a synthetic later draw rather than the actual cold-start frame.
                    val decor = created.window.decorView
                    val observer = decor.viewTreeObserver
                    check(observer.isAlive) { "MainActivity decor ViewTreeObserver is not alive at creation." }
                    observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            firstFrameElapsedMs.compareAndSet(
                                -1L,
                                SystemClock.elapsedRealtime() - launchStartedAt
                            )
                            firstFrameDrawn.countDown()
                            if (decor.viewTreeObserver.isAlive) {
                                decor.viewTreeObserver.removeOnDrawListener(this)
                            }
                        }
                    })
                }

                override fun onActivityStarted(created: Activity) = Unit
                override fun onActivityResumed(created: Activity) = Unit
                override fun onActivityPaused(created: Activity) = Unit
                override fun onActivityStopped(created: Activity) = Unit
                override fun onActivitySaveInstanceState(created: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(created: Activity) = Unit
            }
            application.registerActivityLifecycleCallbacks(requireNotNull(firstDrawCallbacks))
            monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW), null, false)
            activityRef.set(
                instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            )

            val firstFrameRemainingMs = (
                launchStartedAt + DASHBOARD_READY_BUDGET_MS - SystemClock.elapsedRealtime()
            ).coerceAtLeast(0L)
            assertTrue(
                "MainActivity did not draw its first frame within " + DASHBOARD_READY_BUDGET_MS + " ms.",
                firstFrameDrawn.await(firstFrameRemainingMs, TimeUnit.MILLISECONDS)
            )
            val firstFrameMs = firstFrameElapsedMs.get()
            assertTrue("The first MainActivity frame timestamp must be captured.", firstFrameMs >= 0L)
            val dashboardReadyMs = awaitDashboardReady(
                instrumentation = instrumentation,
                activity = requireNotNull(activityRef.get()),
                launchStartedAt = launchStartedAt,
                timeoutMs = DASHBOARD_READY_BUDGET_MS
            )
            // awaitDashboardReady traverses the Dashboard to find the archive milestone.
            // Reset every scrollable container before creating/locating the new archive row.
            scrollToDashboardTop(instrumentation)
            assertTrue("The Dashboard must become usable within 20 seconds.", dashboardReadyMs <= DASHBOARD_READY_BUDGET_MS)
            assertTrue(
                "The real MainActivity must own window focus after Dashboard readiness.",
                hasWindowFocusOnMain(instrumentation, requireNotNull(activityRef.get()))
            )
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

            // The production renderer performs network-backed asset retrieval and Turkish TTS.
            // Bound the real executor so an unavailable provider cannot hang instrumentation.
            withTimeout(PRODUCTION_RENDER_TIMEOUT_MS) {
                repository.acceptPlan(createdTaskId)
            }

            val completed = withContext(Dispatchers.IO) { database.taskDao().findById(createdTaskId) }
                ?: throw AssertionError("The accepted video task must remain in Room.")
            assertEquals(JarvisTaskStatus.SUCCEEDED.storageValue, completed.status)
            assertTrue(completed.planAcceptedAt != null)
            assertTrue(completed.videoUri.orEmpty().startsWith("content://"))
            assertTrue(completed.captionsUri.orEmpty().startsWith("content://"))
            val metadataJson = requireNotNull(completed.videoArtifactMetadataJson) {
                "A SUCCEEDED video task must persist typed artifact metadata."
            }
            assertTrue("Persisted video artifact metadata must not be blank.", metadataJson.isNotBlank())
            videoUri = completed.videoUri
            captionsUri = completed.captionsUri
            val metadata = VideoArtifactMetadataCodec.decode(metadataJson)
            assertNotNull("Typed renderer metadata must be persisted with the task.", metadata)
            assertTrue(requireNotNull(metadata).isValid())
            assertTrue(requireNotNull(metadata).attributionManifestComplete)
            assertEquals("tr-TR", requireNotNull(metadata).narrationLocale)
            thumbnailUri = requireNotNull(metadata).thumbnailUri
            assertTrue(thumbnailUri.orEmpty().startsWith("content://"))
            assertEquals(3, JSONArray(completed.artifactRefs).length())
            val goal = completed.goal

            scrollToDashboardTop(instrumentation)
            awaitArchiveRowAndClick(
                instrumentation = instrumentation,
                taskGoal = goal,
                timeoutMs = 30_000L
            )
            awaitVideoView(requireNotNull(activityRef.get()), requireNotNull(videoUri), timeoutMs = 30_000L)
            awaitAccessibleTextAndClick(
                instrumentation = instrumentation,
                text = "Oynat",
                timeoutMs = 30_000L
            )

            val playedMs = awaitPlaybackProgress(
                instrumentation = instrumentation,
                activity = requireNotNull(activityRef.get()),
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

            // Recreate the real host Activity after playback. The archive selection is
            // rememberSaveable and the artifact is durable in Room; the new Activity must
            // revalidate the same content URI and play it again without ACTION_VIEW.
            val activityBeforeRecreate = requireNotNull(activityRef.get())
            instrumentation.runOnMainSync { activityBeforeRecreate.recreate() }
            val recreatedActivity = awaitRecreatedMainActivity(
                instrumentation = instrumentation,
                activityRef = activityRef,
                previous = activityBeforeRecreate,
                timeoutMs = 20_000L
            )
            assertTrue(
                "The recreated MainActivity must regain window focus.",
                hasWindowFocusOnMain(instrumentation, recreatedActivity)
            )
            awaitVideoView(recreatedActivity, requireNotNull(videoUri), timeoutMs = 30_000L)
            awaitAccessibleTextAndClick(
                instrumentation = instrumentation,
                text = "Oynat",
                timeoutMs = 30_000L
            )
            val replayedMs = awaitPlaybackProgress(
                instrumentation = instrumentation,
                activity = recreatedActivity,
                expectedUri = requireNotNull(videoUri),
                timeoutMs = 30_000L
            )
            assertTrue(
                "The persisted archive artifact must play again after MainActivity recreation.",
                replayedMs >= 500
            )
            assertFalse(
                "Reopening the persisted artifact must remain inside JARVIS after Activity recreation.",
                instrumentation.checkMonitorHit(requireNotNull(monitor), 1)
            )
        } finally {
            activityRef.get()?.let { launched ->
                instrumentation.runOnMainSync {
                    if (!launched.isFinishing) launched.finish()
                }
            }
            monitor?.let { instrumentation.removeMonitor(it) }
            firstDrawCallbacks?.let(application::unregisterActivityLifecycleCallbacks)

            withContext(NonCancellable + Dispatchers.IO) {
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

    private fun awaitRecreatedMainActivity(
        instrumentation: Instrumentation,
        activityRef: AtomicReference<Activity?>,
        previous: Activity,
        timeoutMs: Long
    ): Activity {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = activityRef.get()
            if (current != null && current !== previous && !current.isFinishing &&
                hasWindowFocusOnMain(instrumentation, current)
            ) {
                return current
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("MainActivity did not recreate and regain focus within ${timeoutMs} ms.")
    }

    private fun hasWindowFocusOnMain(instrumentation: Instrumentation, activity: Activity): Boolean {
        var hasFocus = false
        instrumentation.runOnMainSync {
            hasFocus = !activity.isFinishing && activity.window.decorView.hasWindowFocus()
        }
        return hasFocus
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
            if (found.containsAll(required)) {
                if (hasWindowFocusOnMain(instrumentation, activity)) {
                    return SystemClock.elapsedRealtime() - launchStartedAt
                }
                // All Dashboard milestones have been seen; wait for focus without scrolling
                // past the archive section and making later row selection nondeterministic.
                SystemClock.sleep(100)
            } else {
                scrollForward(root)
                SystemClock.sleep(100)
            }
        }
        throw AssertionError(
            "Real Dashboard was not ready within ${timeoutMs} ms. Visible milestones: " +
                found.joinToString(", ") + "; required: " + required.joinToString(", ")
        )
    }

    private fun scrollToDashboardTop(instrumentation: Instrumentation) {
        val deadline = SystemClock.elapsedRealtime() + SCROLL_RESET_BUDGET_MS
        var actions = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root == null) {
                SystemClock.sleep(50)
                continue
            }
            if (!scrollBackward(root)) return
            actions++
            if (actions >= MAX_SCROLL_RESET_ACTIONS) {
                throw AssertionError("Dashboard scroll reset exceeded $MAX_SCROLL_RESET_ACTIONS bounded actions.")
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Dashboard scroll position could not be restored to the top within the bounded reset budget.")
    }

    private fun scrollBackward(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (root.isScrollable && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        for (index in 0 until root.childCount) {
            if (scrollBackward(root.getChild(index))) return true
        }
        return false
    }

    private fun awaitArchiveRowAndClick(
        instrumentation: Instrumentation,
        taskGoal: String,
        timeoutMs: Long
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val archiveHeader = root?.findAccessibilityNodeInfosByText("JARVIS video arşivi")
                .orEmpty()
                .filter { it.isVisibleToUser }
                .maxByOrNull { node -> Rect().also(node::getBoundsInScreen).bottom }
            if (root != null && archiveHeader != null) {
                val headerBounds = Rect().also(archiveHeader::getBoundsInScreen)
                val archiveRows = root.findAccessibilityNodeInfosByText(taskGoal)
                    .orEmpty()
                    .filter { node ->
                        if (!node.isVisibleToUser) return@filter false
                        val bounds = Rect().also(node::getBoundsInScreen)
                        bounds.top >= headerBounds.bottom
                    }
                for (node in archiveRows) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) {
                        clickable = clickable.parent
                    }
                    if (clickable != null && clickable.isEnabled &&
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) return
                }
            }
            scrollForward(root)
            SystemClock.sleep(100)
        }
        throw AssertionError(
            "Could not select the exact task row below the JARVIS video archive heading: $taskGoal"
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
        const val PRODUCTION_RENDER_TIMEOUT_MS = 180_000L
        const val SCROLL_RESET_BUDGET_MS = 5_000L
        const val MAX_SCROLL_RESET_ACTIONS = 64
        const val ACCEPTANCE_TAG = "JARVIS_ACCEPTANCE"
    }
}
