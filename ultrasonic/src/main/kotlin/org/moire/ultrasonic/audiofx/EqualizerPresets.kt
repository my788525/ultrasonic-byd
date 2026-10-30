/*
 * EqualizerPresets.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.audiofx

import android.media.audiofx.Equalizer
import kotlin.math.ln
import org.moire.ultrasonic.R
import timber.log.Timber

/**
 * Built in equalizer presets.
 *
 * The presets of [Equalizer] are provided by the device vendor and are frequently missing or
 * pointless on car head units, so the app ships its own set. Every preset is defined as a gain
 * curve over the ten bands of a classic graphic equalizer; the curve is interpolated (in log
 * frequency space) onto whatever bands the device actually exposes, which can be as few as five.
 */
object EqualizerPresets {

    /** Center frequencies of the reference curve, in Hz. */
    private val REFERENCE_FREQUENCIES = doubleArrayOf(
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    class Preset(
        val nameRes: Int,
        val gains: DoubleArray
    )

    /** The curves are the widely used ones of Winamp / iTunes / VLC style players. */
    val all: List<Preset> = listOf(
        Preset(R.string.eq_preset_flat, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset(R.string.eq_preset_pop, doubleArrayOf(-1.0, 1.0, 3.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.0, -2.0)),
        Preset(R.string.eq_preset_rock, doubleArrayOf(5.0, 4.0, 3.0, 1.0, -1.0, -1.0, 1.0, 3.0, 4.0, 5.0)),
        Preset(R.string.eq_preset_jazz, doubleArrayOf(3.0, 2.0, 1.0, 2.0, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0)),
        Preset(R.string.eq_preset_classical, doubleArrayOf(4.0, 3.0, 2.0, 1.0, -1.0, -1.0, 0.0, 2.0, 3.0, 4.0)),
        Preset(R.string.eq_preset_dance, doubleArrayOf(6.0, 5.0, 2.0, 0.0, 0.0, -2.0, -1.0, 2.0, 4.0, 5.0)),
        Preset(R.string.eq_preset_bass, doubleArrayOf(8.0, 7.0, 5.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset(R.string.eq_preset_treble, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 5.0, 6.0, 7.0)),
        Preset(R.string.eq_preset_vocal, doubleArrayOf(-2.0, -1.0, 0.0, 2.0, 4.0, 4.0, 3.0, 1.0, 0.0, -2.0)),
        Preset(R.string.eq_preset_electronic, doubleArrayOf(5.0, 4.0, 1.0, 0.0, -2.0, 2.0, 1.0, 3.0, 5.0, 5.0)),
        Preset(R.string.eq_preset_acoustic, doubleArrayOf(4.0, 3.0, 2.0, 1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 2.0)),
        Preset(R.string.eq_preset_loudness, doubleArrayOf(6.0, 4.0, 0.0, -2.0, -1.0, 1.0, 4.0, 5.0, 6.0, 6.0))
    )

    /**
     * Applies the preset to [equalizer] and switches the effect on.
     *
     * Band levels of the platform are expressed in millibel, and the available range differs
     * between devices, so every value is clamped into the reported range.
     */
    fun apply(equalizer: Equalizer, preset: Preset) {
        try {
            val range = equalizer.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()

            for (band in 0 until equalizer.numberOfBands) {
                val frequencyHz = equalizer.getBand(band) / 1000.0
                val gainMillibel = (gainAt(frequencyHz, preset.gains) * 100.0).toInt()
                equalizer.setBandLevel(
                    band.toShort(),
                    gainMillibel.coerceIn(min, max).toShort()
                )
            }

            equalizer.enabled = true
        } catch (all: Throwable) {
            Timber.w(all, "Could not apply the equalizer preset")
        }
    }

    /** Linear interpolation between the reference points, done on a log frequency axis. */
    private fun gainAt(frequencyHz: Double, gains: DoubleArray): Double {
        if (frequencyHz <= REFERENCE_FREQUENCIES.first()) return gains.first()
        if (frequencyHz >= REFERENCE_FREQUENCIES.last()) return gains.last()

        var index = 0
        while (index < REFERENCE_FREQUENCIES.size - 2 && REFERENCE_FREQUENCIES[index + 1] <= frequencyHz) {
            index++
        }

        val lower = REFERENCE_FREQUENCIES[index]
        val upper = REFERENCE_FREQUENCIES[index + 1]
        val position = (ln(frequencyHz) - ln(lower)) / (ln(upper) - ln(lower))

        return gains[index] + position * (gains[index + 1] - gains[index])
    }
}
