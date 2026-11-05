/*
 * EqualizerController.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.audiofx

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.util.FileUtil.deserialize
import org.moire.ultrasonic.util.FileUtil.serialize
import timber.log.Timber

/**
 * Controls the native Android audio effects that are applied to the playback session.
 *
 * Beyond the classic graphic [Equalizer] this also manages [BassBoost], [Virtualizer]
 * (3D / surround) and [LoudnessEnhancer]. They are the same building blocks that apps such
 * as NetEase Cloud Music use for their "sound enhancement", and they run entirely on the
 * device audio HAL with zero extra CPU. Every effect is created defensively: if the car
 * head unit does not expose a given effect, the corresponding capability flag stays false
 * and the UI hides that control instead of crashing.
 *
 * The master switch ([isEnabled]) mutes every module at once. The "desired" strengths are
 * remembered across the mute so toggling the switch does not wipe the user's tuning.
 *
 * TODO: Maybe store the settings in the DB?
 */
class EqualizerController : CoroutineScope by CoroutineScope(Dispatchers.IO) {

    @JvmField
    var equalizer: Equalizer? = null

    @JvmField
    var bassBoost: BassBoost? = null

    @JvmField
    var virtualizer: Virtualizer? = null

    @JvmField
    var loudness: LoudnessEnhancer? = null

    /** Whether the device exposes each effect. False on head units that lack the engine. */
    var hasBassBoost = false
        private set
    var hasVirtualizer = false
        private set
    var hasLoudness = false
        private set

    /** Master switch for the whole sound-effects suite. */
    var isEnabled = false
        private set

    /** The user's tuning, remembered even while the suite is muted. */
    var desiredBass = 0
        private set
    var desiredVirtualizer = 0
        private set
    var desiredLoudness = 0
        private set

    private var audioSessionId = 0

    // ------------------------------------------------------------------
    // Setters: all guarded so a missing effect silently does nothing.
    // ------------------------------------------------------------------

    fun setMasterEnabled(enabled: Boolean) {
        isEnabled = enabled
        equalizer?.enabled = enabled
        if (enabled) applyDesired() else mute()
        saveSuite()
    }

    /** [strength] is 0..1000 (the Android convention for BassBoost / Virtualizer). */
    fun setBassBoost(strength: Int) {
        desiredBass = strength.coerceIn(0, 1000)
        if (isEnabled) bassBoost?.setStrength(desiredBass.toShort())
        saveSuite()
    }

    /** [strength] is 0..1000. */
    fun setVirtualizer(strength: Int) {
        desiredVirtualizer = strength.coerceIn(0, 1000)
        if (isEnabled) virtualizer?.setStrength(desiredVirtualizer.toShort())
        saveSuite()
    }

    /** [gain] is in millibels (e.g. 0..1000). */
    fun setLoudness(gain: Int) {
        desiredLoudness = gain.coerceAtLeast(0)
        if (isEnabled) loudness?.setTargetGain(desiredLoudness)
        saveSuite()
    }

    private fun applyDesired() {
        bassBoost?.setStrength(desiredBass.toShort())
        virtualizer?.setStrength(desiredVirtualizer.toShort())
        loudness?.setTargetGain(desiredLoudness)
    }

