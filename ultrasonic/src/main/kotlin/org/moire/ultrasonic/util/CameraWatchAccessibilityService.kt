/*
 * CameraWatchAccessibilityService.kt
 *
 * Observes the top window of the head unit to find out whether the car's 360 / reversing
 * picture is showing. This is the only dependable observer available to a sideloaded app:
 * the camera view belongs to com.byd.avc (a system app) and the BYD Open Platform getters
 * are signature-protected (BYDAUTO_*_GET is refused to an ordinary app uid).
 *
 * The driver enables this service once, from the car's accessibility settings. It reads no
 * content, takes no action and sends nothing anywhere: it only forwards a boolean "a camera
 * window is on top" into CameraSignalBus, which hides the floating player and pauses music.
 */

package org.moire.ultrasonic.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

class CameraWatchAccessibilityService : AccessibilityService() {

    /** How often the window list is re-checked while this service is bound. */
    private val heartbeat = object : Runnable {
        override fun run() {
            // A long reversing manoeuvre must not outlive the 30 s signal lifetime. Only a
            // positive confirmation refreshes it; a missing window is left to the window
            // events and to the bus timeout, so a transient unreadable window is harmless.
            val hit = findLargeCameraWindow()
            if (hit != null) {
                CameraSignalBus.setCameraWindow(true, "camera window still on top: $hit")
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ask for interactive windows even if the head unit ignored the XML flag.
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        CameraSignalBus.accessibilityEnabled = true
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        Timber.i("Camera watch accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val eventPackage = event.packageName?.toString().orEmpty()
        val direct = CameraPackages.isCameraPackage(eventPackage)
        val window = if (direct) null else findLargeCameraWindow()
        val active = direct || window != null
        val reason = when {
            direct -> "camera window event from $eventPackage"
            window != null -> "camera window on top: $window"
            else -> "top window is $eventPackage"
        }
        CameraSignalBus.lastForegroundPackage = eventPackage.ifEmpty { CameraSignalBus.lastForegroundPackage }
        CameraSignalBus.setCameraWindow(active, reason)
    }

    override fun onInterrupt() {
        CameraSignalBus.setCameraWindow(false, "accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        CameraSignalBus.accessibilityEnabled = false
        CameraSignalBus.setCameraWindow(false, "accessibility service stopped")
        super.onDestroy()
    }

    /**
     * Confirms the camera view through the window list. The car's Image app is a persistent
     * system process, so a small always-present window must not count: only a window that
     * covers at least a quarter of the display is treated as the camera picture.
     */
    private fun findLargeCameraWindow(): String? {
        return try {
            val metrics = resources.displayMetrics
            val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
            val bounds = Rect()
            for (info in windows) {
                val root = try {
                    info.root
                } catch (all: Throwable) {
                    null
                }
                val pkg = root?.packageName?.toString()
                val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        info.title?.toString().orEmpty()
                    } catch (all: Throwable) {
                        ""
                    }
                } else {
                    ""
                }
                val byPackage = CameraPackages.isCameraPackage(pkg)
                val byTitle = title.contains("360") ||
                    title.contains("AVM", ignoreCase = true) ||
                    title.contains("全景") ||
                    title.contains("影像")
                if (!byPackage && !byTitle) continue
                info.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height()
                if (area < screenArea / 4) continue
                return (pkg ?: title).ifEmpty { "camera window" }
            }
            null
        } catch (all: Throwable) {
            Timber.w(all, "Could not inspect the window list")
            null
        }
    }

    companion object {

        private const val HEARTBEAT_MS = 5_000L

        /** Whether the driver has switched this service on in the car's settings. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, CameraWatchAccessibilityService::class.java)
            val enabled = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
            } catch (all: Throwable) {
                return false
            }
            val wanted = setOf(component.flattenToString(), component.flattenToShortString())
            return enabled.split(':').any { it in wanted } ||
                enabled.contains("CameraWatchAccessibilityService")
        }
    }
}
