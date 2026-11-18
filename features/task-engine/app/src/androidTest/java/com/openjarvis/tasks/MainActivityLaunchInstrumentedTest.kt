package com.openjarvis.tasks

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirms the launcher Activity reaches a visible window, not merely a live process. */
class MainActivityLaunchInstrumentedTest {
    @Test
    fun launcherActivityStartsAndRemainsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor("com.openjarvis.ui.MainActivity", null, false)
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName("com.openjarvis", "com.openjarvis.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            instrumentation.startActivitySync(intent)
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("MainActivity did not start", activity)
            instrumentation.waitForIdleSync()
            assertFalse("MainActivity is finishing", activity!!.isFinishing)
            assertTrue("MainActivity window is not visible", activity.window.decorView.isShown)
            Thread.sleep(2_000)
            assertFalse("MainActivity exited shortly after launch", activity.isFinishing)
            activity.finish()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
