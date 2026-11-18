package com.openjarvis.commands

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.ui.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchInstrumentedTest {
    @Test
    fun packageManagerLaunchIntentStartsTheKnownJarvisLauncherActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val hostActivity = instrumentation.startActivitySync(launcherIntent) as MainActivity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val testTarget = AppLaunchTarget(
            displayName = "JARVIS test",
            aliases = setOf("jarvis test"),
            packageNames = listOf(context.packageName)
        )

        try {
            val result = AppLaunchDispatcher(
                platform = AndroidAppLaunchPlatform(hostActivity),
                targets = listOf(testTarget)
            ).dispatch("jarvis test")

            assertTrue("Expected a launch request, got " + result, result is AppLaunchResult.LaunchRequested)
            val launched = monitor.waitForActivityWithTimeout(5_000)
            assertNotNull("PackageManager launch intent did not start MainActivity", launched)
            assertTrue("Unexpected Activity launched: " + launched?.javaClass?.name, launched is MainActivity)
        } finally {
            val launched = monitor.lastActivity
            instrumentation.runOnMainSync {
                if (launched != null && !launched.isFinishing) launched.finish()
                if (!hostActivity.isFinishing) hostActivity.finish()
            }
            instrumentation.removeMonitor(monitor)
        }
    }
}
