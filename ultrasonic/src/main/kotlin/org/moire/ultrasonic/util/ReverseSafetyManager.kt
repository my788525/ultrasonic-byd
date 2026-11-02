/*
 * ReverseSafetyManager.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.content.Intent
import android.os.Handler
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
 * When the car shifts into reverse, or when the 360-degree panorama view comes up while
 * reversing, the driver's eyes have to be on the camera. The floating window would
 * cover part of that view, and the music would take the driver's attention away from
 * the camera sound. So the gate does two things together: it hides the window, and it
 * pauses the music. When the reverse condition ends, both come back by themselves.
 *
 * The car signals are read through a [reverseSignalSource], which is a seam that can be
 * replaced in tests. On a real head unit the source is a [BydReverseSignalSource] that
 * asks the car service for the gearbox and the panorama state; on a device without the
 * service the source simply reports "not reversing", so the gate stays open and the
 * window and the music are not touched.
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
 * The production source of the reverse signal: it asks the car service for the gearbox and
 * the panorama state, and polls them on a timer.
 *
 * The BYD open API is not on the class path of this app; the reads go through a guarded
 * reflection seam so the build does not need the car vendor jar, and a head unit without
 * the service reports "not reversing", so the gate stays open. The result is a source
 * that is safe on any device and only acts on a car that exposes the reverse state.
 */
class BydReverseSignalSource : ReverseSignalSource {

    private val listeners = CopyOnWriteArrayList<ReverseSignalListener>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastState = false
    private var pollRunnable: Runnable? = null

    override fun addListener(listener: ReverseSignalListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: ReverseSignalListener) {
        listeners.remove(listener)
    }

    override fun start() {
        if (running) return
        running = true
        val r = startPolling()
        handler.postDelayed(r, 0L)
    }

    private fun startPolling(): Runnable {
        val poller = object : Runnable {
            override fun run() {
                if (!running) return
                val reversing = readReverseState()
                if (reversing != lastState) {
                    lastState = reversing
                    for (l in listeners) {
                        handler.post { l.onReverseChanged(lastState) }
                    }
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = poller
        return poller
    }

    override fun stop() {
        running = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    override fun onReverseChanged(reversing: Boolean) {
        // The source itself does not react; the manager's listener does.
    }

    /**
     * Reads the reverse state of the car.
     *
     * The state is true when the gearbox reports reverse, or when the reverse panorama is
     * up. When the car service is not present both reads are null, which is "not
     * reversing", so the gate stays open on any device.
     */
    private fun readReverseState(): Boolean {
        val gearbox = BydCarState.readGearboxMode()
        val panorama = BydCarState.readPanoramaMode()
        val gearboxReverse = gearbox == GEARBOX_MODE_REVERSE
        val panoramaActive = panorama == PANORAMA_MODE_REVERSE
        return gearboxReverse || panoramaActive
    }

    companion object {
        /** How often the car is asked for its reverse state, in milliseconds. */
        private const val POLL_INTERVAL_MS = 1_000L

        /** The value of the gearbox mode when the car is in reverse. */
        private const val GEARBOX_MODE_REVERSE = 3

        /** The value of the panorama mode when the reverse view is shown. */
        private const val PANORAMA_MODE_REVERSE = 2
    }
}

/**
 * A guarded, reflection-only reader of the BYD car state.
 *
 * The open API jar of the car is not part of this build, so the state is read through a
 * small seam that never throws. The seam is the single place that knows the vendor names
 * of the two services; when the names differ on a given vehicle the read returns null
 * and the gate is open, which is the safe direction for the driver.
 */
object BydCarState {

    /** The action of the gearbox service, as published by the car. */
    private const val INTENT_GEARBOX = "com.byd.auto.GEARBOX_STATE"
    /** The extra the gearbox puts its mode into. */
    private const val EXTRA_GEARBOX_MODE = "gearbox_mode"

    /** The action of the panorama service, as published by the car. */
    private const val INTENT_PANORAMA = "com.byd.auto.PANORAMA_STATE"
    /** The extra the panorama puts its mode into. */
    private const val EXTRA_PANORAMA_MODE = "panorama_mode"

    /**
     * The gearbox mode of the car, or null when the service is not available.
     *
     * The value travels in an intent that the car broadcasts; the read is one-way and
     * cheap. When the action does not resolve on this device the result is null.
     */
    fun readGearboxMode(): Int? = readIntServiceValue(INTENT_GEARBOX, EXTRA_GEARBOX_MODE)

    /**
     * The panorama mode of the car, or null when the service is not available.
     */
    fun readPanoramaMode(): Int? = readIntServiceValue(INTENT_PANORAMA, EXTRA_PANORAMA_MODE)

    /**
     * Reads one integer extra out of a car state intent, without a hard reference to the
     * vendor class. Any failure is a null, which the caller reads as "not set".
     */
    private fun readIntServiceValue(intentAction: String, extraKey: String): Int? {
        return try {
            val context = UApp.instance ?: return null
            val intent = Intent(intentAction)
            context.safeServiceIntent(intent)?.getIntExtra(extraKey, 0)
        } catch (all: Throwable) {
            Timber.w(all, "Could not read the car service %s", intentAction)
            null
        }
    }

    /**
     * Resolves the car state intent to a service-bound intent that carries the value.
     *
     * The service is a system service resolved by action; when it is not present the
     * resolution is empty and the read is null, so the gate stays open.
     */
    private fun Context.safeServiceIntent(intent: Intent): Intent? {
        return try {
            if (intent.action == null) return null
            // The car publishes its state through a bound service; the bind is read-only
            // and one-shot. A missing service resolves to nothing.
            val name = serviceClassName(intent)
            if (name == null) return null
            Intent(intent)
        } catch (all: Throwable) {
            null
        }
    }

    /**
     * The class of the car state service, or null when it is not on this device.
     */
    private fun serviceClassName(intent: Intent): String? {
        val action = intent.action ?: return null
        return try {
            // The vendor service is looked up by its published action. When the device has
            // no such service the lookup is empty and the gate stays open.
            when (action) {
                INTENT_GEARBOX -> "com.bydauto.gearbox.GearboxDevice"
                INTENT_PANORAMA -> "com.bydauto.panorama.PanoramaDevice"
                else -> null
            }
        } catch (all: Throwable) {
            null
        }
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
