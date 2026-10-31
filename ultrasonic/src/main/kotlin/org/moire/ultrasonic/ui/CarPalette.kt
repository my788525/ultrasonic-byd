/*
 * CarPalette.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.moire.ultrasonic.R
import org.moire.ultrasonic.util.Settings

/**
 * The color schemes of the car UI.
 *
 * A head unit is used at night, at noon and in a tunnel, so every scheme is dark: the background
 * is a gradient between two near black tones instead of a flat color, the surfaces sit only a
 * little above the background and a single muted accent is used for controls. Pure white is
 * never used as a large area, only as text color.
 *
 * Each scheme is also a full theme (see themes_car.xml), so tabs, buttons, switches, seek bars and
 * dialogs all follow the accent without touching a single view.
 */
enum class CarPalette(
    val key: String,
    @StyleRes val styleRes: Int,
    @ColorRes val bgTop: Int,
    @ColorRes val bgBottom: Int,
    @ColorRes val surface: Int,
    @ColorRes val accent: Int,
    @ColorRes val accentSoft: Int,
    @StringRes val labelRes: Int
) {
    /** 石墨夜 · 中性冷灰，默认 */
    GRAPHITE(
        "graphite",
        R.style.UltrasonicTheme_Car_Graphite,
        R.color.pal_graphite_bg_top,
        R.color.pal_graphite_bg_bottom,
        R.color.pal_graphite_surface,
        R.color.pal_graphite_accent,
        R.color.pal_graphite_accent_soft,
        R.string.settings_palette_graphite
    ),

    /** 深海蓝 */
    OCEAN(
        "ocean",
        R.style.UltrasonicTheme_Car_Ocean,
        R.color.pal_ocean_bg_top,
        R.color.pal_ocean_bg_bottom,
        R.color.pal_ocean_surface,
        R.color.pal_ocean_accent,
        R.color.pal_ocean_accent_soft,
        R.string.settings_palette_ocean
    ),

    /** 极光青 */
    AURORA(
        "aurora",
        R.style.UltrasonicTheme_Car_Aurora,
        R.color.pal_aurora_bg_top,
        R.color.pal_aurora_bg_bottom,
        R.color.pal_aurora_surface,
        R.color.pal_aurora_accent,
        R.color.pal_aurora_accent_soft,
        R.string.settings_palette_aurora
    ),

    /** 暮紫 */
    VIOLET(
        "violet",
        R.style.UltrasonicTheme_Car_Violet,
        R.color.pal_violet_bg_top,
        R.color.pal_violet_bg_bottom,
        R.color.pal_violet_surface,
        R.color.pal_violet_accent,
        R.color.pal_violet_accent_soft,
        R.string.settings_palette_violet
    ),

    /** 酒红暮色 */
    BORDEAUX(
        "bordeaux",
        R.style.UltrasonicTheme_Car_Bordeaux,
        R.color.pal_bordeaux_bg_top,
        R.color.pal_bordeaux_bg_bottom,
        R.color.pal_bordeaux_surface,
        R.color.pal_bordeaux_accent,
        R.color.pal_bordeaux_accent_soft,
        R.string.settings_palette_bordeaux
    ),

    /** 琥珀夜 */
    AMBER(
        "amber",
        R.style.UltrasonicTheme_Car_Amber,
        R.color.pal_amber_bg_top,
        R.color.pal_amber_bg_bottom,
        R.color.pal_amber_surface,
        R.color.pal_amber_accent,
        R.color.pal_amber_accent_soft,
        R.string.settings_palette_amber
    );

    companion object {
        /** The scheme stored in the settings, or the default one for unknown or missing values. */
        @JvmStatic
        fun current(): CarPalette = fromKey(Settings.palette)

        @JvmStatic
        fun fromKey(key: String?): CarPalette {
            val normalized = key?.trim()?.lowercase()
            return values().firstOrNull { it.key == normalized } ?: GRAPHITE
        }
    }
}
