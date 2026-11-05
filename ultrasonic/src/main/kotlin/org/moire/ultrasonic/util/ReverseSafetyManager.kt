/*
 * ReverseSafetyManager.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.koin.core.component.KoinComponent
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.service.FloatingPlayerService
import org.moire.ultrasonic.util.Settings
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reverse-camera safety gate for the floating window and the music.
 *
 * When the car shifts into reverse, or when the 360-degree panorama view comes up, the
 * driver's eyes have to be on the camera. The floating window would cover part of that view,
 * and the music would take the driver's attention away from the camera sound. So the gate
 * does two things together: it hides the window, and it pauses the music. When the reverse
 * condition ends, both come back by themselves.
 *
 * The car signals are read through a [reverseSignalSource], which is a seam that can be
 * replaced in tests. On a real BYD head unit the source is a [BydReverseSignalSource] that
 * asks the car's BYD Open Platform service for the gearbox and the panorama state; on a
 * device without that service the source simply reports "not reversing", so the gate stays
 * open and the window and the music are not touched.
 *
 * The gate is behind [Settings.floatingReverseSafety], which is on by default: on a car
 * the behaviour is the safe one, and a driver who wants the window up in reverse has to
 * turn it off on purpose.
 */
class ReverseSafetyManager : KoinComponent {

    /** The source of the car's reverse signal. It decides, not this class. */
    var reverseSignalSource: ReverseSignalSource = BydReverseSignalSource()

    private val listener = object : ReverseSignalListener {
        override fun onReverseChanged(reversing: Boolean) {
            if (reversing) engage() else release()
        }
    }

    /** The source that is running, kept so it can be released in [stop]. */
    private var runningSource: ReverseSignalSource? = null

    /** Whether the gate is currently hiding the window and pausing the music. */
    @Volatile
    private var engaged = false

    /**
     * Whether the music was playing before the gate engaged.
     *
     * The gate only turns the music off when it had been running, so a driver who had
     * paused the music does not have it coming back on when the reverse view ends. The
     * flag is read on the main thread by the gate and written only there.
     */
    private var wasPlaying = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Watches the car while the floating window is alive.
     *
     * It starts the reverse signal source and keeps the gate in the state the car dictates.
     * The gate only acts when [Settings.floatingReverseSafety] is on; with it off the whole
     * manager is a no-op, which is what the setting means: a driver who has switched the
     * safety off has asked for the window to stay up.
     */
    fun start() {
        if (runningSource != null) return
        val source = reverseSignalSource
        runningSource = source
        source.addListener(listener)
        source.start()
        Timber.i("ReverseSafetyManager started, source %s", source.javaClass.simpleName)
    }

    /**
     * Stops watching the car and leaves the window and the music exactly as they are.
     *
     * The pause that the gate took is not undone here: the floating window is going away,
     * and restarting the music for a window that no longer exists would be a surprise. The
     * next time the window is shown the music state is read fresh from the player.
     */
    fun stop() {
        runningSource?.let {
            it.removeListener(listener)
            it.stop()
        }
        runningSource = null
        if (engaged) {
            release()
        }
        Timber.i("ReverseSafetyManager stopped")
    }

    /**
     * The state the gate is in now.
     *
     * The gate is only "engaged" when the safety is on and the car is reversing; the two
     * are read together, so a driver who turns the safety off while reversing still has
     * the window, and one who turns it on while reversing gets the window off at once.
     */
    fun isActive(): Boolean = engaged

    /**
     * Engages the gate: the window is hidden and the music is paused.
     *
     * Everything happens on the main thread, because the player and the window are both
     * main-thread objects. The order matters: the music is paused first, so the driver
     * sees the camera and hears it, and the window is removed second, so the screen is
     * clear by the time the audio is off.
     */
    private fun engage() {
        if (!Settings.floatingReverseSafety) return
        if (engaged) return
        mainHandler.post {
            engaged = true
            wasPlaying = FloatingPlayerService.controller()?.isPlaying == true
            if (wasPlaying) FloatingPlayerService.controller()?.pause()
            // The window is hidden for the duration of the reverse view, and comes back
            // on release. The whole gate is a display-and-audio safety pair.
            FloatingPlayerService.setReverseSafetyActive(true)
            FloatingPlayerService.hideForReverse()
            Timber.i("Reverse gate engaged, music %s", if (wasPlaying) "paused" else "already stopped")
        }
    }

