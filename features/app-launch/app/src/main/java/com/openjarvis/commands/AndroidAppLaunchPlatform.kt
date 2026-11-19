package com.openjarvis.commands

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/** Android PackageManager/launcher-intent adapter for the allowlisted dispatcher. */
class AndroidAppLaunchPlatform(private val context: Context) : AppLaunchPlatform {
    private val packageManager: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    override fun launch(packageName: String): AppLaunchAttempt {
        try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return AppLaunchAttempt.NotInstalled
        } catch (_: SecurityException) {
            return AppLaunchAttempt.Failed
        }

        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: SecurityException) {
            return AppLaunchAttempt.Failed
        } ?: return AppLaunchAttempt.NoLaunchActivity

        if (launchIntent.component == null) return AppLaunchAttempt.IntentNotResolvable

        if (launchIntent.resolveActivity(packageManager) == null) {
            return AppLaunchAttempt.IntentNotResolvable
        }

        if (!canStartActivityFromCurrentAppState()) {
            return AppLaunchAttempt.BackgroundRestricted
        }

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            AppLaunchAttempt.Started
        } catch (_: ActivityNotFoundException) {
            AppLaunchAttempt.IntentNotResolvable
        } catch (error: SecurityException) {
            if (AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                    error.javaClass.simpleName,
                    error.message.orEmpty()
                )
            ) {
                AppLaunchAttempt.BackgroundRestricted
            } else {
                AppLaunchAttempt.Failed
            }
        } catch (error: RuntimeException) {
            if (AppLaunchFailureClassifier.isBackgroundActivityStartDenied(
                    error.javaClass.simpleName,
                    error.message.orEmpty()
                )
            ) {
                AppLaunchAttempt.BackgroundRestricted
            } else {
                AppLaunchAttempt.Failed
            }
        }
    }

    private fun canStartActivityFromCurrentAppState(): Boolean =
        AppLaunchVisibilityPolicy.mayStartActivity(currentProcessVisibility())

    @Suppress("DEPRECATION")
    private fun currentProcessVisibility(): AppLaunchProcessVisibility {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return AppLaunchProcessVisibility.UNKNOWN
        val process = manager.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?: return AppLaunchProcessVisibility.UNKNOWN

        return when (process.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                AppLaunchProcessVisibility.FOREGROUND
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                AppLaunchProcessVisibility.VISIBLE
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                AppLaunchProcessVisibility.FOREGROUND_SERVICE
            else -> AppLaunchProcessVisibility.BACKGROUND
        }
    }
}
