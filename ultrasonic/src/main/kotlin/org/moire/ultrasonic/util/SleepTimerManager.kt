/*
 * SleepTimerManager.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stops the music after a while so the driver does not fall asleep with the car still playing.
 *
 * On a head unit the screen is driven past, not looked at, so the timer is a single tap that
 * cycles through a few fixed durations. The actual pause is not the timer's job: it only tells
 * the listeners that the time is up. Whoever is showing the player (the activity or the floating
 * window) pauses, because either of them may be the only thing still alive when the timer fires.
 *
 * The end time is persisted, so if the process is killed and restarts the countdown continues
 * from where it was instead of silently switching itself off.
 */
class SleepTimerManager {

    companion object {
        /** Selectable durations in minutes, advanced by one tap on the timer button. */
        val PRESET_MINUTES = listOf(15, 30, 45, 60)
        private const val TICK_MS = 1000L
    }

    /**
     * A surface that wants to reflect the timer. All listeners are notified, so more than one
     * (the pane and the floating window) can show the state at the same time.
     */
    interface Listener {
        /** Called roughly once a second with the time left. */
        fun onSleepTimerTick(remainingMillis: Long) {}

        /** Called when the timer turns on or off. */
        fun onSleepTimerActiveChanged(active: Boolean) {}

        /** Called when the countdown reaches zero; the listener should pause playback. */
        fun onSleepTimerFired() {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            val remaining = remainingMillis()
            if (remaining <= 0L) {
                fire()
            } else {
                emitTick(remaining)
                handler.postDelayed(this, TICK_MS.coerceAtMost(remaining))
            }
        }
    }

    val isActive: Boolean
        get() = Settings.sleepTimerEndAt > 0L && remainingMillis() > 0L

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        // Push the current state immediately so a surface that just appeared shows the right thing.
        listener.onSleepTimerActiveChanged(isActive)
        if (isActive) listener.onSleepTimerTick(remainingMillis())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Starts the countdown for the given number of minutes. */
    fun start(minutes: Int) {
        require(minutes > 0) { "Sleep timer minutes must be positive" }
        Settings.sleepTimerMinutes = minutes
        Settings.sleepTimerEndAt = System.currentTimeMillis() + minutes * 60_000L
        schedule()
        emitActive(true)
    }

    /** Turns the timer off and forgets the end time. */
    fun cancel() {
        val wasActive = isActive
        Settings.sleepTimerEndAt = 0L
        Settings.sleepTimerMinutes = 0
        handler.removeCallbacks(tickRunnable)
        if (wasActive) emitActive(false)
        emitTick(0L)
    }

    /**
     * One tap cycles 15 → 30 → 45 → 60 → off → 15 … . The off state is reached from the
     * longest preset, not from any arbitrary remaining time, so the button always lands on a
     * duration the driver can read at a glance.
     */
    fun cycle() {
        if (!isActive) {
            start(PRESET_MINUTES.first())
            return
        }
        val index = PRESET_MINUTES.indexOf(Settings.sleepTimerMinutes)
        if (index < 0 || index >= PRESET_MINUTES.size - 1) {
            cancel()
        } else {
            start(PRESET_MINUTES[index + 1])
        }
    }

    /** Milliseconds left, or 0 when the timer is off or has elapsed. */
    fun remainingMillis(): Long {
        val end = Settings.sleepTimerEndAt
        if (end <= 0L) return 0L
        return (end - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Re-arms a timer that was running when the process died. Called from the service that
     * owns playback, so a restarted app does not lose the driver's "stop in 20 minutes".
     */
    fun resumeIfNeeded() {
        if (Settings.sleepTimerEndAt <= 0L) return
        if (!isActive) {
            // It elapsed while we were dead; drop the stale marker instead of firing late.
            Settings.sleepTimerMinutes = 0
            Settings.sleepTimerEndAt = 0L
            return
        }
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tickRunnable)
        if (!isActive) return
        emitTick(remainingMillis())
        handler.postDelayed(tickRunnable, TICK_MS.coerceAtMost(remainingMillis()))
    }

    private fun fire() {
        handler.removeCallbacks(tickRunnable)
        Settings.sleepTimerEndAt = 0L
        Settings.sleepTimerMinutes = 0
        emitActive(false)
        emitTick(0L)
        for (listener in listeners) listener.onSleepTimerFired()
    }

    private fun emitTick(remaining: Long) {
        for (listener in listeners) listener.onSleepTimerTick(remaining)
    }

    private fun emitActive(active: Boolean) {
        for (listener in listeners) listener.onSleepTimerActiveChanged(active)
    }
}
