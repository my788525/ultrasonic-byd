/*
 * FloatingAppearanceTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the look of the floating window.
 *
 * The window is drawn over the navigation map, so the background has to be able to fade out
 * completely, and the text has to stay readable when it does.
 */
class FloatingAppearanceTest {

    @Test
    fun `an opaque background is fully opaque`() {
        assertEquals(255, FloatingAppearance.backgroundAlpha(100))
    }

    @Test
    fun `a transparent background has no alpha at all`() {
        assertEquals(0, FloatingAppearance.backgroundAlpha(0))
    }

    @Test
    fun `the background alpha follows the opacity`() {
        assertEquals(127, FloatingAppearance.backgroundAlpha(50))
        assertEquals(229, FloatingAppearance.backgroundAlpha(90))
    }

    @Test
    fun `an opacity outside the range is clamped`() {
        // A value that was never written by the slider must not produce a broken colour.
        assertEquals(255, FloatingAppearance.backgroundAlpha(150))
        assertEquals(0, FloatingAppearance.backgroundAlpha(-20))
    }

    @Test
    fun `an opaque background needs no shadow`() {
        assertEquals(0, FloatingAppearance.shadowAlpha(100))
    }

    @Test
    fun `a transparent background needs a strong shadow`() {
        // Without the plate behind it, the text would be unreadable over a bright map.
        assertTrue(FloatingAppearance.shadowAlpha(0) > 150)
        assertTrue(FloatingAppearance.shadowAlpha(0) > FloatingAppearance.shadowAlpha(50))
    }

    @Test
    fun `the shadow fades as the background becomes more solid`() {
        // The plate takes over the job of the shadow, so the shadow has to give way.
        var previous = Int.MAX_VALUE
        for (opacity in 0..100) {
            val shadow = FloatingAppearance.shadowAlpha(opacity)
            assertTrue(
                shadow <= previous,
                "the shadow must not grow when the opacity grows: $opacity"
            )
            previous = shadow
        }
        assertEquals(0, previous)
    }

    @Test
    fun `every offered colour is known`() {
        for (name in FloatingAppearance.COLOR_NAMES) {
            assertEquals(name, FloatingAppearance.colorFor(name))
        }
    }

    @Test
    fun `an unknown colour is not resolved`() {
        // A colour that was removed from the list must not crash the window.
        assertNull(FloatingAppearance.colorFor("chartreuse"))
        assertNull(FloatingAppearance.colorFor(null))
        assertNull(FloatingAppearance.colorFor(""))
    }
}
