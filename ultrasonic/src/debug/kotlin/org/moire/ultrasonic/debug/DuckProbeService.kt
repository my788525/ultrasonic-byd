/*
 * DuckProbeService.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.debug

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import org.moire.ultrasonic.service.CarAudioFocusManager
import org.moire.ultrasonic.service.PlatformAudioFocusRequester
import timber.log.Timber

/**
 * Debug only probe that verifies the audio focus handling on a real device.
 *
 * The service plays a silent player, asks for the audio focus and prints the volume of the
 * player whenever it is asked to. A second service in a separate process asks for a ducking
 * focus, which is what a navigation app does while it speaks. The volume of the probe has to
 * drop to 40 % and come back to 100 % afterwards.
 *
 * This is only part of the debug variant and is not shipped in the release build.
 */
class DuckProbeService : Service() {

    private var player: ExoPlayer? = null
    private var focusManager: CarAudioFocusManager? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // The focus is handled by CarAudioFocusManager, not by ExoPlayer.
                false
            )
            .build()
        player = exo
        focusManager = CarAudioFocusManager(PlatformAudioFocusRequester(this)) { exo }
        Timber.i("DUCK-PROBE: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_VOLUME -> reportVolume()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun start() {
        val exo = player ?: return
        focusManager?.onPlaybackStarted()
        // Pretend that the music is playing, so that the focus is held like in the real service.
        exo.playWhenReady = true
        Timber.i("DUCK-PROBE: started, volume=%s", exo.volume)
        reportVolume()
    }

    private fun reportVolume() {
        val exo = player ?: return
        val multiplier = readInternalVolumeMultiplier(exo)
        Timber.i(
            "DUCK-PROBE: volume=%.3f internalMultiplier=%s",
            exo.volume,
            multiplier?.toString() ?: "n/a"
        )
    }

    /**
     * Reads the internal volume multiplier of ExoPlayer. It is 1.0 as long as ExoPlayer does not
     * handle the audio focus itself, and 0.2 while another app holds a ducking focus.
     */
    private fun readInternalVolumeMultiplier(exo: ExoPlayer): Float? {
        return try {
            val implField = ExoPlayer::class.java.getDeclaredField("impl")
            implField.isAccessible = true
            val impl = implField.get(exo)
            val focusField = impl.javaClass.getDeclaredField("audioFocusManager")
            focusField.isAccessible = true
            val focusManager = focusField.get(impl)
            val method = focusManager.javaClass
                .getDeclaredMethod("getVolumeMultiplier")
            method.isAccessible = true
            method.invoke(focusManager) as? Float
        } catch (e: Exception) {
            Timber.w(e, "DUCK-PROBE: could not read the internal volume multiplier")
            null
        }
    }

    override fun onDestroy() {
        focusManager?.abandonFocus()
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "org.moire.ultrasonic.debug.DUCK_START"
        const val ACTION_VOLUME = "org.moire.ultrasonic.debug.DUCK_VOLUME"
        const val ACTION_STOP = "org.moire.ultrasonic.debug.DUCK_STOP"
    }
}

/**
 * Debug only service that behaves like a navigation app: it runs in its own process and asks
 * for a ducking focus while it "speaks".
 */
class DuckTriggerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Timber.i("DUCK-TRIGGER: focus change %d", change)
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DUCK -> duck()
            ACTION_RELEASE -> release()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun duck() {
        val manager = audioManager ?: return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener, handler)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        Timber.i("DUCK-TRIGGER: ducking focus request result %d", result)
    }

    private fun release() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }
        Timber.i("DUCK-TRIGGER: released the focus")
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_DUCK = "org.moire.ultrasonic.debug.DUCK_TRIGGER_DUCK"
        const val ACTION_RELEASE = "org.moire.ultrasonic.debug.DUCK_TRIGGER_RELEASE"
        const val ACTION_STOP = "org.moire.ultrasonic.debug.DUCK_TRIGGER_STOP"
    }
}
