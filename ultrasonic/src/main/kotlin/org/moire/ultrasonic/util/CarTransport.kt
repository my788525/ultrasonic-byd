/*
 * CarTransport.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import androidx.media3.common.Player

/**
 * The rules of the transport controls on a head unit.
 *
 * The floating window and the permanent player panel show the same player, but a driver reads
 * them differently: the window is glanced at, the panel is looked at. Both have to answer three
 * questions at a glance:
 *
 * 1. Is the music playing, paused, buffering or broken?
 * 2. What does the previous button do at this very moment?
 * 3. Does a clumsy double tap skip two songs?
 *
 * The rules live here, away from the views, so that they can be tested without a device.
 */
object CarTransport {

    /**
     * A previous button that is pressed more than this many milliseconds into the song goes
     * back to the start of the song instead of to the song before it.
     *
     * This is the behaviour of every known music player, and the driver expects it: pressing
     * previous in the middle of a song means "I did not like this one", not "play the one
     * before".
     */
    const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

    /**
     * Two taps within this window count as one.
     *
     * The screen of a head unit is large and so are its buttons, so an accidental double tap is
     * common. It must not skip two songs.
     */
    const val CLICK_DEBOUNCE_MS = 400L

    /** What the previous button does. */
    enum class PreviousAction {
        /** Go back to the start of the current song. */
        RESTART_CURRENT,

        /** Go to the song before the current one. */
        GO_TO_PREVIOUS,

        /** There is no song before this one and the current one cannot be restarted. */
        NONE
    }

    /** What the driver has to see. */
    enum class VisualState {
        /** The music is playing. */
        PLAYING,

        /** The music is stopped on purpose. */
        PAUSED,

        /** The music is being loaded, it is not stopped. */
        BUFFERING,

        /** The playback failed. */
        ERROR,

        /** The music waits for the server to come back. */
        WAITING_FOR_NETWORK,

        /** Nothing is loaded at all. */
        IDLE
    }

    /**
     * Decides what the previous button has to do.
     *
     * @param positionMs the position inside the current song
     * @param hasPreviousMediaItem whether a song before the current one exists
     */
    fun previousAction(positionMs: Long, hasPreviousMediaItem: Boolean): PreviousAction {
        // In the middle of a song the driver wants to start this one over.
        if (positionMs > PREVIOUS_RESTART_THRESHOLD_MS) return PreviousAction.RESTART_CURRENT
        if (hasPreviousMediaItem) return PreviousAction.GO_TO_PREVIOUS
        return PreviousAction.NONE
    }

    /**
     * True when a tap has to be ignored, because the one before it was too recent.
     *
     * @param lastClickAt the time of the previous tap, 0 when there was none
     * @param nowAt the time of the current tap
     */
    fun isDebounced(lastClickAt: Long, nowAt: Long): Boolean {
        // A first tap is never a double tap.
        if (lastClickAt <= 0L) return false
        return nowAt - lastClickAt < CLICK_DEBOUNCE_MS
    }

    /**
     * The state the driver has to see.
     *
     * Buffering comes before playing: while the song is loading, the player is not playing, and
     * without this the window would show the pause symbol for a song that is on its way.
     *
     * @param playbackState the media3 playback state
     * @param isPlaying whether the player reports that it is playing
     * @param hasError whether the last attempt to play failed
     * @param waitingForNetwork whether the app waits for the server to come back
     */
    fun visualState(
        playbackState: Int,
        isPlaying: Boolean,
        hasError: Boolean,
        waitingForNetwork: Boolean
    ): VisualState {
        if (waitingForNetwork) return VisualState.WAITING_FOR_NETWORK
        if (hasError) return VisualState.ERROR
        return when (playbackState) {
            Player.STATE_BUFFERING -> VisualState.BUFFERING
            Player.STATE_READY -> if (isPlaying) VisualState.PLAYING else VisualState.PAUSED
            Player.STATE_IDLE, Player.STATE_ENDED -> VisualState.IDLE
            else -> VisualState.IDLE
        }
    }
}
