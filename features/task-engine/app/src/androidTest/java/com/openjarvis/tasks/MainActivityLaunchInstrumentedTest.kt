package com.openjarvis.tasks

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the real launcher Activity reaches and keeps a foreground,
 * globally visible window. The test first returns the emulator to HOME so an
 * earlier SMS fallback test cannot leave Messages above the launcher.
 */
class MainActivityLaunchInstrumentedTest {
    private data class WindowSnapshot(
        val lifecycleState: Lifecycle.State,
        val attached: Boolean,
        val laidOut: Boolean,
        val shown: Boolean,
        val windowVisible: Boolean,
        val globalVisible: Boolean,
        val globalRectNonEmpty: Boolean,
        val windowFocus: Boolean,
        val finishing: Boolean,
        val rect: String
    ) {
        val isForegroundAndVisible: Boolean
            get() = lifecycleState == Lifecycle.State.RESUMED &&
                attached &&
                laidOut &&
                shown &&
                windowVisible &&
                globalVisible &&
                globalRectNonEmpty &&
                windowFocus &&
                !finishing

        fun describe(): String =
            "lifecycle=" + lifecycleState +
                ", attached=" + attached +
                ", laidOut=" + laidOut +
                ", shown=" + shown +
                ", windowVisible=" + windowVisible +
                ", globalVisible=" + globalVisible +
                ", globalRectNonEmpty=" + globalRectNonEmpty +
                ", windowFocus=" + windowFocus +
                ", finishing=" + finishing +
                ", rect=" + rect
    }

    @Test
    fun launcherActivityStartsAndRemainsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A preceding SMS fallback test may have left an external app on top.
        // Move to HOME before launching the actual app Activity.
        instrumentation.uiAutomation
            .executeShellCommand("input keyevent KEYCODE_HOME")
            .close()
        SystemClock.sleep(500)

        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName("com.openjarvis", "com.openjarvis.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // startActivitySync returns the exact Activity instance it launched;
        // unlike a class-wide monitor, this cannot accidentally inspect a stale
        // or previously-created MainActivity instance.
        val launched = instrumentation.startActivitySync(intent)
        assertTrue("Explicit launcher intent did not create MainActivity", launched is MainActivity)
        val activity = launched as MainActivity

        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var state = captureWindowSnapshot(instrumentation, activity)

            while (!state.isForegroundAndVisible && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
                state = captureWindowSnapshot(instrumentation, activity)
            }

            assertTrue(
                "MainActivity did not reach a foreground, globally visible RESUMED window within 10s (" +
                    state.describe() + ")",
                state.isForegroundAndVisible
            )

            // A transient first frame is not enough: keep the same Activity
            // foregrounded and visible for another two seconds.
            SystemClock.sleep(2_000)
            state = captureWindowSnapshot(instrumentation, activity)
            assertTrue(
                "MainActivity did not remain foregrounded and globally visible (" +
                    state.describe() + ")",
                state.isForegroundAndVisible
            )
        } finally {
            instrumentation.runOnMainSync {
                if (!activity.isFinishing) activity.finish()
            }
        }
    }

    private fun captureWindowSnapshot(
        instrumentation: Instrumentation,
        activity: MainActivity
    ): WindowSnapshot {
        var snapshot: WindowSnapshot? = null
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val globalRect = Rect()
            val globalVisible = decor.getGlobalVisibleRect(globalRect)
            snapshot = WindowSnapshot(
                lifecycleState = activity.lifecycle.currentState,
                attached = decor.isAttachedToWindow,
                laidOut = decor.isLaidOut,
                shown = decor.isShown,
                windowVisible = decor.windowVisibility == View.VISIBLE,
                globalVisible = globalVisible,
                globalRectNonEmpty = !globalRect.isEmpty,
                windowFocus = activity.hasWindowFocus(),
                finishing = activity.isFinishing,
                rect = globalRect.toShortString()
            )
        }
        return checkNotNull(snapshot) { "Could not capture MainActivity window state" }
    }
}
