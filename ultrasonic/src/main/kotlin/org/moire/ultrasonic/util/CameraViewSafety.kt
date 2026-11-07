/*
 * CameraViewSafety.kt
 *
 * Shared state for the "a camera view is on the screen" gate.
 *
 * The BYD 360 / reversing picture is drawn by the car's own Image app (com.byd.avc) as a
 * system window, and the BYD Open Platform getters are signature-protected, so a
 * sideloaded app cannot read the car state directly. The dependable observer is an
 * AccessibilityService (CameraWatchAccessibilityService), enabled once by the driver.
 * This bus collects that observer plus two optional cheaper hints: usage access when
 * ADB granted it, and the running-process table.
 *
 * A previous release watched a tiny overlay SurfaceView being reclaimed. That only happens
 * to an Activity's own drawing surface; a Service-owned overlay window is never reclaimed
 * by the camera view, so the sensor never fired. It has been removed.
 */

package org.moire.ultrasonic.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/** The packages that own the car's camera picture. Kept narrow: navigation must never match. */
object CameraPackages {

    private val exact = setOf(
        "com.byd.avc",
        "com.byd.autovideo",
        "com.byd.auto.avc",
        "com.byd.avm",
        "com.byd.panorama",
        "com.byd.surroundview",
        "com.byd.camera"
    )

    private val hints = arrayOf(
        "panorama", "surroundview", "aroundview", "reversing", "reverseview",
        "avm", "autovideo", "byd.avc"
    )

    private val navigationPrefixes = arrayOf(
        "com.autonavi.", "com.amap.", "com.baidu.navi", "com.google.android.apps.maps"
    )

    fun isCameraPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (navigationPrefixes.any { pkg.startsWith(it, ignoreCase = true) }) return false
        val lower = pkg.lowercase()
        if (lower in exact) return true
        if (lower.startsWith("com.byd.") && hints.any { lower.contains(it) }) return true
        return hints.any { lower.contains(it) }
    }
}

/**
 * The combined camera-view state.
 *
 * Listeners are notified on the main thread and only on a real change, so the reverse gate
 * is not engaged again and again while the camera stays up. Every signal is a heartbeat:
 * the bus re-evaluates after the longest lifetime, so a missed "camera closed" event can
 * never leave the floating window hidden forever.
 */
object CameraSignalBus {

    /** How long a window/process/API signal stays valid without being refreshed. */
    private const val WINDOW_SIGNAL_TTL_MS = 30_000L

    /** Grace period before a "no camera window" event releases the gate. */
    private const val RELEASE_DELAY_MS = 2_000L

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cameraWindowAt = 0L
    @Volatile private var foregroundCameraAt = 0L
    @Volatile private var bydApiAt = 0L
    @Volatile private var fallbackAt = 0L
    @Volatile private var lastPublished: Boolean? = null
    @Volatile private var releasePending = false

    /**
     * Level signal from the UsageStats ACTIVITY_RESUMED tail. Unlike the pulse signals above
     * it does not expire: BYDMate proved on DiLink that com.byd.avc stays the raw foreground
     * package for the whole camera view, with no events while it is up. A TTL here would show
     * the floating window again in the middle of a long reversing manoeuvre.
     */
    @Volatile private var cameraUsageActive = false

    /**
     * Level signal from the player's audio focus. Some head units take the audio focus for
     * the parking radar / camera cues. Off by default: navigation prompts can also take a
     * transient focus, which must not hide the window on every turn instruction.
     */
    @Volatile private var audioFocusActive = false

    /** The last signal that fired, shown in settings so a real car can be diagnosed. */
    @Volatile var lastReason: String = "none"
        private set

    /** Diagnostic state, refreshed by the signal source and the settings screen. */
    @Volatile var accessibilityEnabled = false
    @Volatile var usageAccessGranted = false
    @Volatile var bydApiAvailable = false
    @Volatile var lastForegroundPackage: String? = null

    /** The most recent audio-focus change, shown in settings so a real car can be diagnosed. */
    @Volatile var audioFocusLastEvent: String = "none"
        private set

    private val recheckRunnable = Runnable { publish() }
    private val releaseRunnable = Runnable {
        releasePending = false
        if (cameraWindowAt == 0L) return@Runnable
        cameraWindowAt = 0L
        note("camera window gone")
        publish()
    }

