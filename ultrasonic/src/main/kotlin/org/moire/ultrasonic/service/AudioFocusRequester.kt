/*
 * AudioFocusRequester.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Abstraction over the platform audio focus API.
 *
 * The abstraction exists so that the ducking logic in [CarAudioFocusManager] can be tested
 * without a device: a test can hand over a fake implementation and simulate the focus changes
 * that a navigation app would cause.
 */
interface AudioFocusRequester {
    /**
     * Requests the audio focus.
     *
     * @param onFocusChanged called with the focus change constants of [AudioManager].
     * @return true if the focus was granted.
     */
    fun request(onFocusChanged: (Int) -> Unit): Boolean

    /** Abandons the audio focus. */
    fun abandon()
}

/**
 * [AudioFocusRequester] that talks to the [AudioManager] of the platform.
 */
class PlatformAudioFocusRequester(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : AudioFocusRequester {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null

    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun request(onFocusChanged: (Int) -> Unit): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            // The listener is not necessarily called on the main thread, but the player may only
            // be touched from the thread that owns it.
            handler.post { onFocusChanged(focusChange) }
        }
        focusListener = listener
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestFocusV26(listener)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            focusListener?.let { audioManager.abandonAudioFocus(it) }
        }
        focusListener = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestFocusV26(
        listener: AudioManager.OnAudioFocusChangeListener
    ): Int {
        val request = Api26Impl.createRequest(listener, handler)
        focusRequest = request
        return audioManager.requestAudioFocus(request)
    }

    /**
     * The focus request API was added in Android 8. The calls are kept in a separate class, so
     * that the version check does not have to be repeated for every single call.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26Impl {
        fun createRequest(
            listener: AudioManager.OnAudioFocusChangeListener,
            handler: Handler
        ): AudioFocusRequest {
            return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener, handler)
                .build()
        }
    }
}
