/*
 * PreloadPolicyTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests how many songs are kept on disk ahead of the current one.
 *
 * The case that matters on a head unit is the one where the preloading is switched off.
 * The general preload setting defaults to "no limit", so falling back to it would download
 * the complete queue and fill the storage of the car.
 */
class PreloadPolicyTest {

    @Test
    fun `the preloading switched off downloads nothing`() {
        // The general setting asks for everything, but "off" has to win.
        assertEquals(0, PreloadPolicy.count(carPreload = 0, preloadCount = Int.MAX_VALUE))
        assertEquals(0, PreloadPolicy.count(carPreload = 0, preloadCount = 3))
        assertEquals(0, PreloadPolicy.count(carPreload = 0, preloadCount = 1))
    }

    @Test
    fun `a negative car setting is also off`() {
        assertEquals(0, PreloadPolicy.count(carPreload = -1, preloadCount = Int.MAX_VALUE))
    }

    @Test
    fun `one song ahead is allowed`() {
        assertEquals(1, PreloadPolicy.count(carPreload = 1, preloadCount = Int.MAX_VALUE))
        assertEquals(1, PreloadPolicy.count(carPreload = 1, preloadCount = 3))
    }

    @Test
    fun `two songs ahead are allowed`() {
        assertEquals(2, PreloadPolicy.count(carPreload = 2, preloadCount = Int.MAX_VALUE))
        assertEquals(2, PreloadPolicy.count(carPreload = 2, preloadCount = 3))
    }

    @Test
    fun `the smaller of the two settings wins`() {
        // The general setting asks for one song, the car setting allows two.
        assertEquals(1, PreloadPolicy.count(carPreload = 2, preloadCount = 1))
        // The general setting asks for five, the car setting allows two.
        assertEquals(2, PreloadPolicy.count(carPreload = 2, preloadCount = 5))
    }

    @Test
    fun `an unlimited general setting is capped by the car setting`() {
        // This is the default combination that would fill the storage of the head unit.
        assertEquals(1, PreloadPolicy.count(carPreload = 1, preloadCount = Int.MAX_VALUE))
        assertEquals(2, PreloadPolicy.count(carPreload = 2, preloadCount = Int.MAX_VALUE))
    }

    @Test
    fun `an unlimited general setting with the preloading off stays off`() {
        // The exact combination of the defaults with the preloading switched off.
        assertEquals(0, PreloadPolicy.count(carPreload = 0, preloadCount = Int.MAX_VALUE))
    }
}
