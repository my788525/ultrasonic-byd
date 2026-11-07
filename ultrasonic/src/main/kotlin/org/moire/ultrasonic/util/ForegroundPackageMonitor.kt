/*
 * ForegroundPackageMonitor.kt
 *
 * Optional supporting hint. Some BYD head units do not expose "usage access" in their
 * settings app, but the permission can still be granted once over wireless ADB:
 *
 *   adb shell appops set org.moire.ultrasonic GET_USAGE_STATS allow
 *
 * When that has been done this monitor names the app on top, which lets the camera gate
 * work even without the accessibility service. Nothing here asks the user for the
 * permission or blocks a feature when it is missing; it is simply skipped.
 */

package org.moire.ultrasonic.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

object ForegroundPackageMonitor {

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (all: Throwable) {
            false
        }
    }

    /** The package whose activity was most recently brought to the front, if known. */
    @Suppress("DEPRECATION")
    fun currentForegroundPackage(context: Context): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        return try {
            val events = manager.queryEvents(now - 15_000L, now)
            val event = UsageEvents.Event()
            var best: String? = null
            var bestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val foreground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (foreground && event.timeStamp > bestTime) {
                    bestTime = event.timeStamp
                    best = event.packageName
                }
            }
            best ?: manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 86_400_000L,
                now
            )?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (all: Throwable) {
            null
        }
    }
}
