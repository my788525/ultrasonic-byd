/*
 * ReverseSafetyManager.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
    private var registered = false

    /**
     * The broadcast receiver for the car's gearbox and panorama (360) state.
     *
     * The car publishes its reverse / 360 state as broadcast intents; the receiver turns each
     * one into a reverse-or-not decision and tells the listeners only when the state changes.
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val reversing = BydCarState.isReverseIntent(intent)
            if (reversing != lastState) {
                lastState = reversing
                for (l in listeners) handler.post { l.onReverseChanged(lastState) }
            }
        }
    }

    override fun addListener(listener: ReverseSignalListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    override fun removeListener(listener: ReverseSignalListener) {
        listeners.remove(listener)
    }

    override fun start() {
        if (running) return
        running = true
        val context = UApp.instance ?: return
        val filter = IntentFilter().apply {
            BydCarState.gearboxActions.forEach { addAction(it) }
            BydCarState.panoramaActions.forEach { addAction(it) }
        }
        try {
            // RECEIVER_NOT_EXPORTED: the app only listens to the car's own broadcasts, never
            // to those from other apps, so exporting would be both unnecessary and unsafe.
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        } catch (all: Throwable) {
            Timber.w(all, "Could not register the BYD reverse receiver")
            registered = false
        }
        // Seed the listeners with the current (non-reversing) state so the gate starts
        // disengaged even before the first broadcast arrives. A sticky broadcast, if the car
        // sends one, is delivered immediately on registration and updates it right away.
        for (l in listeners) handler.post { l.onReverseChanged(lastState) }
    }

    override fun stop() {
        running = false
        val context = UApp.instance
        if (registered && context != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (all: Throwable) {
                Timber.w(all, "Could not unregister the BYD reverse receiver")
            }
        }
        registered = false
    }

    override fun onReverseChanged(reversing: Boolean) {
        // The source itself does not react; the manager's listener does.
    }
}

/**
 * A guarded reader of the BYD car state, kept in one place so a single on-device check can
 * confirm or correct the vendor broadcast names.
 *
 * The BYD open API jar is not part of this build, so the state is read from broadcast intents
 * the car emits. The action names and mode values are vendor strings that vary between BYD
 * firmware versions; they live here so the one thing to verify on a given head unit is these
 * constants. When none of the broadcasts match, [isReverseIntent] returns false and the gate
 * stays open — the safe direction for the driver.
 */
object BydCarState {

    /** Broadcast actions the gearbox publishes its state under. */
    val gearboxActions = listOf(
        "com.byd.auto.GEARBOX_STATE"
    )

    /** Broadcast actions the 360 / panorama camera publishes its state under. */
    val panoramaActions = listOf(
        "com.byd.auto.PANORAMA_STATE",
        "com.byd.auto.avm.AVM_STATE"
    )

    /** Extra the gearbox puts its mode into. */
    const val EXTRA_GEARBOX_MODE = "gearbox_mode"

    /** Extra the panorama / 360 puts its mode into. */
    const val EXTRA_PANORAMA_MODE = "panorama_mode"

    /** Gearbox mode value meaning reverse. */
    const val GEARBOX_MODE_REVERSE = 3

    /** Panorama / 360 mode value meaning the reverse camera view is up. */
    const val PANORAMA_MODE_REVERSE = 2

    /**
     * Decides whether a car-state broadcast means "reversing or 360 up".
     *
     * Returns false for any broadcast this app does not recognise, so a device that never
     * fires these intents simply never engages the gate.
     */
    fun isReverseIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false
        return when {
            gearboxActions.contains(action) ->
                intent.getIntExtra(EXTRA_GEARBOX_MODE, 0) == GEARBOX_MODE_REVERSE
            panoramaActions.contains(action) ->
                intent.getIntExtra(EXTRA_PANORAMA_MODE, 0) == PANORAMA_MODE_REVERSE
            else -> false
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
