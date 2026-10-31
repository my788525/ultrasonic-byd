/*
 * NetworkRecoveryStateTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the state machine that resumes the music after the network comes back.
 *
 * The behaviour that matters on a head unit:
 *
 * * the music continues from the position it had, not from the beginning;
 * * the retry budget is a time window, so a server that needs a minute to answer is still
 *   picked up instead of the app giving up after a handful of attempts;
 * * the attempts are spread out, so a server that is out of range does not get hammered;
 * * the resume happens once, even when the network callback and the poll both see the network
 *   come back;
 * * the budget describes the whole outage and is only released when the music really plays
 *   again. A resume that is handed out must not restart the budget, otherwise the app would
 *   retry every few seconds for as long as the server stays unreachable;
 * * the position belongs to a song. When the driver changes the song during the outage, the
 *   position must not be restored into the new song.
 */
class NetworkRecoveryStateTest {

    private val window = 60_000L
    private val backoffBase = 5_000L
    private val backoffMax = 20_000L

    private fun state() = NetworkRecoveryState(window, backoffBase, backoffMax)

    @Test
    fun `a network failure remembers the position and asks for a resume`() {
        val state = state()

        assertTrue(state.onFailure(positionMs = 42_000L, resume = true, nowMs = 1_000L))
        assertTrue(state.isWaiting)
        assertEquals(42_000L, state.pendingPositionMs)
        assertTrue(state.shouldResume)
    }

    @Test
    fun `the resume returns the remembered position and clears the waiting flag`() {
        val state = state()
        state.onFailure(positionMs = 42_000L, resume = true, nowMs = 1_000L)

        val resume = state.takeResume()

        assertEquals(42_000L, resume?.positionMs)
        assertFalse(state.hasPendingResume)
    }

    @Test
    fun `the resume happens only once even when it is requested twice`() {
        val state = state()
        state.onFailure(positionMs = 42_000L, resume = true, nowMs = 1_000L)

        assertTrue(state.takeResume() != null)
        assertNull(state.takeResume())
    }

    @Test
    fun `a failure that must not continue does not produce a resume`() {
        val state = state()
        state.onFailure(positionMs = 42_000L, resume = false, nowMs = 1_000L)

        assertNull(state.takeResume())
        assertFalse(state.isWaiting)
    }

    @Test
    fun `the budget is a time window and not a number of attempts`() {
        val state = state()
        var now = 1_000L

        // Many failures inside the window are all accepted.
        repeat(30) {
            assertTrue(
                "attempt $it must still be accepted inside the window",
                state.onFailure(positionMs = 1_000L, resume = true, nowMs = now)
            )
            now += 1_000L
        }

        // The window has passed by now, so the next failure is refused.
        now += window
        assertFalse(state.onFailure(positionMs = 1_000L, resume = true, nowMs = now))
        assertFalse(state.isWaiting)
    }

