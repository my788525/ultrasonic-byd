/*
 * CarTransportTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins down the rules of the transport buttons on a head unit.
 *
 * These rules decide what the driver gets when a finger hits a button, so they are checked
 * here instead of only in the view.
 */
class CarTransportTest {

    @Test
    fun `the previous button restarts the song when it is pressed in the middle of it`() {
        val action = CarTransport.previousAction(
            positionMs = 30_000L,
            hasPreviousMediaItem = true
        )
        assertEquals(CarTransport.PreviousAction.RESTART_CURRENT, action)
    }

    @Test
    fun `the previous button changes the song at the very beginning of it`() {
        val action = CarTransport.previousAction(
            positionMs = 0L,
            hasPreviousMediaItem = true
        )
        assertEquals(CarTransport.PreviousAction.GO_TO_PREVIOUS, action)
    }

    @Test
    fun `the boundary between restarting and changing the song is three seconds`() {
        assertEquals(
            CarTransport.PreviousAction.RESTART_CURRENT,
            CarTransport.previousAction(
                CarTransport.PREVIOUS_RESTART_THRESHOLD_MS + 1,
                hasPreviousMediaItem = true
            )
        )
        assertEquals(
            CarTransport.PreviousAction.GO_TO_PREVIOUS,
            CarTransport.previousAction(
                CarTransport.PREVIOUS_RESTART_THRESHOLD_MS,
                hasPreviousMediaItem = true
            )
        )
    }

    @Test
    fun `the previous button does nothing at the start of the first song`() {
        val action = CarTransport.previousAction(
            positionMs = 0L,
            hasPreviousMediaItem = false
        )
        assertEquals(CarTransport.PreviousAction.NONE, action)
    }

    @Test
    fun `the first tap is never a double tap`() {
        assertFalse(CarTransport.isDebounced(lastClickAt = 0L, nowAt = 12_345L))
    }

    @Test
    fun `a tap right after the previous one is ignored`() {
        assertTrue(CarTransport.isDebounced(lastClickAt = 10_000L, nowAt = 10_100L))
    }

    @Test
    fun `a tap after the double tap window is accepted`() {
        assertFalse(
            CarTransport.isDebounced(
                lastClickAt = 10_000L,
                nowAt = 10_000L + CarTransport.CLICK_DEBOUNCE_MS
            )
        )
    }

    @Test
    fun `a buffering song is not shown as a paused one`() {
        val state = CarTransport.visualState(
            playbackState = Player.STATE_BUFFERING,
            isPlaying = false,
            hasError = false,
            waitingForNetwork = false
        )
        assertEquals(CarTransport.VisualState.BUFFERING, state)
    }

    @Test
    fun `a playing song is shown as playing`() {
        val state = CarTransport.visualState(
            playbackState = Player.STATE_READY,
            isPlaying = true,
            hasError = false,
            waitingForNetwork = false
        )
        assertEquals(CarTransport.VisualState.PLAYING, state)
    }

    @Test
    fun `a stopped song is shown as paused`() {
        val state = CarTransport.visualState(
            playbackState = Player.STATE_READY,
            isPlaying = false,
            hasError = false,
            waitingForNetwork = false
        )
        assertEquals(CarTransport.VisualState.PAUSED, state)
    }

    @Test
    fun `a failed song is shown as broken`() {
        val state = CarTransport.visualState(
            playbackState = Player.STATE_IDLE,
            isPlaying = false,
            hasError = true,
            waitingForNetwork = false
        )
        assertEquals(CarTransport.VisualState.ERROR, state)
    }

    @Test
    fun `waiting for the network wins over the error`() {
        // A network error is also an error, but the driver is told the more useful thing:
        // the music comes back by itself.
        val state = CarTransport.visualState(
            playbackState = Player.STATE_IDLE,
            isPlaying = false,
            hasError = true,
            waitingForNetwork = true
        )
        assertEquals(CarTransport.VisualState.WAITING_FOR_NETWORK, state)
    }

    @Test
    fun `an idle player shows nothing`() {
        val state = CarTransport.visualState(
            playbackState = Player.STATE_IDLE,
            isPlaying = false,
            hasError = false,
            waitingForNetwork = false
        )
        assertEquals(CarTransport.VisualState.IDLE, state)
    }
}
