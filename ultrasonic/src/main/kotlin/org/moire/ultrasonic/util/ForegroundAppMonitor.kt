/*
 * ForegroundAppMonitor.kt
 *
 * Finds the app the driver is actually looking at, so the floating player can stay off every
 * screen that is not the navigation map (launcher, 360 surround view, reversing camera, ...).
 */

package org.moire.ultrasonic.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import timber.log.Timber

/**
 * A small, permission-tolerant foreground-app probe.
 *
 * The primary source is the usage-stats service, which needs the driver to grant "usage access"
 * once. Two fallbacks keep the feature usable on head units that withhold that permission:
 * the daily usage stats (also part of usage access) and the running-process table (no
 * permission at all, and usually good enough on a car head unit).
 *
 * When no source can name the foreground app, callers should leave the window visible rather
 * than silently breaking the floating player; the BYD reverse-safety gate is still there as a
 * second line of defence.
 */
object ForegroundAppMonitor {

    /**
     * Amap / Gaode Map. All of its phone, car and lite builds share this package prefix, so a
     * prefix match also covers head-unit builds whose exact package name differs. Deliberately
     * narrow: the driver asked for the window to appear *only* over the navigation map.
     */
    private val NAVIGATION_PREFIXES = arrayOf("com.autonavi.")

    /** Whether [pkg] is one of the navigation apps the floating window is allowed on. */
    fun isNavigationApp(pkg: String?): Boolean =
        pkg != null && NAVIGATION_PREFIXES.any { pkg.startsWith(it, ignoreCase = true) }

    /** True once the driver has granted this app "usage access" in the system settings. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (all: Throwable) {
            Timber.w(all, "Could not read the usage-access app-op")
            false
        }
    }

    /** Opens the system page where usage access is granted. Safe when no such page exists. */
    fun openUsageAccessSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (all: Throwable) {
        // Some head units reject the package URI; fall back to the plain settings page.
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (again: Throwable) {
            Timber.w(again, "No usage-access settings page on this device")
            false
        }
    }

    /** Returns the package on top of the screen, or null when the platform will not tell us. */
    fun foregroundPackage(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return runningProcessPackage(context)
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            lastForegroundEvent(usm)?.let { return it }
            mostRecentUsage(usm)?.let { return it }
        }
        return runningProcessPackage(context)
    }

    /**
     * The most recent "moved to foreground" event. This is the sharpest signal: it also sees a
     * camera or panorama app that takes the screen while Amap stays running behind it.
     */
    private fun lastForegroundEvent(usm: UsageStatsManager): String? = try {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - EVENT_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        var at = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.timeStamp >= at
            ) {
                at = event.timeStamp
                pkg = event.packageName
            }
        }
        pkg
    } catch (all: Throwable) {
        null
    }

    /**
     * Fallback for a navigation app that has been in the foreground longer than the event
     * window: the daily usage stats still name the app that was used most recently.
     */
    private fun mostRecentUsage(usm: UsageStatsManager): String? = try {
        val now = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now)
            ?.filter { it.lastTimeUsed > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    } catch (all: Throwable) {
        null
    }

    /** Last resort: the running-process table needs no permission on most head units. */
    private fun runningProcessPackage(context: Context): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val myUid = Process.myUid()
            am.runningAppProcesses
                ?.firstOrNull {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.uid != myUid
                }
                ?.pkgList
                ?.firstOrNull()
        } catch (all: Throwable) {
            null
        }
    }

    private const val EVENT_WINDOW_MS = 30 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
