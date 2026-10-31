/*
 * UiState.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

/**
 * Runtime layout facts which other components need to know about.
 *
 * When the two pane layout is active the player is always visible on the right, so starting
 * playback must not navigate away from the list the user is browsing.
 */
object UiState {

    @JvmStatic
    @Volatile
    var twoPane: Boolean = false
}
