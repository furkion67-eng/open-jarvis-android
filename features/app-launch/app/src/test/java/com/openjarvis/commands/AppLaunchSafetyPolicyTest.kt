package com.openjarvis.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchSafetyPolicyTest {
    @Test
    fun foregroundProcessMayRequestLaunch() {
        assertTrue(
            AppLaunchVisibilityPolicy.mayStartActivity(AppLaunchProcessVisibility.FOREGROUND)
        )
    }

    @Test
    fun visibleProcessMayRequestLaunch() {
        assertTrue(
            AppLaunchVisibilityPolicy.mayStartActivity(AppLaunchProcessVisibility.VISIBLE)
        )
    }

    @Test
    fun foregroundServiceAloneDoesNotPermitActivityLaunch() {
        assertFalse(
            AppLaunchVisibilityPolicy.mayStartActivity(AppLaunchProcessVisibility.FOREGROUND_SERVICE)
        )
    }

    @Test
    fun backgroundAndUnknownProcessesFailClosed() {
        assertFalse(
            AppLaunchVisibilityPolicy.mayStartActivity(AppLaunchProcessVisibility.BACKGROUND)
        )
        assertFalse(
            AppLaunchVisibilityPolicy.mayStartActivity(AppLaunchProcessVisibility.UNKNOWN)
        )
    }

    @Test
    fun recognizesAndroidBackgroundActivityStartDenials() {
        assertTrue(
            AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                "BackgroundActivityStartDeniedException",
                "Activity start denied"
            )
        )
        assertTrue(
            AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                "SecurityException",
                "Background activity launch blocked"
            )
        )
        assertTrue(
            AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                "IllegalStateException",
                "background activity start not allowed"
            )
        )
    }

    @Test
    fun doesNotMislabelUnrelatedSecurityFailuresAsBackgroundRestrictions() {
        assertFalse(
            AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                "SecurityException",
                "Permission Denial: component is not exported"
            )
        )
    }
}
