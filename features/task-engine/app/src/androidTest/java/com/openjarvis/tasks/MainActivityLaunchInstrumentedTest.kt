package com.openjarvis.tasks

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirms the launcher Activity reaches and keeps an attached, visible window. */
class MainActivityLaunchInstrumentedTest {
    @Test
    fun launcherActivityStartsAndRemainsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor("com.openjarvis.ui.MainActivity", null, false)
        var activity: android.app.Activity? = null
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName("com.openjarvis", "com.openjarvis.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            instrumentation.startActivitySync(intent)
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("MainActivity did not start", activity)

            val launchedActivity = activity!!
            val deadline = SystemClock.uptimeMillis() + 10_000
            var visible = false
            var windowState = "not inspected"

            // Activity launch completion does not guarantee that WindowManager has
            // attached and drawn the decor view. Poll on the main thread instead
            // of asserting isShown immediately after waitForIdleSync().
            while (SystemClock.uptimeMillis() < deadline && !visible) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val decor = launchedActivity.window.decorView
                    visible = !launchedActivity.isFinishing &&
                        decor.isAttachedToWindow &&
                        decor.isShown &&
                        decor.windowVisibility == View.VISIBLE
                    windowState = "attached=${decor.isAttachedToWindow}, " +
                        "shown=${decor.isShown}, visibility=${decor.windowVisibility}, " +
                        "windowFocus=${launchedActivity.hasWindowFocus()}, " +
                        "finishing=${launchedActivity.isFinishing}"
                }
                if (!visible) SystemClock.sleep(100)
            }

            assertTrue(
                "MainActivity window did not become visible within 10s ($windowState)",
                visible
            )

            // Ensure it remains visible after the initial launch transition.
            SystemClock.sleep(2_000)
            instrumentation.runOnMainSync {
                val decor = launchedActivity.window.decorView
                windowState = "attached=${decor.isAttachedToWindow}, " +
                    "shown=${decor.isShown}, visibility=${decor.windowVisibility}, " +
                    "windowFocus=${launchedActivity.hasWindowFocus()}, " +
                    "finishing=${launchedActivity.isFinishing}"
                visible = !launchedActivity.isFinishing &&
                    decor.isAttachedToWindow &&
                    decor.isShown &&
                    decor.windowVisibility == View.VISIBLE
            }
            assertFalse("MainActivity is finishing ($windowState)", launchedActivity.isFinishing)
            assertTrue("MainActivity did not remain visible ($windowState)", visible)
        } finally {
            activity?.finish()
            instrumentation.removeMonitor(monitor)
        }
    }
}
