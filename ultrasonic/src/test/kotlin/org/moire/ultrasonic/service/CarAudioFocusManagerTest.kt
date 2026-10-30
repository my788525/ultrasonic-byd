/*
 * CarAudioFocusManagerTest.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.media.AudioManager
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests the ducking behaviour of [CarAudioFocusManager].
 *
 * The fake [AudioFocusRequester] simulates the focus changes that a navigation app causes on a
 * car: a ducking request, a temporary loss and a permanent loss.
 */
class CarAudioFocusManagerTest {

    private lateinit var fakeRequester: FakeAudioFocusRequester
    private lateinit var player: Player
    private lateinit var manager: CarAudioFocusManager

    /** The volume that the mocked player reports. */
    private var currentVolume = 1.0f

    @Before
    fun setUp() {
        fakeRequester = FakeAudioFocusRequester()
        player = mock()
        currentVolume = 1.0f
        whenever(player.volume).thenAnswer { currentVolume }
        doAnswer { currentVolume = it.getArgument(0) }.whenever(player).volume = any()
        whenever(player.isPlaying).thenReturn(true)
        manager = CarAudioFocusManager(fakeRequester) { player }
    }

    @Test
    fun `the focus is only requested when the playback starts`() {
        manager.onPlaybackStarted()

        assertTrue(fakeRequester.isRequested)
        assertTrue(manager.requestFocus())
    }

    @Test
    fun `the focus is not requested twice`() {
        manager.onPlaybackStarted()
        manager.onPlaybackStarted()

        assertEquals(1, fakeRequester.requestCount)
    }

    @Test
    fun `the focus is abandoned when the playback stops`() {
        manager.onPlaybackStarted()
        manager.onPlaybackStopped()

        assertTrue(fakeRequester.isAbandoned)
    }

    @Test
    fun `the music is ducked to 40 percent while the navigation app talks`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(0.4f, currentVolume)
    }

    @Test
    fun `the volume is restored when the focus comes back`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1.0f, currentVolume)
    }

    @Test
    fun `the volume is restored when the focus is abandoned`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        manager.abandonFocus()

        assertEquals(1.0f, currentVolume)
    }

    @Test
    fun `the playback is paused on a temporary focus loss`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        verify(player).pause()
    }

    @Test
    fun `the playback is resumed when the focus comes back`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        verify(player).play()
    }

    @Test
    fun `the focus is kept while the playback is paused for a focus loss`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        manager.onPlaybackStopped()

        assertFalse(fakeRequester.isAbandoned)
    }

    @Test
    fun `the playback is paused on a permanent focus loss`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        verify(player).pause()
    }

    @Test
    fun `an unknown focus change is ignored`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(4711)

        assertEquals(1.0f, currentVolume)
        verify(player, never()).pause()
    }

    @Test
    fun `the music may not start again after a permanent focus loss`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        // Another app has the audio for good, so the music must not come back by itself.
        assertFalse(manager.mayStartPlayback())
    }

    @Test
    fun `the music may start again after the focus was given up`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        manager.abandonFocus()

        // The driver asked for the music again, so the app may try to get the focus.
        assertTrue(manager.mayStartPlayback())
    }

    @Test
    fun `a refused focus stops the playback`() {
        fakeRequester.grantFocus = false

        val granted = manager.onPlaybackStarted()

        assertFalse(granted)
    }

    @Test
    fun `a granted focus lets the playback continue`() {
        assertTrue(manager.onPlaybackStarted())
    }

    @Test
    fun `a temporary focus loss does not block the playback`() {
        manager.onPlaybackStarted()

        fakeRequester.simulateFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // Only a permanent loss blocks the music.
        assertTrue(manager.mayStartPlayback())
    }
}

/**
 * [AudioFocusRequester] that records the calls and lets the test trigger the focus changes.
 */
class FakeAudioFocusRequester : AudioFocusRequester {

    var isRequested = false
        private set

    var isAbandoned = false
        private set

    var requestCount = 0
        private set

    /** When false the focus request is refused, like when another app holds the audio. */
    var grantFocus = true

    private var listener: ((Int) -> Unit)? = null

    override fun request(onFocusChanged: (Int) -> Unit): Boolean {
        isRequested = true
        requestCount++
        listener = onFocusChanged
        return grantFocus
    }

    override fun abandon() {
        isAbandoned = true
    }

    /** Simulates a focus change caused by another app. */
    fun simulateFocusChange(focusChange: Int) {
        listener?.invoke(focusChange)
    }
}
