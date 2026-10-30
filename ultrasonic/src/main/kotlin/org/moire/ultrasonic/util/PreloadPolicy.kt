/*
 * PreloadPolicy.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

/**
 * Decides how many songs are kept on disk ahead of the one that is playing.
 *
 * On a head unit the storage is small and the download of a long queue would fill it, so
 * the car setting caps the queue. The cap is a hard limit: even when the general preload
 * setting asks for more, the car setting wins.
 *
 * The calculation lives in its own class so that it can be tested without a player and
 * without a server.
 */
object PreloadPolicy {

    /**
     * The number of songs that are kept on disk ahead of the current one.
     *
     * @param carPreload the car setting: 0 switches the preloading off, 1 and 2 allow one
     *   or two songs.
     * @param preloadCount the general preload setting. A negative value means "no limit".
     * @return the number of songs to download, 0 when the preloading is switched off.
     */
    fun count(carPreload: Int, preloadCount: Int): Int {
        // "Off" means off. Falling back to the general setting here would use its
        // "unlimited" default and download the complete queue, which is the exact opposite
        // of what the user asked for and would fill the storage of the head unit.
        if (carPreload <= 0) return 0

        // The general setting may ask for everything, the car setting is the cap.
        if (preloadCount <= 0) return carPreload

        return minOf(preloadCount, carPreload)
    }
}
