package com.openjarvis.commands

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

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
        } catch (_: SecurityException) {
            AppLaunchAttempt.BackgroundRestricted
        } catch (error: RuntimeException) {
            if (isBackgroundStartDenial(error)) AppLaunchAttempt.BackgroundRestricted
            else AppLaunchAttempt.Failed
        }
    }

    private fun canStartActivityFromCurrentAppState(): Boolean {
        if (context is Activity && !context.isFinishing && !context.isDestroyed) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Settings.canDrawOverlays(context)) return true

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val process = manager.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?: return false

        // A foreground service alone is not treated as a visible activity.
        return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun isBackgroundStartDenial(error: RuntimeException): Boolean {
        val className = error.javaClass.simpleName
        val message = error.message.orEmpty().lowercase()
        return className.contains("BackgroundActivityStartDenied") ||
            message.contains("background activity start") ||
            message.contains("background start")
    }
}
