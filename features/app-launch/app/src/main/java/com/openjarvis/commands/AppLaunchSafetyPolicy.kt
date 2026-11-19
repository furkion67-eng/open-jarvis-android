package com.openjarvis.commands

/**
 * Process visibility is deliberately stricter than "has a foreground service"
 * or "has SYSTEM_ALERT_WINDOW permission". Android 10+ background-activity
 * launch exemptions depend on an actually visible user-facing surface.
 */
internal enum class AppLaunchProcessVisibility {
    FOREGROUND,
    VISIBLE,
    FOREGROUND_SERVICE,
    BACKGROUND,
    UNKNOWN
}

internal object AppLaunchVisibilityPolicy {
    fun mayStartActivity(visibility: AppLaunchProcessVisibility): Boolean =
        visibility == AppLaunchProcessVisibility.FOREGROUND ||
            visibility == AppLaunchProcessVisibility.VISIBLE
}

internal object AppLaunchFailureClassifier {
    fun isBackgroundActivityStartDenied(className: String, message: String): Boolean {
        val normalizedMessage = message.lowercase()
        return className.contains("BackgroundActivityStartDenied", ignoreCase = true) ||
            normalizedMessage.contains("background activity start") ||
            normalizedMessage.contains("background activity launch") ||
            normalizedMessage.contains("background start")
    }
}
