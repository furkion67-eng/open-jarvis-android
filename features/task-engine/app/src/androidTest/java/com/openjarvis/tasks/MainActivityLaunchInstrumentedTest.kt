package com.openjarvis.tasks

import android.content.ComponentName
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CI-only launcher contract:
 * - the real MainActivity must remain foregrounded and visible;
 * - ordinary Dashboard/video playback must not be blocked by Accessibility or Overlay;
 * - the embedded player panel must be part of the Dashboard composition.
 */
class MainActivityLaunchInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherActivityStartsAndRemainsVisible() {
        val activity = composeRule.activity
        composeRule.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var state = captureWindowSnapshot(activity)

        while (!state.isForegroundAndVisible && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
            composeRule.waitForIdle()
            state = captureWindowSnapshot(activity)
        }

        assertTrue(
            "MainActivity did not reach a foreground, globally visible RESUMED window within 10s (" +
                state.describe() + ")",
            state.isForegroundAndVisible
        )

        SystemClock.sleep(2_000L)
        state = captureWindowSnapshot(activity)
        assertTrue(
            "MainActivity did not remain foregrounded and globally visible (" +
                state.describe() + ")",
            state.isForegroundAndVisible
        )
    }

    @Test
    fun dashboardAndEmbeddedVideoArchiveAreAvailableWithoutGlobalDevicePermissions() {
        val activity = composeRule.activity
        val enabledServices = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val accessibilityComponent = ComponentName(
            activity,
            JarvisAccessibilityService::class.java
        ).flattenToString()
        val accessibilityEnabled = enabledServices.split(':').any {
            it.equals(accessibilityComponent, ignoreCase = true)
        }

        // A fresh emulator must not silently grant either capability. The test
        // verifies ordinary app use without opening Android permission settings.
        assertFalse(
            "Accessibility must remain disabled for ordinary Dashboard/video playback",
            accessibilityEnabled
        )
        assertFalse(
            "Overlay must remain denied for ordinary Dashboard/video playback",
            Settings.canDrawOverlays(activity)
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Permission Required", useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("JARVIS video arşivi", useUnmergedTree = true)
            .assertExists()
    }

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
                attached && laidOut && shown && windowVisible &&
                globalVisible && globalRectNonEmpty && windowFocus && !finishing

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

    private fun captureWindowSnapshot(activity: MainActivity): WindowSnapshot {
        var snapshot: WindowSnapshot? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
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
                windowFocus = decor.hasWindowFocus(),
                finishing = activity.isFinishing,
                rect = globalRect.toShortString()
            )
        }
        return checkNotNull(snapshot)
    }
}