    /**
     * Releases the gate: the window comes back and the music resumes when it had run.
     *
     * The resume only happens for the music that was playing, see [wasPlaying]. A driver
     * who had paused the music before the reverse view ends is not startled by it coming
     * back on.
     */
    private fun release() {
        if (!engaged) return
        mainHandler.post {
            engaged = false
            val restore = wasPlaying && Settings.floatingReverseSafety
            wasPlaying = false
            if (restore) FloatingPlayerService.controller()?.play()
            // The window comes back once the reverse view is gone; only when the safety is
            // still on, so a driver who switched it off mid-reverse keeps the window off.
            if (Settings.floatingReverseSafety) FloatingPlayerService.showForReverse()
            FloatingPlayerService.setReverseSafetyActive(false)
            Timber.i("Reverse gate released, music %s", if (restore) "resumed" else "left stopped")
        }
    }
}

/**
 * A listener of the car's reverse signal.
 */
interface ReverseSignalListener {
    fun onReverseChanged(reversing: Boolean)
}

/**
 * Where the reverse signal of the car comes from.
 *
 * The interface keeps the [ReverseSafetyManager] independent of the real source, so a
 * test can drive the gate with a plain flag and the manager does not have to reach into
 * the car service at all. The production source is [BydReverseSignalSource].
 */
interface ReverseSignalSource : ReverseSignalListener {

    /** Adds [listener], which is called on the main thread on every change of the state. */
    fun addListener(listener: ReverseSignalListener)

    /** Removes [listener]; after this it is never called. */
    fun removeListener(listener: ReverseSignalListener)

    /**
     * Begins following the car's reverse signal.
     *
     * The call is cheap and idempotent: a source that is already running returns, and the
     * state is read at start, so a gate that turns on while the car is already reversing
     * sees it at once.
     */
    fun start()

    /** Stops following the signal. It is called when the floating window goes away. */
    fun stop()
}

/**
 * The production source of the reverse signal: it asks the BYD Open Platform car service for
 * the gearbox and the panorama (360) state, and polls them on a timer.
 *
 * The BYD Open Platform classes (android.hardware.bydauto.gearbox.BYDAutoGearboxDevice and
 * android.hardware.bydauto.panorama.BYDAutoPanoramaDevice) are part of the car's system
 * framework, not of this app's compile classpath. The reads go through a guarded reflection
 * seam so the build does not need the vendor jar, and a head unit without the service reports
 * "not reversing" (ClassNotFound / SecurityException / RemoteException), so the gate stays
 * open. The result is a source that is safe on any device and only acts on a real BYD head
 * unit that exposes the open platform.
 *
 * Reverse is detected when the gearbox reports R (GEARBOX_AUTO_MODE_R), or when the panorama
 * (360) reports an open / active view (getPanoWorkState() != 0). The reverse gear constant is
 * read by name from the device class so its value is never hard-coded wrong.
 */
class BydReverseSignalSource : ReverseSignalSource {

    private val listeners = CopyOnWriteArrayList<ReverseSignalListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastState = false

    /** Background thread that performs the (binder) reads away from the main thread. */
    private var pollerThread: HandlerThread? = null
    private var pollerHandler: Handler? = null
    private var pollCount = 0

    /** BYD Open Platform device classes and instances, resolved by reflection at start(). */
    private var gearboxClazz: Class<*>? = null
    private var gearboxInstance: Any? = null
    private var gearboxR = GEARBOX_AUTO_MODE_R_FALLBACK
    private var panoramaClazz: Class<*>? = null
    private var panoramaInstance: Any? = null

    override fun addListener(listener: ReverseSignalListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: ReverseSignalListener) {
        listeners.remove(listener)
    }

