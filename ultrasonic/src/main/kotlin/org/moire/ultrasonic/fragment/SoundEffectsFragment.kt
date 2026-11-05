/*
 * SoundEffectsFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as RM
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.moire.ultrasonic.R
import org.moire.ultrasonic.audiofx.AudioFxPresets
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.util.Util.applyTheme
import timber.log.Timber

/**
 * The car-friendly "sound enhancement" panel.
 *
 * Exposes the native effect modules (bass boost, 3D surround, loudness) plus a master switch
 * and a row of car-oriented presets. The full graphic equalizer is reached through the
 * "advanced" button. Controls for effects the device does not expose are hidden.
 */
class SoundEffectsFragment : Fragment() {

    private var controller: EqualizerController? = null
    private val presetButtons = mutableListOf<MaterialButton>()

    private var masterSwitch: SwitchMaterial? = null
    private var presetsContainer: LinearLayout? = null
    private var bassRow: LinearLayout? = null
    private var virtualizerRow: LinearLayout? = null
    private var loudnessRow: LinearLayout? = null
    private var bassSeek: SeekBar? = null
    private var virtualizerSeek: SeekBar? = null
    private var loudnessSeek: SeekBar? = null
    private var bassValue: TextView? = null
    private var virtualizerValue: TextView? = null
    private var loudnessValue: TextView? = null
    private var unavailableNote: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(context)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sound_effects, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(this, R.string.fx_title)

        masterSwitch = view.findViewById(R.id.fx_master)
        presetsContainer = view.findViewById(R.id.fx_presets)
        bassRow = view.findViewById(R.id.fx_bass_row)
        virtualizerRow = view.findViewById(R.id.fx_virtualizer_row)
        loudnessRow = view.findViewById(R.id.fx_loudness_row)
        bassSeek = view.findViewById(R.id.fx_bass)
        virtualizerSeek = view.findViewById(R.id.fx_virtualizer)
        loudnessSeek = view.findViewById(R.id.fx_loudness)
        bassValue = view.findViewById(R.id.fx_bass_value)
        virtualizerValue = view.findViewById(R.id.fx_virtualizer_value)
        loudnessValue = view.findViewById(R.id.fx_loudness_value)
        unavailableNote = view.findViewById(R.id.fx_unavailable)

        buildPresetButtons()

        bassSeek?.setOnSeekBarChangeListener(sliderListener { v ->
            ensureEnabled()
            controller?.setBassBoost(v)
            updateValue(bassValue, v)
        })
        virtualizerSeek?.setOnSeekBarChangeListener(sliderListener { v ->
            ensureEnabled()
            controller?.setVirtualizer(v)
            updateValue(virtualizerValue, v)
        })
        loudnessSeek?.setOnSeekBarChangeListener(sliderListener { v ->
            ensureEnabled()
            controller?.setLoudness(v)
            updateValue(loudnessValue, v)
        })

        masterSwitch?.setOnCheckedChangeListener { _, enabled ->
            controller?.setMasterEnabled(enabled)
        }

        view.findViewById<MaterialButton>(R.id.fx_advanced)
            ?.setOnClickListener { findNavController().navigate(R.id.action_global_equalizer) }

        EqualizerController.get().observe(viewLifecycleOwner) { c ->
            controller = c
            if (c != null) bind(c) else clear()
        }
    }

    private fun sliderListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    /** Moving a slider turns the suite on immediately so the driver hears the change. */
    private fun ensureEnabled() {
        val c = controller ?: return
        if (!c.isEnabled) {
            c.setMasterEnabled(true)
            masterSwitch?.isChecked = true
        }
    }

    private fun updateValue(view: TextView?, progress: Int) {
        view?.text = "${progress * 100 / 1000}%"
    }

    private fun bind(c: EqualizerController) {
        masterSwitch?.isChecked = c.isEnabled

        bassRow?.visibility = if (c.hasBassBoost) View.VISIBLE else View.GONE
        virtualizerRow?.visibility = if (c.hasVirtualizer) View.VISIBLE else View.GONE
        loudnessRow?.visibility = if (c.hasLoudness) View.VISIBLE else View.GONE
        val missing = listOf(c.hasBassBoost, c.hasVirtualizer, c.hasLoudness).count { !it }
        unavailableNote?.visibility = if (missing > 0) View.VISIBLE else View.GONE

        bassSeek?.progress = c.desiredBass
        virtualizerSeek?.progress = c.desiredVirtualizer
        loudnessSeek?.progress = c.desiredLoudness
        updateValue(bassValue, c.desiredBass)
        updateValue(virtualizerValue, c.desiredVirtualizer)
        updateValue(loudnessValue, c.desiredLoudness)
    }

    private fun clear() {
        masterSwitch?.isChecked = false
        bassRow?.visibility = View.GONE
        virtualizerRow?.visibility = View.GONE
        loudnessRow?.visibility = View.GONE
    }

    private fun buildPresetButtons() {
        val container = presetsContainer ?: return
        container.removeAllViews()
        presetButtons.clear()

        val margin = (6 * resources.displayMetrics.density).toInt()

        AudioFxPresets.all.forEach { preset ->
            val button = MaterialButton(
                requireContext(),
                null,
                RM.attr.materialButtonOutlinedStyle
            )
            button.text = getString(preset.nameRes)
            button.textSize = 13f
            button.isAllCaps = false
            button.insetTop = 0
            button.insetBottom = 0
            button.minWidth = 0
            button.minimumWidth = 0
            button.setPadding(margin * 2, margin, margin * 2, margin)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = margin
            button.layoutParams = params

            button.setOnClickListener {
                controller?.let { c ->
                    AudioFxPresets.apply(c, preset)
                    bind(c)
                }
            }

            container.addView(button)
            presetButtons.add(button)
        }
    }

    override fun onPause() {
        super.onPause()
        controller?.saveSuite()
        controller?.saveSettings()
    }
}