    private fun mute() {
        bassBoost?.setStrength(0)
        virtualizer?.setStrength(0)
        loudness?.setTargetGain(0)
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    fun saveSettings() {
        if (equalizer == null) return
        launch {
            try {
                serialize(
                    UApp.applicationContext(),
                    EqualizerSettings(equalizer!!),
                    "equalizer.dat"
                )
            } catch (all: Throwable) {
                Timber.w(all, "Failed to save equalizer settings.")
            }
        }
    }

    fun loadSettings() {
        if (equalizer == null) return
        launch {
            try {
                val settings = deserialize<EqualizerSettings>(
                    UApp.applicationContext(), "equalizer.dat"
                )
                settings?.apply(equalizer!!)
            } catch (all: Throwable) {
                Timber.w(all, "Failed to load equalizer settings.")
            }
        }
    }

    /** Persists the sound-effects suite (master + bass + virtualizer + loudness). */
    fun saveSuite() {
        launch {
            try {
                serialize(
                    UApp.applicationContext(),
                    AudioFxSuiteSettings(this@EqualizerController),
                    "audiofx.dat"
                )
            } catch (all: Throwable) {
                Timber.w(all, "Failed to save sound-effects suite.")
            }
        }
    }

    fun loadSuite() {
        if (equalizer == null) return
        launch {
            try {
                val settings = deserialize<AudioFxSuiteSettings>(
                    UApp.applicationContext(), "audiofx.dat"
                )
                settings?.apply(this@EqualizerController)
            } catch (all: Throwable) {
                Timber.w(all, "Failed to load sound-effects suite.")
            }
        }
    }

    private class EqualizerSettings(equalizer: Equalizer) : Serializable {
        private val bandLevels: ShortArray
        private var preset: Short = 0
        private val enabled: Boolean

        fun apply(equalizer: Equalizer) {
            for (i in bandLevels.indices) {
                equalizer.setBandLevel(i.toShort(), bandLevels[i])
            }
            if (preset >= 0 && preset < equalizer.numberOfPresets) {
                equalizer.usePreset(preset)
            }
            equalizer.enabled = enabled
        }

        init {
            enabled = equalizer.enabled
            bandLevels = ShortArray(equalizer.numberOfBands.toInt())
            for (i in 0 until equalizer.numberOfBands) {
                bandLevels[i] = equalizer.getBandLevel(i.toShort())
            }
            preset = try {
                equalizer.currentPreset
            } catch (ignored: Exception) {
                -1
            }
        }

        companion object {
            private const val serialVersionUID = 6269873247206061L
        }
    }

    private class AudioFxSuiteSettings(controller: EqualizerController) : Serializable {
        private val enabled = controller.isEnabled
        private val bass = controller.desiredBass
        private val virtualizer = controller.desiredVirtualizer
        private val loudness = controller.desiredLoudness

        fun apply(controller: EqualizerController) {
            controller.isEnabled = enabled
            controller.desiredBass = bass
            controller.desiredVirtualizer = virtualizer
            controller.desiredLoudness = loudness
            controller.equalizer?.enabled = enabled
            if (enabled) controller.applyDesired() else controller.mute()
        }

        companion object {
            private const val serialVersionUID = 7713479245312072112L
        }
    }

    companion object {
        private val instance = MutableLiveData<EqualizerController?>()

        /**
         * Retrieves the EqualizerController as LiveData
         */
        @JvmStatic
        fun get(): LiveData<EqualizerController?> {
            return instance
        }

        /**
         * Initializes the EqualizerController instance with a Session
         *
         * @param sessionId
         * @return the new controller
         */
        fun create(sessionId: Int): EqualizerController? {
            val controller = EqualizerController()
            return try {
                controller.audioSessionId = sessionId
                controller.equalizer = Equalizer(0, controller.audioSessionId)
                controller.bassBoost = createBassBoost(controller.audioSessionId)
                    ?.also { controller.hasBassBoost = true }
                controller.virtualizer = createVirtualizer(controller.audioSessionId)
                    ?.also { controller.hasVirtualizer = true }
                controller.loudness = createLoudness(controller.audioSessionId)
                    ?.also { controller.hasLoudness = true }
                controller.loadSettings()
                controller.loadSuite()
                instance.postValue(controller)
                controller
            } catch (all: Throwable) {
                Timber.w(all, "Failed to create equalizer.")
                null
            }
        }

        /**
         * Builds a [BassBoost]. Returns null when the head unit does not expose the effect.
         */
        private fun createBassBoost(sessionId: Int): BassBoost? = try {
            BassBoost(0, sessionId)
        } catch (all: Throwable) {
            Timber.i(all, "BassBoost not available on this device.")
            null
        }

        /**
         * Builds a [Virtualizer]. Returns null when the head unit does not expose the effect.
         * (The class is deprecated on API 35 in favour of Spatializer, but the insert effect
         * still works on the Android versions shipped on car head units.)
         */
        private fun createVirtualizer(sessionId: Int): Virtualizer? = try {
            Virtualizer(0, sessionId)
        } catch (all: Throwable) {
            Timber.i(all, "Virtualizer not available on this device.")
            null
        }

        /**
         * Builds a [LoudnessEnhancer]. Returns null when the head unit does not expose it.
         */
        private fun createLoudness(sessionId: Int): LoudnessEnhancer? = try {
            LoudnessEnhancer(sessionId)
        } catch (all: Throwable) {
            Timber.i(all, "LoudnessEnhancer not available on this device.")
            null
        }

        /**
         * Releases the EqualizerController instance when the underlying MediaPlayer is no longer available
         */
        fun release() {
            val controller = instance.value ?: return
            // The native effect is not there when the creation failed, and releasing it
            // twice would crash the app.
            controller.bassBoost?.release()
            controller.virtualizer?.release()
            controller.loudness?.release()
            controller.equalizer?.release()
            controller.bassBoost = null
            controller.virtualizer = null
            controller.loudness = null
            controller.equalizer = null
            instance.postValue(null)
        }
    }
}
