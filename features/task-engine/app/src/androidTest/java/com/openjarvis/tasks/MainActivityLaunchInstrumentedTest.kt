package com.openjarvis.tasks

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product acceptance: optional Accessibility and Overlay capabilities must not gate normal
 * Dashboard/video-archive launch. The emulator is intentionally left with both disabled.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchInstrumentedTest {
    /**
     * The smoke-test phase starts MainActivity before instrumentation. An Activity-backed
     * Compose rule can therefore attach after the prelaunched setContent call and see no roots.
     * Register the empty Compose rule first, then clear/relaunch MainActivity inside the test so
     * its Compose hierarchy is created while the test registry is already observing the process.
     */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun dashboardAndVideoArchiveRemainVisibleWithoutAccessibilityOrOverlay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val accessibilityComponent = ComponentName(
            context,
            JarvisAccessibilityService::class.java
        ).flattenToString()
        val accessibilityEnabled = enabledServices.split(':').any {
            it.equals(accessibilityComponent, ignoreCase = true)
        }

        assertFalse(
            "Acceptance emulator must keep JARVIS Accessibility disabled",
            accessibilityEnabled
        )
        assertFalse(
            "Acceptance emulator must keep Overlay permission denied",
            Settings.canDrawOverlays(context)
        )

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent)
        try {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("OPEN JARVIS").assertIsDisplayed()
            composeRule.onNodeWithText("Türkçe sesli video üretimi")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("JARVIS video arşivi")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            scenario.close()
        }
    }
}