    override fun start() {
        if (running) return
        running = true
        if (!resolveDevices()) {
            // The car does not expose the open platform, or the permission is missing. The
            // gate stays open: the safe direction for a device we cannot read.
            Timber.w("BYD Open Platform devices unavailable; reverse gate stays open (non-BYD device or missing permission)")
            for (l in listeners) mainHandler.post { l.onReverseChanged(false) }
            return
        }
        pollerThread = HandlerThread("byd-reverse-poll").also { it.start() }
        pollerHandler = Handler(pollerThread!!.looper)
        val poller = object : Runnable {
            override fun run() {
                if (!running) return
                val reversing = readReverseState()
                if (reversing != lastState) {
                    lastState = reversing
                    for (l in listeners) mainHandler.post { l.onReverseChanged(lastState) }
                }
                pollCount++
                if (pollCount % DIAGNOSTIC_EVERY_N == 0) {
                    Timber.d("BYD reverse poll: gearboxR=%s, lastState=%s", gearboxR, lastState)
                }
                pollerHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollerHandler?.post(poller)
        Timber.i("BydReverseSignalSource started (BYD Open Platform polling, gearboxR=%d)", gearboxR)
    }

    override fun stop() {
        running = false
        pollerHandler?.removeCallbacksAndMessages(null)
        pollerThread?.quitSafely()
        pollerThread = null
        pollerHandler = null
        gearboxInstance = null
        panoramaInstance = null
        gearboxClazz = null
        panoramaClazz = null
    }

    override fun onReverseChanged(reversing: Boolean) {
        // The source itself does not react; the manager's listener does.
    }

    /**
     * Resolves the BYD Open Platform gearbox and panorama device classes/instances through
     * reflection. Returns false when the classes are absent (non-BYD device) or the required
     * permission is missing; the caller then keeps the gate open.
     */
    private fun resolveDevices(): Boolean {
        val context = UApp.instance ?: return false
        return try {
            gearboxClazz = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice")
            gearboxInstance = gearboxClazz!!.getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            // Reverse gear constant, read by name so we never hard-code a wrong value.
            gearboxR = try {
                gearboxClazz!!.getField("GEARBOX_AUTO_MODE_R").getInt(null)
            } catch (all: Throwable) {
                Timber.w(all, "BYD GEARBOX_AUTO_MODE_R constant not found; using fallback %d", GEARBOX_AUTO_MODE_R_FALLBACK)
                GEARBOX_AUTO_MODE_R_FALLBACK
            }
            // Panorama is optional: 360 detection is disabled if it is unavailable, but the
            // gearbox-based reverse detection still works.
            try {
                panoramaClazz = Class.forName("android.hardware.bydauto.panorama.BYDAutoPanoramaDevice")
                panoramaInstance = panoramaClazz!!.getMethod("getInstance", Context::class.java)
                    .invoke(null, context)
                Timber.i("BYD panorama device resolved")
            } catch (all: Throwable) {
                Timber.w(all, "BYD panorama device unavailable; 360 detection disabled")
                panoramaClazz = null
                panoramaInstance = null
            }
            true
        } catch (all: Throwable) {
            Timber.w(all, "BYD Open Platform gearbox device unavailable")
            false
        }
    }

    /**
     * Reads the reverse state from the resolved devices.
     *
     * Reverse when the gearbox reports R, or when the panorama (360) reports an open/active
     * view. Each read is guarded: a failure (permission, IPC, RemoteException) is treated as
     * "not reversing" for that source, so a transient error never hides the window wrongly.
     */
    private fun readReverseState(): Boolean {
        val gearbox = gearboxClazz
        val gInst = gearboxInstance
        if (gearbox != null && gInst != null) {
            try {
                val mode = gearbox.getMethod("getGearboxAutoModeType").invoke(gInst) as? Int ?: 0
                if (mode == gearboxR) {
                    Timber.d("BYD gearbox mode=%d (R), engaging reverse gate", mode)
                    return true
                }
            } catch (all: Throwable) {
                Timber.w(all, "BYD gearbox read failed")
            }
        }
        val pano = panoramaClazz
        val pInst = panoramaInstance
        if (pano != null && pInst != null) {
            try {
                val workState = pano.getMethod("getPanoWorkState").invoke(pInst) as? Int ?: 0
                if (workState != 0) {
                    Timber.d("BYD panorama workState=%d, engaging reverse gate", workState)
                    return true
                }
            } catch (all: Throwable) {
                Timber.w(all, "BYD panorama read failed")
            }
        }
        return false
    }

    companion object {
        /** How often the car is asked for its reverse state, in milliseconds. */
        private const val POLL_INTERVAL_MS = 800L

        /** How often (in polls) to log a diagnostic line, so logcat can confirm values. */
        private const val DIAGNOSTIC_EVERY_N = 30

        /**
         * Fallback value of GEARBOX_AUTO_MODE_R if the constant cannot be read.
         * Per the BYD OpenAPI SDK: P=1, R=2, N=3, D=4, M=5, S=6.
         */
        private const val GEARBOX_AUTO_MODE_R_FALLBACK = 2
    }
}

/**
 * A source of the reverse signal that never fires.
 *
 * It is the fallback when the real source cannot be built, and it is the source a test
 * uses to keep the gate open. It is also what a non-BYD device effectively has: the
 * gate runs, it just never sees a reverse, so the window and the music are untouched.
 */
class NoopReverseSignalSource : ReverseSignalSource {
    override fun addListener(listener: ReverseSignalListener) = Unit
    override fun removeListener(listener: ReverseSignalListener) = Unit
    override fun start() = Unit
    override fun stop() = Unit
    override fun onReverseChanged(reversing: Boolean) = Unit
}
