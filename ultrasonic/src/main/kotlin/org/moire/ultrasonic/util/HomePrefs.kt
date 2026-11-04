/*
 * HomePrefs.kt
 *
 * Persistence for the customised car home screen: the order of the music library tiles, a cached
 * copy of the server playlist list, a per-playlist resume position, and a local play count used to
 * surface the driver's most-played playlists on the home screen.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.content.SharedPreferences
import org.moire.ultrasonic.app.UApp

/**
 * A playlist shown on the home screen.
 *
 * @param id   server playlist id (stable across sessions)
 * @param name playlist name, cached so the tile can render without a network call
 */
data class PinnedPlaylist(val id: String, val name: String)

/**
 * Saved playback position for a playlist.
 *
 * @param name   cached playlist name
 * @param index  zero based index of the track that was playing
 * @param position  playback position inside that track, in milliseconds
 */
data class PlaylistProgress(val name: String, val index: Int, val position: Int)

object HomePrefs {

    private const val FILE = "home_prefs"
    private const val KEY_TILE_ORDER = "tile_order"
    private const val KEY_CACHED = "cached_playlists"
    private const val KEY_PLAY_COUNT = "playlist_play_count"

    private fun prefs(): SharedPreferences =
        UApp.applicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Returns the saved order of the music-library tiles, or an empty list on first run. */
    fun getTileOrder(): List<String> {
        val raw = prefs().getString(KEY_TILE_ORDER, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    /** Persists the order of the music-library tiles (a list of stable tile keys). */
    fun setTileOrder(order: List<String>) {
        prefs().edit().putString(KEY_TILE_ORDER, order.joinToString(",")).apply()
    }

    /**
     * A cached copy of the server playlist list (id + name only), so the home screen can show
     * the driver's playlists instantly without waiting for the network. Refreshed in the
     * background whenever the home screen is opened.
     */
    fun getCachedPlaylists(): List<PinnedPlaylist> {
        val raw = prefs().getString(KEY_CACHED, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("\\u0001")
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                PinnedPlaylist(parts[0], parts[1])
            } else {
                null
            }
        }
    }

    fun setCachedPlaylists(list: List<PinnedPlaylist>) {
        val raw = list.joinToString(";") { "${it.id}\\u0001${it.name}" }
        prefs().edit().putString(KEY_CACHED, raw).apply()
    }

    /** Reads the local play count for a playlist (how many times it was launched from home). */
    fun getPlayCount(id: String): Int = getAllPlayCounts()[id] ?: 0

    /** A map of every known playlist id to its local play count. */
    fun getAllPlayCounts(): Map<String, Int> {
        val raw = prefs().getString(KEY_PLAY_COUNT, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val map = mutableMapOf<String, Int>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("\\u0001")
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                map[parts[0]] = parts[1].toIntOrNull() ?: 0
            }
        }
        return map
    }

    /** Records one more home launch for the playlist and returns the new total. */
    fun incrementPlayCount(id: String): Int {
        val next = getPlayCount(id) + 1
        val map = getAllPlayCounts().toMutableMap()
        map[id] = next
        val raw = map.entries.joinToString(";") { "${it.key}\\u0001${it.value}" }
        prefs().edit().putString(KEY_PLAY_COUNT, raw).apply()
        return next
    }
}

object PlaylistResumeStore {

    private const val FILE = "playlist_resume"
    private const val KEY_PREFIX = "resume_"

    private fun prefs(): SharedPreferences =
        UApp.applicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Reads the saved resume position for a playlist, or null when none exists. */
    fun get(id: String): PlaylistProgress? {
        val raw = prefs().getString(KEY_PREFIX + id, null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 3) return null
        return PlaylistProgress(
            name = parts[0],
            index = parts[1].toIntOrNull() ?: 0,
            position = parts[2].toIntOrNull() ?: 0
        )
    }

    /** Saves (or overwrites) the resume position for a playlist. */
    fun save(id: String, name: String, index: Int, position: Int) {
        prefs().edit().putString(KEY_PREFIX + id, "$name|$index|$position").apply()
    }
}
