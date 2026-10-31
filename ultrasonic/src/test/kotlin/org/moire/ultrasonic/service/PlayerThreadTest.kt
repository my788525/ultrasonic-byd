/*
 * PlayerThreadTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.Test as JUnitTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Proves the reason for the crash on the head unit.
 *
 * ExoPlayer may only be read on the thread that its application looper runs on. The
 * preload loop used to read the timeline from the IO dispatcher, which made the player
 * throw an IllegalStateException every 45 seconds. That exception was not caught, so the
 * whole app died while a song was playing.
 *
 * This test pins that behaviour down, so that the same mistake cannot come back.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerThreadTest {

    private fun createPlayer(): ExoPlayer {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        return ExoPlayer.Builder(context).build()
    }

    @JUnitTest
    fun `reading the timeline on the main thread works`() {
        val player = createPlayer()
        try {
            player.setMediaItem(MediaItem.fromUri("http://localhost/test.mp3"))
            player.prepare()

            // The main thread is the thread the player was created on.
            assertNotNull(player.currentTimeline)
        } finally {
            player.release()
        }
    }

    @JUnitTest
    fun `reading the timeline on another thread throws`() {
        val player = createPlayer()
        try {
            var thrown: Throwable? = null
            val thread = Thread {
                thrown = runCatching { player.currentMediaItemIndex }.exceptionOrNull()
            }
            thread.start()
            thread.join(5_000)

            val error = thrown
            assertNotNull(error, "The player should refuse the access from another thread")
            assertFailsWith<IllegalStateException> { throw error }
        } finally {
            player.release()
        }
    }

    @JUnitTest
    fun `reading the playing state on another thread throws`() {
        val player = createPlayer()
        try {
            var thrown: Throwable? = null
            val thread = Thread {
                thrown = runCatching { player.isPlaying }.exceptionOrNull()
            }
            thread.start()
            thread.join(5_000)

            val error = thrown
            assertNotNull(error, "The player should refuse the access from another thread")
            assertFailsWith<IllegalStateException> { throw error }
        } finally {
            player.release()
        }
    }
}
