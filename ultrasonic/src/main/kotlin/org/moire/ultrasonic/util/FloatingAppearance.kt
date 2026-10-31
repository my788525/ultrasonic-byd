/*
 * FloatingAppearance.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

/**
 * The look of the floating window: how transparent its background is and which colour its text
 * uses.
 *
 * The floating window is drawn over the navigation map, so it has two jobs that pull in opposite
 * directions. It must not hide the road, and it must still be readable over whatever the map
 * shows underneath. The background opacity answers the first job, the text colour the second one.
 *
 * The colour is stored as a name instead of a number, so a colour can be re-tuned later without
 * invalidating the value that is already stored on the car.
 */
object FloatingAppearance {

    /** Fully opaque, the default look of the window. */
    const val OPACITY_OPAQUE = 100

    /** No background at all: only the text is drawn over the map. */
    const val OPACITY_TRANSPARENT = 0

    /** The name of the colour that follows the day/night mode of the head unit. */
    const val COLOR_THEME = "theme"

    /** The name of the colour that is always white. */
    const val COLOR_WHITE = "white"

    /** A warm yellow, the most readable colour over a dark map. */
    const val COLOR_AMBER = "amber"

    /** The accent colour of the current palette. */
    const val COLOR_ACCENT = "accent"

    /** A bright green that stays readable over both a dark map and a bright one. */
    const val COLOR_NEON_GREEN = "neon_green"

    /** A cyan that pops against the amber of most navigation maps. */
    const val COLOR_CYAN = "cyan"

    /** A magenta / hot pink, the most eye-catching choice for a quick glance. */
    const val COLOR_MAGENTA = "magenta"

    /** A warm orange, easy to tell apart from the yellow of road markings. */
    const val COLOR_ORANGE = "orange"

    /** A pure red, for when the window has to be impossible to miss. */
    const val COLOR_RED = "red"

    /** A bright yellow, the classic high-visibility colour. */
    const val COLOR_YELLOW = "yellow"

    /**
     * A dark grey, the most readable choice over a pale map (the day mode of most navigation
     * apps is a light grey/white). It relies on a low background opacity, because a dark plate
     * behind dark text would swallow it.
     */
    const val COLOR_DARK_GRAY = "dark_gray"

    /**
     * A bright blue, distinct from the amber/yellow route lines of a navigation map and from the
     * deep blue of its night mode, so the text stays readable on either map.
     */
    const val COLOR_BLUE = "blue"

    /**
     * A vivid violet, the colour that stands out most against the yellow/amber palette of a
     * navigation map, for when the driver wants the window to be impossible to miss.
     */
    const val COLOR_VIOLET = "violet"

    /** The colours the driver can pick, in the order they are offered. */
    val COLOR_NAMES = listOf(
        COLOR_THEME,
        COLOR_WHITE,
        COLOR_AMBER,
        COLOR_ACCENT,
        COLOR_NEON_GREEN,
        COLOR_CYAN,
        COLOR_MAGENTA,
        COLOR_ORANGE,
        COLOR_RED,
        COLOR_YELLOW,
        COLOR_DARK_GRAY,
        COLOR_BLUE,
        COLOR_VIOLET
    )

    /**
     * The alpha of the window background for the given opacity.
     *
     * @param opacity the opacity in percent, 0 to 100
     * @return the alpha channel for the background colour, 0 to 255
     */
    fun backgroundAlpha(opacity: Int): Int {
        val clamped = opacity.coerceIn(OPACITY_TRANSPARENT, OPACITY_OPAQUE)
        return (clamped * 255) / OPACITY_OPAQUE
    }

    /**
     * The alpha of the text for the given opacity.
     *
     * A fully transparent window has no dark plate behind the text any more, so the text has to
     * carry its own contrast. A shadow does that without a background, and the alpha of the
     * shadow grows as the plate fades away.
     */
    fun shadowAlpha(opacity: Int): Int {
        val clamped = opacity.coerceIn(OPACITY_TRANSPARENT, OPACITY_OPAQUE)
        // No shadow at all on an opaque window, a strong one on a transparent one.
        val missing = OPACITY_OPAQUE - clamped
        return (missing * 200) / OPACITY_OPAQUE
    }

    /**
     * The colour for the given name, or null when the name is unknown.
     *
     * The theme colour cannot be resolved here, because it depends on the palette and the day and
     * night mode, so the caller resolves it.
     */
    fun colorFor(name: String?): String? {
        return COLOR_NAMES.firstOrNull { it == name }
    }
}