    fun addListener(listener: (Boolean) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        publish()
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun note(reason: String) {
        lastReason = reason
        Timber.i("Camera view signal: %s", reason)
    }

    /**
     * The AccessibilityService reports whether a camera window is on top.
     *
     * Entering is immediate; leaving is delayed briefly, because the head unit emits a
     * flurry of window events while the camera view comes up and a non-camera event in
     * the middle of that flurry must not show the floating window over the picture.
     */
    fun setCameraWindow(active: Boolean, reason: String) {
        if (active) {
            mainHandler.removeCallbacks(releaseRunnable)
            releasePending = false
            cameraWindowAt = System.currentTimeMillis()
            note(reason)
        } else if (cameraWindowAt != 0L && !releasePending) {
            // Only the first "no camera" event arms the release. A head unit emits many
            // unrelated window events; re-arming on each one would postpone recovery.
            releasePending = true
            note("camera window closing ($reason)")
            mainHandler.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
        }
        publish()
    }

    /** A foreground camera process seen through usage access or the process table. */
    fun noteForegroundCamera(pkg: String) {
        foregroundCameraAt = System.currentTimeMillis()
        note("foreground camera package $pkg")
        publish()
    }

    /** Kept for callers that report the process going away; the heartbeat also expires. */
    fun noteForegroundCameraGone() {
        if (foregroundCameraAt == 0L) return
        foregroundCameraAt = 0L
        note("foreground camera package gone")
        publish()
    }

    /**
     * Authoritative answer from the UsageStats event tail: com.byd.avc resumed (true) or a
     * different package resumed (false). This is a level, so it survives any number of polls
     * without new events. Other sources remain OR-ed in as supporting hints.
     */
    fun setCameraUsage(active: Boolean, reason: String) {
        if (active == cameraUsageActive && active) {
            // No change; avoid resetting the release timer on every 500 ms poll.
            return
        }
        cameraUsageActive = active
        if (active) {
            mainHandler.removeCallbacks(releaseRunnable)
            releasePending = false
            note(reason)
        } else {
            // The raw foreground package changed to a non-camera app: that is the
            // authoritative "camera gone" evidence (BYDMate CameraStateMonitor.publish).
            // Clear the supporting pulses too, otherwise their 30 s TTL would keep the
            // floating window hidden long after the picture has disappeared.
            cameraWindowAt = 0L
            foregroundCameraAt = 0L
            fallbackAt = 0L
            releasePending = false
            mainHandler.removeCallbacks(releaseRunnable)
            note(reason)
        }
        publish()
    }

    /**
     * Audio focus edge, zero permission and event driven. Only AUDIOFOCUS_LOSS_TRANSIENT is
     * treated as a candidate; ducking (navigation prompts) never hides the window.
     */
    fun setAudioFocus(active: Boolean, reason: String) {
        audioFocusLastEvent = reason
        val wanted = active && Settings.floatingCameraAudioFocus
        if (audioFocusActive == wanted) return
        audioFocusActive = wanted
        if (wanted) {
            note(reason)
        } else if (Settings.floatingCameraAudioFocus) {
            note("audio focus restored ($reason)")
        }
        publish()
    }

    /** A positive answer from the BYD Open Platform (only possible on a signed build). */
    fun noteBydApi(active: Boolean, reason: String) {
        if (active) {
            bydApiAt = System.currentTimeMillis()
            note(reason)
        } else if (bydApiAt != 0L) {
            bydApiAt = 0L
        }
        publish()
    }

    /**
     * A hit from the zero-permission scanner: a vehicle property / setting changed, the
     * head unit muted the media, or the window dump shows the camera window.
     */
    fun noteFallbackSignal(reason: String) {
        fallbackAt = System.currentTimeMillis()
        note(reason)
        publish()
    }

    /** Clears every signal; used when the service goes away. */
    fun reset() {
        cameraWindowAt = 0L
        foregroundCameraAt = 0L
        bydApiAt = 0L
        fallbackAt = 0L
        cameraUsageActive = false
        audioFocusActive = false
        lastPublished = null
        releasePending = false
        mainHandler.removeCallbacks(recheckRunnable)
        mainHandler.removeCallbacks(releaseRunnable)
    }

    fun isActive(now: Long = System.currentTimeMillis()): Boolean =
        cameraUsageActive || audioFocusActive ||
            fresh(cameraWindowAt, now) || fresh(foregroundCameraAt, now) ||
            fresh(bydApiAt, now) || fresh(fallbackAt, now)

    private fun fresh(at: Long, now: Long): Boolean = at > 0L && now - at < WINDOW_SIGNAL_TTL_MS

    private fun publish() {
        mainHandler.removeCallbacks(recheckRunnable)
        val active = isActive()
        if (active != lastPublished) {
            lastPublished = active
            for (listener in listeners) {
                mainHandler.post { listener(active) }
            }
            Timber.i("Camera view gate: %s", if (active) "hidden" else "shown")
        }
        if (active) mainHandler.postDelayed(recheckRunnable, WINDOW_SIGNAL_TTL_MS + 250L)
    }
}

/**
 * Reads the running-process table, which needs no permission, for the car's camera app
 * that has taken the foreground. On many head units the camera view is a system window
 * and never appears here; this is only a supporting hint.
 */
class CameraForegroundScanner(private val context: Context) {

    fun scan(): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val myUid = Process.myUid()
            val foreground = am.runningAppProcesses
                ?.filter {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.uid != myUid
                }
                ?.flatMap { it.pkgList?.toList().orEmpty() }
                ?: return null
            val found = foreground.firstOrNull { CameraPackages.isCameraPackage(it) }
            if (found != null) CameraSignalBus.lastForegroundPackage = found
            found
        } catch (all: Throwable) {
            null
        }
    }
}