    @Test
    fun `the window is measured from the first failure`() {
        val state = state()

        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 5_000L)
        // Still inside the window, 50 s after the first failure.
        assertTrue(state.onFailure(positionMs = 1_000L, resume = true, nowMs = 55_000L))
        // Now 61 s after the first failure: outside of the window.
        assertFalse(state.onFailure(positionMs = 1_000L, resume = true, nowMs = 66_000L))
    }

    @Test
    fun `the first attempt is always allowed`() {
        val state = state()
        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 1_000L)

        assertTrue(state.mayAttempt(1_000L))
    }

    @Test
    fun `later attempts have to wait for the backoff`() {
        val state = state()
        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 1_000L)
        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 2_000L)
        state.onAttempt(2_000L)

        // Two attempts so far, so the backoff is 10 s.
        assertFalse(state.mayAttempt(5_000L))
        assertFalse(state.mayAttempt(11_999L))
        assertTrue(state.mayAttempt(12_000L))
    }

    @Test
    fun `the backoff never grows beyond its cap`() {
        val state = state()
        var now = 1_000L
        repeat(40) {
            state.onFailure(positionMs = 1_000L, resume = true, nowMs = now)
            now += 1_000L
        }

        // 40 attempts would mean a backoff of 200 s, but it is capped at 20 s.
        state.onAttempt(now)
        assertFalse(state.mayAttempt(now + 19_999L))
        assertTrue(state.mayAttempt(now + 20_000L))
    }

    @Test
    fun `no attempt is allowed when the playback does not wait for the network`() {
        val state = state()

        assertFalse(state.mayAttempt(1_000L))
    }

    @Test
    fun `a successful playback clears the state completely`() {
        val state = state()
        state.onFailure(positionMs = 42_000L, resume = true, nowMs = 1_000L)
        state.takeResume()
        state.onSuccess()

        assertEquals(0, state.attempts)
        assertFalse(state.isWaiting)
        assertFalse(state.shouldResume)
        assertEquals(-1L, state.pendingPositionMs)
    }

    @Test
    fun `a new failure after a successful playback starts a new window`() {
        val state = state()
        state.onFailure(positionMs = 42_000L, resume = true, nowMs = 1_000L)
        state.takeResume()
        state.onSuccess()

        // Far beyond the window of the first failure, but this is a new failure.
        assertTrue(state.onFailure(positionMs = 7_000L, resume = true, nowMs = 500_000L))
        assertTrue(state.isWaiting)
    }

    @Test
    fun `handing out a resume does not restart the budget`() {
        val state = state()

        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 1_000L)
        state.takeResume()

        // The attempt counter survives the resume, otherwise the backoff would never grow
        // and the app would retry every few seconds for as long as the server is down.
        assertEquals(1, state.attempts)
    }

    @Test
    fun `the backoff grows across the resumes of one outage`() {
        val state = state()
        var now = 1_000L

        // Three failures, each with a resume in between, as it happens when the server is
        // reachable but not really usable.
        repeat(3) {
            state.onFailure(positionMs = 1_000L, resume = true, nowMs = now)
            state.takeResume()
            now += 1_000L
        }

        assertEquals(3, state.attempts)

        // The backoff has to be based on the third attempt: 3 * 5 s = 15 s.
        state.onAttempt(now)
        assertFalse(state.mayAttempt(now + 14_999L))
        assertTrue(state.mayAttempt(now + 15_000L))
    }

    @Test
    fun `the window is not restarted by the resumes of one outage`() {
        val state = state()
        var now = 1_000L

        // A failure, a resume, and then more failures, all within the window.
        state.onFailure(positionMs = 1_000L, resume = true, nowMs = now)
        state.takeResume()

        // 59 s after the first failure: still inside the window.
        now = 60_000L
        assertTrue(state.onFailure(positionMs = 1_000L, resume = true, nowMs = now))
    }

    @Test
    fun `the window can expire without a new failure`() {
        val state = state()

        state.onFailure(positionMs = 1_000L, resume = true, nowMs = 1_000L)

        assertFalse(state.isWindowExpired(30_000L))
        assertTrue(state.isWindowExpired(70_000L))
    }

    @Test
    fun `the position belongs to the track it was taken from`() {
        val state = state()

        state.onFailure(
            positionMs = 42_000L,
            trackId = "track-1",
            resume = true,
            nowMs = 1_000L
        )

        assertEquals("track-1", state.takeResume()?.trackId)
    }

    @Test
    fun `a pause during the outage drops the position`() {
        val state = state()

        state.onFailure(
            positionMs = 42_000L,
            trackId = "track-1",
            resume = true,
            nowMs = 1_000L
        )
        state.clearPending()

        // The music must not start by itself any more.
        assertNull(state.takeResume())
        assertEquals(-1L, state.pendingPositionMs)
        assertNull(state.pendingTrackId)
        assertFalse(state.isWaiting)
    }
}
