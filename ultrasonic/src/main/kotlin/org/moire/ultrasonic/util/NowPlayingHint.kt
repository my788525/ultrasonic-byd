/*
 * NowPlayingHint.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import androidx.media3.common.Player
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MediaPlayerManager

/**
 * Decides when the song title area should temporarily announce the next track.
 *
 * A driver glances at the header, not at the queue. Two short windows are useful without
 * hiding the current song for most of the track:
 *  - from 10 s to 20 s after a song starts, and
 *  - the last 15 s of a song (never for tracks shorter than 15 s).
 *
 * Outside those windows the header goes back to the current song. Everything here is pure
 * presentation logic; no data is invented. When the queue has no next track, or the player is
 * repeating one song, no hint is shown at all.
 */
object NowPlayingHint {

    private const val WINDOW_START_MS = 10_000
    private const val WINDOW_END_MS = 20_000
    private const val TAIL_MS = 15_000

    /** True when the header should show the next track instead of the current one. */
    fun shouldShowNext(positionMs: Int, durationMs: Int): Boolean {
        val position = positionMs.coerceAtLeast(0)
        if (position in WINDOW_START_MS until WINDOW_END_MS) return true
        return durationMs > TAIL_MS && position >= durationMs - TAIL_MS
    }

    /**
     * The track that will play after this one, in play order (shuffle aware).
     * Null when the queue ends, repeats a single song, or cannot be read.
     */
    fun nextTrack(manager: MediaPlayerManager): Track? {
        if (manager.repeatMode == Player.REPEAT_MODE_ONE) return null
        val items = runCatching { manager.playlistInPlayOrder }.getOrNull().orEmpty()
        if (items.isEmpty()) return null
        val current = runCatching { manager.getCurrentShuffleIndex() }.getOrDefault(-1)
        if (current < 0) return null
        val next = items.getOrNull(current + 1)
            ?: if (manager.repeatMode == Player.REPEAT_MODE_ALL) items.firstOrNull() else null
        return next?.toTrack()
    }

    /**
     * Same answer for the floating window, which owns a Media3 [Player] instead of the
     * [MediaPlayerManager]. The player already accounts for shuffle, repeat mode and the end
     * of the queue, so its own "next" index is the most accurate source available.
     */
    fun nextTrack(player: Player?): Track? {
        val p = player ?: return null
        if (p.repeatMode == Player.REPEAT_MODE_ONE) return null
        val index = p.nextMediaItemIndex
        if (index < 0 || index >= p.mediaItemCount) return null
        return runCatching { p.getMediaItemAt(index).toTrack() }.getOrNull()
    }

    /**
     * The next [count] distinct tracks in play order, shuffle and repeat aware.
     *
     * Used by the fullscreen header, where both upcoming songs stay on screen all the time
     * instead of replacing the current title for a few seconds. A hard step budget guarantees
     * termination even for a single-song queue with repeat-all turned on.
     */
    fun upNextTracks(manager: MediaPlayerManager, count: Int): List<Track> {
        if (count <= 0 || manager.repeatMode == Player.REPEAT_MODE_ONE) return emptyList()
        val items = runCatching { manager.playlistInPlayOrder }.getOrNull().orEmpty()
        if (items.isEmpty()) return emptyList()
        val current = runCatching { manager.getCurrentShuffleIndex() }.getOrDefault(-1)
        if (current < 0) return emptyList()
        val repeatAll = manager.repeatMode == Player.REPEAT_MODE_ALL
        val currentId = runCatching { items.getOrNull(current)?.toTrack()?.id }.getOrNull()
        val result = ArrayList<Track>(count)
        var index = current + 1
        var steps = 0
        val budget = items.size * 2 + count
        while (result.size < count && steps < budget) {
            steps++
            if (index >= items.size) {
                if (!repeatAll) break
                index = 0
            }
            val track = runCatching { items.getOrNull(index)?.toTrack() }.getOrNull()
            if (track != null && track.id != currentId && result.none { it.id == track.id }) {
                result.add(track)
            }
            index++
        }
        return result
    }

    /** Title line: an explicit "next" prefix followed by the track name. */
    fun title(context: Context, track: Track): String =
        context.getString(R.string.next_track_title, track.title.orEmpty())

    /** Subtitle line: names the coming artist and album, with an explicit "coming up" prefix. */
    fun subtitle(context: Context, track: Track): String {
        val detail = listOfNotNull(track.artist, track.album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return if (detail.isBlank()) {
            context.getString(R.string.next_track_subtitle_unknown)
        } else {
            context.getString(R.string.next_track_subtitle, detail)
        }
    }
}
