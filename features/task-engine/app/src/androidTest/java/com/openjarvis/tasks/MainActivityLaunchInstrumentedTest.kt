package com.openjarvis.tasks

import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

        composeRule.waitForIdle()
        composeRule.onNodeWithText("OPEN JARVIS").assertIsDisplayed()
        composeRule.onNodeWithText("Türkçe sesli video üretimi")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("JARVIS video arşivi")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
