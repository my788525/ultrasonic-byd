/*
 * ForegroundPackageMonitor.kt
 *
 * The primary camera-view detector on BYD DiLink head units.
 *
 * Validated against AndyShaman/BYDMate (a third-party app that actually runs on DiLink):
 * its CameraStateMonitor tails the UsageStats event stream for ACTIVITY_RESUMED of
 * com.byd.avc and publishes "camera on top" straight from that raw package name, because
 * "the camera surface may not be an a11y window". BYDMate also documents that DiLink has
 * NO accessibility settings UI and NO usage-access settings UI, so the GET_USAGE_STATS
 * appop has to be granted once over ADB:
 *
 *   adb shell appops set org.moire.ultrasonic GET_USAGE_STATS allow
 *
 * That command needs no settings screen, no root and no signature. Once granted it persists
 * across reboots, and this monitor becomes a level signal (camera on / camera off) instead
 * of a pulse that has to be refreshed every few seconds.
 *
 * Polling is incremental: only events after the last consumed timestamp are read, and when
 * there are no new events the previously observed foreground package is kept. A reversing
 * manoeuvre that lasts longer than the query window therefore never silently expires.
 */

package org.moire.ultrasonic.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import timber.log.Timber

object ForegroundPackageMonitor {

    /** BYDMate uses 500 ms; the floating window must drop within half a second of the 360 view. */
    private const val POLL_INTERVAL_MS = 500L

    /** Wide first window, to catch a camera ACTIVITY_RESUMED that fired before we started. */
    private const val INITIAL_LOOKBACK_MS = 5L * 60_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false
    @Volatile private var lastEventTs = 0L
    @Volatile private var lastForegroundPkg: String? = null
    @Volatile private var lastCameraActive = false

    /** Whether the GET_USAGE_STATS appop has been granted (ADB or the settings screen). */
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

    fun start(context: Context) {
        if (running) return
        val appContext = context.applicationContext
        running = true
        lastEventTs = 0L
        lastForegroundPkg = null
        lastCameraActive = false
        val t = HandlerThread("camera-usage-poll").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    poll(appContext)
                } catch (all: Throwable) {
                    Timber.w(all, "Camera usage poll failed")
                }
                handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
        Timber.i("Foreground package monitor started (500ms incremental UsageStats tail)")
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
        lastEventTs = 0L
        lastForegroundPkg = null
        if (lastCameraActive) {
            lastCameraActive = false
            CameraSignalBus.setCameraUsage(false, "usage monitor stopped")
        }
        Timber.i("Foreground package monitor stopped")
    }

    private fun poll(context: Context) {
        val granted = hasUsageAccess(context)
        CameraSignalBus.usageAccessGranted = granted
        if (!granted) {
            // Permission can be granted over ADB while the app is running. Rewind the cursor
            // so the first successful poll still looks back five minutes.
            lastEventTs = 0L
            if (lastCameraActive) {
                lastCameraActive = false
                CameraSignalBus.setCameraUsage(false, "usage access no longer granted")
            }
            return
        }

        val now = System.currentTimeMillis()
        val beginTs = if (lastEventTs == 0L) now - INITIAL_LOOKBACK_MS else lastEventTs + 1
        val latest = latestResumed(context, beginTs, now + 1)
        if (latest != null) {
            lastEventTs = latest.second
            lastForegroundPkg = latest.first
        } else if (lastEventTs == 0L) {
            // No events yet, but a baseline is needed so subsequent polls are incremental.
            lastEventTs = now
        }
        // BYDMate: "No new events => keep prior foreground (camera still on, etc.)".
        // A fixed short query window would drop the camera package after a few seconds and
        // fall back to queryUsageStats, which can name an unrelated app while reversing.
        val pkg = lastForegroundPkg
        CameraSignalBus.lastForegroundPackage = pkg
        val active = pkg != null && CameraPackages.isCameraPackage(pkg)
        if (active != lastCameraActive) {
            lastCameraActive = active
            CameraSignalBus.setCameraUsage(
                active,
                if (active) "camera activity resumed: $pkg" else "foreground is ${pkg ?: "unknown"}"
            )
        }
    }

    /** Newest ACTIVITY_RESUMED / MOVE_TO_FOREGROUND in [beginTs, endTs]. */
    @Suppress("DEPRECATION")
    private fun latestResumed(context: Context, beginTs: Long, endTs: Long): Pair<String, Long>? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val events = manager.queryEvents(beginTs, endTs)
            val ev = UsageEvents.Event()
            var bestTs = -1L
            var bestPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val foreground = ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (foreground && ev.timeStamp > bestTs) {
                    bestTs = ev.timeStamp
                    bestPkg = ev.packageName
                }
            }
            bestPkg?.let { it to bestTs }
        } catch (all: Throwable) {
            null
        }
    }
}
