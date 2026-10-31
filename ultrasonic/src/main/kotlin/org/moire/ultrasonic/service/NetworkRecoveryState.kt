/*
 * NetworkRecoveryState.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

/**
 * Keeps track of what has to happen when the network comes back.
 *
 * A head unit loses the connection in a tunnel or in a parking garage and the music has to
 * continue by itself afterwards. Two things make this more than a boolean flag:
 *
 * * The budget is a **time window** and not a number of attempts. The server at home often
 *   needs a lot longer to answer again than the tunnel needs to be left, and a fixed attempt
 *   count made the app give up long before the server was reachable, so the driver had to
 *   restart the music by hand.
 * * The attempts are **spread out with a growing backoff**. The network can be up while the
 *   server is not reachable at all, and a request every few seconds would drain the battery
 *   of the car.
 *
 * The window and the attempt counter describe the **whole outage**, not a single attempt.
 * They are only cleared when the playback really worked again, which is signalled with
 * [onSuccess]. Clearing them when a resume is handed out would restart the budget on every
 * attempt, and the app would then retry every few seconds for as long as the server is down.
 *
 * Two different things are tracked separately:
 *
 * * [isWaiting] says that an outage is running and that the retry machinery is armed.
 * * [hasPendingResume] says that there is a position to restore which was not handed out
 *   yet. The network callback and the poll both see the network come back, so the resume
 *   may only be taken once per failure.
 *
 * All methods have to be called from the same thread, or protected by the same lock as the
 * state in [PlaybackService].
 */
class NetworkRecoveryState(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val backoffMaxMs: Long = DEFAULT_BACKOFF_MAX_MS
) {

    /** Where the music has to continue when the network is back, in milliseconds. */
    var pendingPositionMs: Long = -1L
        private set

    /** The track the pending position belongs to. */
    var pendingTrackId: String? = null
        private set

    /** Whether the music should continue at all when the network is back. */
    var shouldResume: Boolean = false
        private set

    /** True while an outage is running and the retry machinery is armed. */
    var isWaiting: Boolean = false
        private set

    /**
     * True while there is a position to restore that was not handed out yet.
     *
     * This is what keeps the network callback and the poll from starting the music twice.
     */
    var hasPendingResume: Boolean = false
        private set

    /** How many times the playback was prepared again after the failure. */
    var attempts: Int = 0
        private set

    private var windowStartMs: Long = 0L
    private var lastAttemptMs: Long = 0L

    /**
     * Remembers the position the music has to continue at.
     *
     * Returns false when the recovery window has passed. The caller then has to give up and
     * tell the user, because from here on the music would only be prepared again and again
     * without any result.
     *
     * The window is measured from the **first** failure of the outage. Every later failure
     * of the same outage is counted, so that the backoff can grow.
     */
    fun onFailure(
        positionMs: Long,
        trackId: String? = null,
        resume: Boolean,
        nowMs: Long
    ): Boolean {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
        }

        if (nowMs - windowStartMs > windowMs) {
            reset()
            return false
        }

        attempts++
        pendingPositionMs = positionMs
        pendingTrackId = trackId
        shouldResume = resume
        isWaiting = true
        hasPendingResume = true
        return true
    }

    /**
     * Returns the state that the resume needs.
     *
     * The resume may only be taken once per failure, otherwise the network callback and the
     * poll, which both see the network come back, would start the music twice.
     *
     * The window and the attempt counter are **not** cleared: they describe the outage and
     * not this single attempt. They are cleared by [onSuccess], or by [reset] when the
     * recovery gives up.
     */
    fun takeResume(): Resume? {
        if (!isWaiting || !hasPendingResume) return null
        if (!shouldResume) {
            // The music was not playing when the network went away, so there is nothing to
            // continue. The recovery stops here.
            clearPending()
            return null
        }

        hasPendingResume = false
        return Resume(pendingPositionMs, pendingTrackId)
    }

    /**
     * True when another attempt may be made now.
     *
     * The first attempt is always allowed, every later one has to wait for the backoff, which
     * grows with the number of attempts and is capped.
     */
    fun mayAttempt(nowMs: Long): Boolean {
        if (!isWaiting) return false
        if (attempts <= 1) return true
        val backoff = (backoffBaseMs * attempts).coerceAtMost(backoffMaxMs)
        return nowMs - lastAttemptMs >= backoff
    }

    /** Records that an attempt was made right now. */
    fun onAttempt(nowMs: Long) {
        lastAttemptMs = nowMs
    }

    /**
     * True when the recovery window has passed without a success.
     *
     * This is checked before an attempt is made, because a window can also pass while the
     * player is happily playing a cached file and no failure is reported.
     */
    fun isWindowExpired(nowMs: Long): Boolean =
        windowStartMs != 0L && nowMs - windowStartMs > windowMs

    /**
     * The playback worked again, the outage is over.
     *
     * Only now the window and the attempt counter are cleared, so that a later outage gets
     * the full budget again.
     */
    fun onSuccess() {
        reset()
    }

    /**
     * Drops the pending resume and ends the recovery.
     *
     * Used when the driver paused the music on purpose while the network was down: the
     * position must not be restored later, and the app must not start the music behind the
     * back of the driver.
     */
    fun clearPending() {
        pendingPositionMs = -1L
        pendingTrackId = null
        shouldResume = false
        hasPendingResume = false
        isWaiting = false
    }

    /** Forgets everything, used when the recovery gave up. */
    fun reset() {
        clearPending()
        attempts = 0
        windowStartMs = 0L
        lastAttemptMs = 0L
    }

    /**
     * The position the music has to continue at, and the track it belongs to.
     *
     * The track is needed because the driver can change the song while the network is down.
     * A position without a track would then seek into a completely different song.
     */
    data class Resume(val positionMs: Long, val trackId: String?)

    companion object {
        /** How long the app keeps trying to resume before it gives up. */
        const val DEFAULT_WINDOW_MS = 10 * 60_000L

        /** The first backoff between two recovery attempts. */
        const val DEFAULT_BACKOFF_BASE_MS = 5_000L

        /** The longest backoff between two recovery attempts. */
        const val DEFAULT_BACKOFF_MAX_MS = 60_000L
    }
}

/**
 * What the interface has to know about the recovery of the playback.
 *
 * This is a plain snapshot of [NetworkRecoveryState] for the user interface. It is
 * published on the bus, so that a screen which is opened while the music is already
 * waiting still shows the message.
 */
data class NetworkRecoveryStatus(
    /** True while the playback waits for the network to come back. */
    val isWaiting: Boolean = false,

    /** How many times the playback was prepared again after the failure. */
    val attempts: Int = 0,

    /** True when the recovery gave up and the music has to be started by hand. */
    val gaveUp: Boolean = false
)
