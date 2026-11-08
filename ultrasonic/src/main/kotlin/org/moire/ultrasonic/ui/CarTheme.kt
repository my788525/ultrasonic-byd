/*
 * CarTheme.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import org.moire.ultrasonic.R
import org.moire.ultrasonic.util.Settings

/**
 * Paints the parts of the UI that a theme attribute cannot express: the window gradient and the
 * gradient cards of the home screen.
 *
 * Everything is drawn as a [GradientDrawable] instead of a bitmap or a blur, because the head
 * unit runs Android 7 without hardware support for blur or render effects. Gradients are cheap
 * there: they are rasterized once per size and reused by the GPU.
 */
object CarTheme {

    /** Darkest to brightest ratio of the window gradient stops. */
    private const val WINDOW_MIDDLE_STOP = 0.55f

    /** Alpha of the accent that the cards fade out of. */
    private const val CARD_ACCENT_ALPHA = 0x3D

    /** Alpha of the accent used for the card outline. */
    private const val CARD_OUTLINE_ALPHA = 0x33

    /** How far the surfaces are pulled towards black at night. */
    private const val NIGHT_DIM_RATIO = 0.32f

    /**
     * True when the head unit switched to its night mode and the app is set to follow it.
     *
     * DiLink flips the system day/night mode at dusk, so reading the UI mode is enough: no
     * clock, no sensor and no extra permission is needed, and the app matches the map and the
     * dashboard instead of fighting them.
     */
    @JvmStatic
    fun isNight(context: Context): Boolean {
        if (!Settings.followsNight) return false
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Pulls a surface towards black at night. Everything the UI draws goes through this, so the
     * whole interface gets dimmer together instead of one view at a time.
     */
    @ColorInt
    fun tone(context: Context, @ColorInt color: Int): Int =
        if (isNight(context)) mix(color, Color.BLACK, NIGHT_DIM_RATIO) else color

    @ColorInt
    fun color(context: Context, @ColorRes colorRes: Int): Int =
        ContextCompat.getColor(context, colorRes)

    @ColorInt
    fun accent(context: Context, palette: CarPalette = CarPalette.current()): Int =
        tone(context, ContextCompat.getColor(context, palette.accent))

    @ColorInt
    fun onSurface(context: Context): Int =
        ContextCompat.getColor(context, R.color.pal_text_primary)

    @ColorInt
    fun onSurfaceSecondary(context: Context): Int =
        ContextCompat.getColor(context, R.color.pal_text_secondary)

    /**
     * Replaces the flat window background with the gradient of the active palette, so the whole
     * app sits on one continuous surface instead of stacked rectangles.
     */
    fun applyWindow(activity: Activity, palette: CarPalette = CarPalette.current()) {
        val context = activity.applicationContext
        val top = tone(context, ContextCompat.getColor(context, palette.bgTop))
        val bottom = tone(context, ContextCompat.getColor(context, palette.bgBottom))
        val middle = mix(top, bottom, WINDOW_MIDDLE_STOP)

        activity.window?.setBackgroundDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(top, middle, bottom)
            )
        )
        // Android 7 draws a lighter default scrim on a transparent status bar, which shows up as
        // a bright stripe above the dark UI. Painting it with the palette keeps the top edge dark.
        activity.window?.statusBarColor = top
        activity.window?.navigationBarColor = bottom
    }

    /**
     * Background of a home screen card: the accent fades into the surface color along the card,
     * which gives the flat dark layout some depth without a single image asset.
     */
    fun cardBackground(
        context: Context,
        palette: CarPalette = CarPalette.current(),
        cornerDp: Float = 22f,
        vertical: Boolean = false
    ): GradientDrawable {
        val accent = withAlpha(accent(context, palette), CARD_ACCENT_ALPHA)
        val surface = tone(context, ContextCompat.getColor(context, palette.surface))
        val edge = tone(context, ContextCompat.getColor(context, palette.accentSoft))

        return GradientDrawable(
            if (vertical) GradientDrawable.Orientation.TOP_BOTTOM
            else GradientDrawable.Orientation.TL_BR,
            intArrayOf(accent, edge, surface)
        ).apply {
            this.cornerRadius = dp(context, cornerDp)
            setStroke(
                dp(context, 1f).toInt(),
                withAlpha(ContextCompat.getColor(context, palette.accent), CARD_OUTLINE_ALPHA)
            )
        }
    }

    /**
     * Background of a grid tile. [shift] rotates the gradient a little between the tiles of the
     * grid, so eight identical tiles still read as one designed surface instead of a flat block.
     */
    fun tileBackground(
        context: Context,
        shift: Int,
        palette: CarPalette = CarPalette.current(),
        cornerDp: Float = 20f
    ): GradientDrawable {
        val accent = withAlpha(
            accent(context, palette),
            0x2A + (shift % 3) * 0x08
        )
        val surface = tone(context, ContextCompat.getColor(context, palette.surface))
        val surfaceAlt = tone(context, ContextCompat.getColor(context, palette.surfaceAlt()))

        val orientation = when (shift % 3) {
            0 -> GradientDrawable.Orientation.TL_BR
            1 -> GradientDrawable.Orientation.BL_TR
            else -> GradientDrawable.Orientation.TOP_BOTTOM
        }

        return GradientDrawable(orientation, intArrayOf(accent, surface, surfaceAlt)).apply {
            this.cornerRadius = dp(context, cornerDp)
            setStroke(
                dp(context, 1f).toInt(),
                withAlpha(ContextCompat.getColor(context, palette.accent), 0x22)
            )
        }
    }

    /** Round gradient button, used for the main play control of the home screen. */
    fun accentButton(
        context: Context,
        palette: CarPalette = CarPalette.current()
    ): GradientDrawable {
        val accent = accent(context, palette)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(lighten(accent, 0.18f), accent, darken(accent, 0.18f))
        ).apply {
            shape = GradientDrawable.OVAL
        }
    }

    /** Rounded surface or highlight pill drawable with an optional 1dp border. */
    fun roundedFill(
        context: Context,
        @ColorInt fillColor: Int,
        @ColorInt strokeColor: Int = Color.TRANSPARENT,
        cornerDp: Float = 14f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerDp)
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(context, 1f).toInt(), strokeColor)
            }
        }
    }

    @ColorInt
    fun surfaceColor(context: Context, palette: CarPalette = CarPalette.current()): Int =
        tone(context, ContextCompat.getColor(context, palette.surface))

    @ColorInt
    fun surfaceAltColor(context: Context, palette: CarPalette = CarPalette.current()): Int =
        tone(context, ContextCompat.getColor(context, palette.surfaceAlt()))

    private fun CarPalette.surfaceAlt(): Int = when (this) {
        CarPalette.GRAPHITE -> R.color.pal_graphite_surface_alt
        CarPalette.OCEAN -> R.color.pal_ocean_surface_alt
        CarPalette.AURORA -> R.color.pal_aurora_surface_alt
        CarPalette.VIOLET -> R.color.pal_violet_surface_alt
        CarPalette.BORDEAUX -> R.color.pal_bordeaux_surface_alt
        CarPalette.AMBER -> R.color.pal_amber_surface_alt
    }

    @ColorInt
    fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Linear interpolation between two colors, used for the middle stop of the gradient. */
    @ColorInt
    fun mix(@ColorInt from: Int, @ColorInt to: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        return Color.argb(
            Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * clamped).toInt(),
            Color.red(from) + ((Color.red(to) - Color.red(from)) * clamped).toInt(),
            Color.green(from) + ((Color.green(to) - Color.green(from)) * clamped).toInt(),
            Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * clamped).toInt()
        )
    }

    @ColorInt
    private fun lighten(@ColorInt color: Int, ratio: Float): Int = mix(color, Color.WHITE, ratio)

    @ColorInt
    private fun darken(@ColorInt color: Int, ratio: Float): Int = mix(color, Color.BLACK, ratio)

    /**
     * The accent colour, brightened so that it stays readable as text.
     *
     * The palette accent is a rather dark blue. It works well for a button, but as the
     * colour of a text over a dark map it has too little contrast. The floating window
     * has no background when it is fully transparent, so the text has to carry itself.
     */
    @ColorInt
    fun readableAccent(
        context: Context,
        palette: CarPalette = CarPalette.current()
    ): Int = lighten(ContextCompat.getColor(context, palette.accent), 0.35f)

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
}
