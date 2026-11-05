/*
 * CameraViewSafety.kt
 *
 * Zero-permission detection of the BYD 360 panorama / reversing camera view.
 *
 * The head unit does not expose "usage access" to a sideloaded app, so the window on top
 * cannot be named that way. This file follows the approach that already works in the
 * fntv BYD fork: when the camera view takes the screen the platform reclaims the app's
 * drawing surface. A tiny, transparent overlay window with a SurfaceView is kept alive for
 * the whole lifetime of the floating-player service; its SurfaceHolder callbacks report
 * exactly that reclaim, with no permission and no polling of other apps' activities.
 *
 * Every transient signal expires by itself (SIGNAL_TIMEOUT_MS). A camera view that is
 * missed, or a surface that is reclaimed for an unrelated reason, can therefore never hide
 * the floating window forever.
 */

package org.moire.ultrasonic.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The shared state of every camera-view signal.
 *
 * Listeners are notified on the main thread and only on a real change, so the reverse gate
 * is not engaged again and again while the camera stays up.
 */
object CameraSignalBus {

    /** How long a transient signal may hide the floating window without being refreshed. */
    private const val SIGNAL_TIMEOUT_MS = 12_000L

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var surfaceLostAt = 0L
    @Volatile private var windowHiddenAt = 0L
    @Volatile private var foregroundCameraAt = 0L
    @Volatile private var lastPublished: Boolean? = null

    /** The last signal that fired, shown in settings so a real car can be diagnosed. */
    @Volatile var lastReason: String = "none"
        private set

    private val recheckRunnable = Runnable { publish() }

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

    fun noteSurfaceLost(reason: String) {
        surfaceLostAt = System.currentTimeMillis()
        note(reason)
        publish()
    }

    fun noteSurfaceRestored() {
        if (surfaceLostAt == 0L) return
        surfaceLostAt = 0L
        note("overlay surface restored")
        publish()
    }

    fun noteWindowHidden(reason: String) {
        windowHiddenAt = System.currentTimeMillis()
        note(reason)
        publish()
    }

    fun noteWindowShown() {
        if (windowHiddenAt == 0L) return
        windowHiddenAt = 0L
        note("overlay window visible again")
        publish()
    }

    fun noteForegroundCamera(pkg: String) {
        foregroundCameraAt = System.currentTimeMillis()
        note("foreground camera process $pkg")
        publish()
    }

    fun noteForegroundCameraGone() {
        if (foregroundCameraAt == 0L) return
        foregroundCameraAt = 0L
        note("foreground camera process gone")
        publish()
    }

    /** Clears every transient signal; used when the service goes away. */
    fun reset() {
        surfaceLostAt = 0L
        windowHiddenAt = 0L
        foregroundCameraAt = 0L
        lastPublished = null
        mainHandler.removeCallbacks(recheckRunnable)
    }

    fun isActive(now: Long = System.currentTimeMillis()): Boolean =
        fresh(surfaceLostAt, now) || fresh(windowHiddenAt, now) || fresh(foregroundCameraAt, now)

    private fun fresh(at: Long, now: Long): Boolean = at > 0L && now - at < SIGNAL_TIMEOUT_MS

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
        // Re-evaluate after the lifetime of the newest signal, so an unmatched "lost"
        // event can never leave the floating window hidden forever.
        if (active) mainHandler.postDelayed(recheckRunnable, SIGNAL_TIMEOUT_MS + 250L)
    }
}

/**
 * A 1x1 transparent overlay window whose only job is to watch the drawing surface.
 *
 * It stays attached while the floating-player service runs, independently of the floating
 * window itself, so hiding and rebuilding the player window cannot destroy the sensor.
 */
class CameraProbeWindow(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var probeRoot: ProbeRootView? = null
    private var attached = false
    private var stopping = false

    @SuppressLint("InflateParams")
    fun start() {
        if (attached) return
        // Fully qualified: this package also has its own Settings object.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            Timber.w("Camera probe skipped: no overlay permission")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val root = ProbeRootView(context)
        val surface = SurfaceView(context)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!stopping) CameraSignalBus.noteSurfaceRestored()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (!stopping) CameraSignalBus.noteSurfaceLost("display surface reclaimed by the head unit")
            }
        })
        root.addView(surface, FrameLayout.LayoutParams(1, 1))

        val params = WindowManager.LayoutParams(
            1,
            1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(root, params)
            windowManager = wm
            probeRoot = root
            attached = true
            stopping = false
            Timber.i("Camera probe window attached")
        } catch (all: Throwable) {
            Timber.w(all, "Camera probe window could not be attached; surface detection disabled")
        }
    }

    fun stop() {
        if (!attached) return
        stopping = true
        val root = probeRoot
        root?.suppressSignals = true
        try {
            windowManager?.removeView(root)
        } catch (all: Throwable) {
            Timber.w(all, "Camera probe window could not be removed")
        }
        probeRoot = null
        windowManager = null
        attached = false
        CameraSignalBus.reset()
    }

    /** Reports window visibility changes of the overlay, another no-permission camera hint. */
    private inner class ProbeRootView(context: Context) : FrameLayout(context) {

        var suppressSignals = false

        // Only edges are signals. A NOT_FOCUSABLE window may be told "no focus" repeatedly by
        // the window manager; treating every callback as a camera view would pin the gate shut.
        private var lastVisibility = View.VISIBLE
        private var lastFocus = false

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (suppressSignals || stopping || visibility == lastVisibility) return
            val wasVisible = lastVisibility == View.VISIBLE
            lastVisibility = visibility
            if (wasVisible && visibility != View.VISIBLE) {
                CameraSignalBus.noteWindowHidden("overlay window hidden by the head unit")
            } else if (!wasVisible && visibility == View.VISIBLE) {
                CameraSignalBus.noteWindowShown()
            }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (suppressSignals || stopping || hasWindowFocus == lastFocus) return
            val hadFocus = lastFocus
            lastFocus = hasWindowFocus
            if (hadFocus && !hasWindowFocus) {
                CameraSignalBus.noteWindowHidden("overlay window lost focus")
            } else if (!hadFocus && hasWindowFocus) {
                CameraSignalBus.noteWindowShown()
            }
        }
    }
}

/**
 * Reads the running-process table, which needs no permission, for a camera / panorama app
 * that has taken the foreground. On many head units the camera view is a system window and
 * never appears here; this is only a supporting hint for the surface signal.
 */
class CameraForegroundScanner(private val context: Context) {

    /** Deliberately narrow: the navigation app must never match one of these. */
    private val hints = arrayOf(
        "panorama", "pano", "surround", "reverse", "reversing", "camera", "avm", "adas", "aroundview"
    )

    private val navigationPrefixes = arrayOf("com.autonavi.", "com.amap.")

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
            foreground.firstOrNull { pkg ->
                !navigationPrefixes.any { pkg.startsWith(it, ignoreCase = true) } &&
                    hints.any { pkg.contains(it, ignoreCase = true) }
            }
        } catch (all: Throwable) {
            null
        }
    }
}
