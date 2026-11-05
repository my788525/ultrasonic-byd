/*
 * AudioFxPresets.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.audiofx

import org.moire.ultrasonic.R
import timber.log.Timber

/**
 * Car-oriented sound-effect presets.
 *
 * Each preset combines a graphic-EQ curve (reused from [EqualizerPresets]) with the three
 * native effect modules that the head unit exposes: [EqualizerController.bassBoost],
 * [EqualizerController.virtualizer] and [EqualizerController.loudness]. On a car the most
 * useful profiles are different from a phone: a "driving" profile lifts mids and loudness so
 * vocals cut through road noise, while a "bass" profile adds body without muddying the cabin.
 */
object AudioFxPresets {

    class Preset(
        /** Resource id of the user-visible name. */
        val nameRes: Int,
        /** Graphic-EQ curve applied to the [android.media.audiofx.Equalizer]. */
        val eq: EqualizerPresets.Preset,
        /** BassBoost strength, 0..1000. */
        val bass: Int,
        /** Virtualizer (3D / surround) strength, 0..1000. */
        val virtualizer: Int,
        /** LoudnessEnhancer target gain, in millibels. */
        val loudness: Int
    )

    /** Special preset that turns the whole suite off. */
    val off = Preset(
        R.string.fx_preset_off,
        EqualizerPresets.all[0],
        0,
        0,
        0
    )

    val default = Preset(
        R.string.fx_preset_default,
        EqualizerPresets.all[0],
        250,
        150,
        0
    )

    /** Extra low end for electronic / hip-hop in the cabin. */
    val bass = Preset(
        R.string.fx_preset_bass,
        EqualizerPresets.all[6],
        900,
        200,
        200
    )

    /** Lifts the vocal band so lyrics stay intelligible over road noise. */
    val vocal = Preset(
        R.string.fx_preset_vocal,
        EqualizerPresets.all[8],
        150,
        100,
        300
    )

    /** Wide, spacious staging for live / ambient recordings. */
    val surround = Preset(
        R.string.fx_preset_surround,
        EqualizerPresets.all[0],
        200,
        850,
        200
    )

    /** All-round "in the car" profile: mids up, louder, a touch of bass. */
    val driving = Preset(
        R.string.fx_preset_driving,
        EqualizerPresets.all[0],
        500,
        450,
        450
    )

    val all: List<Preset> = listOf(off, default, bass, vocal, surround, driving)

    /**
     * Applies [preset] to [controller] and switches the suite on (except for [off]).
     */
    fun apply(controller: EqualizerController, preset: Preset) {
        try {
            if (preset === off) {
                controller.setBassBoost(0)
                controller.setVirtualizer(0)
                controller.setLoudness(0)
                controller.setMasterEnabled(false)
                return
            }

            controller.equalizer?.let { EqualizerPresets.apply(it, preset.eq) }
            controller.setBassBoost(preset.bass)
            controller.setVirtualizer(preset.virtualizer)
            controller.setLoudness(preset.loudness)
            controller.setMasterEnabled(true)
        } catch (all: Throwable) {
            Timber.w(all, "Could not apply the sound-effect preset")
        }
    }
}
