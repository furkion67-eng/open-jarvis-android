package com.openjarvis.tasks

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.openjarvis.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the actual launcher Activity reaches RESUMED and owns a laid-out,
 * globally visible window. ActivityScenario avoids asserting against a stale
 * ActivityMonitor instance while another Activity is on top.
 */
class MainActivityLaunchInstrumentedTest {
    @Test
    fun launcherActivityStartsAndRemainsVisible() {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName("com.openjarvis", "com.openjarvis.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var visible = false
            var windowState = "not inspected"

            // ActivityScenario.launch waits for the Activity lifecycle to reach
            // RESUMED. Continue polling until its decor is attached, laid out,
            // shown and has a non-empty global visible rectangle.
            while (SystemClock.uptimeMillis() < deadline && !visible) {
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val globalRect = Rect()
                    val globalVisible = decor.getGlobalVisibleRect(globalRect)
                    val lifecycleState = activity.lifecycle.currentState
                    visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
                        !activity.isFinishing &&
                        decor.isAttachedToWindow &&
                        decor.isLaidOut &&
                        decor.isShown &&
                        decor.windowVisibility == View.VISIBLE &&
                        globalVisible &&
                        !globalRect.isEmpty
                    windowState = "lifecycle=$lifecycleState, " +
                        "attached=${decor.isAttachedToWindow}, laidOut=${decor.isLaidOut}, " +
                        "shown=${decor.isShown}, visibility=${decor.windowVisibility}, " +
                        "globalVisible=$globalVisible, globalRect=$globalRect, " +
                        "windowFocus=${activity.hasWindowFocus()}, " +
                        "finishing=${activity.isFinishing}"
                }
                if (!visible) SystemClock.sleep(100)
            }

            assertTrue(
                "MainActivity did not reach a globally visible RESUMED window within 10s ($windowState)",
                visible
            )

            // A transient first frame is not enough: verify that the same
            // Activity remains resumed and globally visible after two seconds.
            SystemClock.sleep(2_000)
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val globalRect = Rect()
                val globalVisible = decor.getGlobalVisibleRect(globalRect)
                val lifecycleState = activity.lifecycle.currentState
                visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.isFinishing &&
                    decor.isAttachedToWindow &&
                    decor.isLaidOut &&
                    decor.isShown &&
                    decor.windowVisibility == View.VISIBLE &&
                    globalVisible &&
                    !globalRect.isEmpty
                windowState = "lifecycle=$lifecycleState, " +
                    "attached=${decor.isAttachedToWindow}, laidOut=${decor.isLaidOut}, " +
                    "shown=${decor.isShown}, visibility=${decor.windowVisibility}, " +
                    "globalVisible=$globalVisible, globalRect=$globalRect, " +
                    "windowFocus=${activity.hasWindowFocus()}, " +
                    "finishing=${activity.isFinishing}"
            }
            assertTrue("MainActivity did not remain globally visible ($windowState)", visible)
        } finally {
            scenario.close()
        }
    }
}
