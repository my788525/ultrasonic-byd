/*
 * CarAudioFocusManager.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.media.AudioManager
import androidx.media3.common.Player
import timber.log.Timber
import org.moire.ultrasonic.util.CameraSignalBus

/**
 * Takes over the audio focus handling of the player so that the music can be ducked to a level
 * that is audible on a car.
 *
 * ExoPlayer is able to handle the audio focus on its own, but then it ducks to 20 % of the
 * volume, which cannot be heard next to the navigation voice. The duck is applied as an internal
 * multiplier on the renderer volume and never shows up in [Player.getVolume], so it cannot be
 * adjusted from the outside: the renderer volume is `player volume * internal multiplier`, and
 * the player volume itself is capped at 1.0, so the duck cannot be lifted to 40 % from the
 * outside either.
 *
 * Therefore the player is built with `handleAudioFocus = false`, which makes ExoPlayer skip the
 * audio focus entirely, and the focus is managed here instead. When another app asks for the
 * focus with a ducking request, the volume of the player is lowered to [DUCK_VOLUME]; when the
 * focus comes back, the volume is restored.
 *
 * The focus is only requested while the player is playing, so that the navigation app can talk
 * even when the music is paused or stopped.
 */
class CarAudioFocusManager(
    private val focusRequester: AudioFocusRequester,
    private val playerProvider: () -> Player
) {
    /** True while this app holds the audio focus. */
    private var focusHeld = false

    /** The volume the player had before it was ducked. */
    private var volumeBeforeDuck: Float? = null

    /** True while the playback is paused because the focus was lost temporarily. */
    private var pausedForFocusLoss = false

    /**
     * True when another app took the audio for good.
     *
     * After such a loss the music must not start again on its own: the other app has the
     * audio, and a playback that ignores the focus would talk over it.
     */
    private var focusLostPermanently = false

    /**
     * Requests the audio focus so that the music can be ducked by other apps.
     *
     * @return true if the focus was granted and the playback may continue.
     */
    fun requestFocus(): Boolean {
        if (focusHeld) {
            return true
        }
        focusHeld = focusRequester.request { focusChange ->
            onFocusChanged(focusChange)
        }
        if (focusHeld) {
            Timber.d("Audio focus granted")
        } else {
            Timber.w("Audio focus request was refused")
        }
        return focusHeld
    }

    /**
     * Abandons the audio focus. The music keeps playing, but other apps can take over the audio.
     */
    fun abandonFocus() {
        focusRequester.abandon()
        focusHeld = false
        restoreVolume()
        pausedForFocusLoss = false
        focusLostPermanently = false
    }

    /**
     * Called when the player started to play.
     *
     * Returns false when the focus could not be obtained. The caller then has to stop the
     * playback, because the music would talk over the app that holds the audio.
     */
    fun onPlaybackStarted(): Boolean {
        val granted = requestFocus()
        if (granted) {
            focusLostPermanently = false
        } else {
            // Someone else has the audio. Playing anyway would mix the music into a phone
            // call or into the navigation voice.
            Timber.w("Not playing, the audio focus was refused")
        }
        return granted
    }

    /**
     * Called when the player stopped playing.
     *
     * When the playback was paused because another app took the focus temporarily, the focus is
     * kept: it has to come back so that the playback can be resumed.
     */
    fun onPlaybackStopped() {
        if (pausedForFocusLoss) {
            Timber.d("Keeping the audio focus, the playback is only paused for a focus loss")
            return
        }
        abandonFocus()
    }

    /**
     * True when the music is allowed to play.
     *
     * After another app took the audio for good, the music must not start again on its own.
     * The driver can still start it by hand, that goes through [requestFocus] again.
     */
    fun mayStartPlayback(): Boolean {
        if (focusLostPermanently) {
            Timber.w("Not playing, the audio focus was lost to another app")
            return false
        }
        return true
    }

    private fun onFocusChanged(focusChange: Int) {
        // Zero-permission hint for head units that take the audio for the camera / parking
        // cues. Opt-in from Settings, because navigation prompts duck the music too.
        CameraSignalBus.setAudioFocus(
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            "audio focus $focusChange"
        )
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreVolume()
                resumeAfterFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Something else needs the audio for a while, pause until the focus returns.
                pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took the audio for good. Stop playing, otherwise the music would
                // talk over it. The flag makes sure that the music does not start again
                // behind the back of the app that holds the focus now.
                restoreVolume()
                pausedForFocusLoss = false
                focusLostPermanently = true
                playerProvider().pause()
            }
            else -> Timber.d("Unknown audio focus change: %d", focusChange)
        }
    }

    /**
     * Lowers the volume of the player while another app is talking.
     */
    private fun duck() {
        if (volumeBeforeDuck != null) {
            // Already ducked.
            return
        }
        val player = playerProvider()
        val current = player.volume
        Timber.i("Ducking music from %d %% to %d %%",
            (current * 100).toInt(), (DUCK_VOLUME * 100).toInt())
        volumeBeforeDuck = current
        player.volume = DUCK_VOLUME
        if (kotlin.math.abs(player.volume - DUCK_VOLUME) > VOLUME_TOLERANCE) {
            // The backend does not support a volume change (the jukebox plays on the server),
            // so there is nothing to restore later on.
            Timber.d("The player backend does not support volume changes")
            volumeBeforeDuck = null
        }
    }

    /**
     * Restores the volume the player had before it was ducked.
     */
    private fun restoreVolume() {
        val volume = volumeBeforeDuck ?: return
        volumeBeforeDuck = null
        Timber.i("Restoring music volume to %d %%", (volume * 100).toInt())
        playerProvider().volume = volume
    }

    private fun pauseForFocusLoss() {
        val player = playerProvider()
        if (!player.isPlaying) {
            return
        }
        pausedForFocusLoss = true
        player.pause()
    }

    private fun resumeAfterFocusLoss() {
        if (!pausedForFocusLoss) {
            return
        }
        pausedForFocusLoss = false
        playerProvider().play()
    }

    companion object {
        /**
         * The volume the music is held at while the navigation app is talking. ExoPlayer would
         * duck to 20 %, which cannot be heard over the navigation voice on a car.
         */
        private const val DUCK_VOLUME = 0.4f

        /** Tolerance when checking whether the backend applied the new volume. */
        private const val VOLUME_TOLERANCE = 0.01f
    }
}
