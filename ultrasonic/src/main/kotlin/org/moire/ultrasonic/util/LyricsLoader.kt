/*
 * LyricsLoader.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Lyrics
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MusicServiceFactory
import timber.log.Timber

enum class LyricsSource { NONE, SERVER, ONLINE }

/**
 * Everything the lyrics views need from one lookup: the timed lines, the plain text fallback,
 * and where the result came from so the driver can see whether word by word lyrics are available.
 */
data class LyricsLoadResult(
    val text: String?,
    val lines: List<TimedLyric>,
    val source: LyricsSource,
    /** String resource describing the source, or 0 when there is nothing to show. */
    val labelRes: Int,
    val wordSynced: Boolean,
    /** True when the time axis was invented by spreading plain text over the song length. */
    val synthetic: Boolean
) {
    val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
}

/**
 * One place that decides which lyrics a song gets.
 *
 * The Subsonic / Navidrome server is always asked first, because it works offline and answers
 * fast. When it has no lyrics, or only line synced ones while the driver asked for word by word
 * lyrics, [LyricsAutoSearch] looks the track up on LRCLIB. A result with real word timings always
 * wins over a line synced one; a plain text result is still spread over the song length so the
 * view keeps moving.
 *
 * Both the full screen player and the permanent car panel use this loader, so they always show
 * exactly the same lyrics and the same source label.
 */
object LyricsLoader {

    private const val SERVER_TIMEOUT_MS = 8_000L
    private const val ONLINE_TIMEOUT_MS = 14_000L

    suspend fun load(
        context: Context,
        track: Track,
        durationMs: Long,
        forceRefresh: Boolean = false
    ): LyricsLoadResult = withContext(Dispatchers.IO) {
        val title = track.title.orEmpty().trim()
        val artist = track.artist.orEmpty().trim()
        val album = track.album.orEmpty().trim()
        val effectiveDuration = if (durationMs > 0) {
            durationMs
        } else {
            (track.duration ?: 0) * 1_000L
        }

        val server = withTimeoutOrNull(SERVER_TIMEOUT_MS) {
            runCatching {
                val service = MusicServiceFactory.getMusicService()
                // The endpoint with the time axis is asked first: the plain one drops every
                // timestamp and the lyrics would not move along with the song.
                runCatching { service.getSyncedLyrics(track.id) }.getOrNull()
                    ?.takeIf { it.timedLines.isNotEmpty() }
                    ?: service.getLyrics(artist, title)
            }.onFailure { Timber.w(it, "Server lyrics lookup failed for %s", title) }
                .getOrNull()
        }
        val serverCandidate = fromServer(server, effectiveDuration)

        val wantsOnline = Settings.autoSearchLyrics &&
            title.isNotBlank() && artist.isNotBlank() &&
            (serverCandidate.rank == 0 ||
                (Settings.preferWordLyrics && !serverCandidate.wordSynced))

        val online = if (wantsOnline) {
            withTimeoutOrNull(ONLINE_TIMEOUT_MS) {
                runCatching {
                    LyricsAutoSearch.search(
                        context.applicationContext,
                        title,
                        artist,
                        album,
                        effectiveDuration,
                        forceRefresh
                    )
                }.onFailure { Timber.w(it, "Online lyrics search failed for %s", title) }
                    .getOrNull()
            }
        } else {
            null
        }
        val onlineCandidate = online?.let { fromOnline(it, effectiveDuration) }

        // Equal ranks keep the server result: it is local, works offline, and the online
        // lookup has already been cached for the next time this song is played.
        val chosen = if (onlineCandidate != null && onlineCandidate.rank > serverCandidate.rank) {
            onlineCandidate
        } else {
            serverCandidate
        }

        val label = when {
            chosen.isEmpty -> R.string.lyrics_source_not_found
            chosen.source == LyricsSource.ONLINE && chosen.wordSynced ->
                R.string.lyrics_source_online_word
            chosen.source == LyricsSource.ONLINE && chosen.fromCache ->
                R.string.lyrics_source_cached
            chosen.source == LyricsSource.ONLINE ->
                R.string.lyrics_source_online_synced
            chosen.wordSynced -> R.string.lyrics_source_server_word
            chosen.synthetic -> R.string.lyrics_source_plain
            else -> R.string.lyrics_source_server_synced
        }

        LyricsLoadResult(
            text = chosen.text,
            lines = chosen.lines,
            source = chosen.source,
            labelRes = label,
            wordSynced = chosen.wordSynced,
            synthetic = chosen.synthetic
        )
    }

    private class Candidate(
        val text: String?,
        val lines: List<TimedLyric>,
        val source: LyricsSource,
        val rank: Int,
        val wordSynced: Boolean,
        val synthetic: Boolean,
        val fromCache: Boolean = false
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
    }

    private fun fromServer(lyrics: Lyrics?, durationMs: Long): Candidate {
        val raw = lyrics?.text
        val timed = lyrics?.timedLines
        val lines = when {
            !timed.isNullOrEmpty() -> timed.map { TimedLyric(it.startMs, it.text) }
            !raw.isNullOrBlank() -> LyricsParser.parse(raw)
            else -> emptyList()
        }
        val synthetic = lines.isEmpty() && !raw.isNullOrBlank()
        val effective = if (synthetic) LyricsParser.spreadPlainLyrics(raw, durationMs) else lines
        val wordSynced = effective.any { it.words.isNotEmpty() }
        val rank = when {
            effective.isEmpty() -> 0
            synthetic -> 1
            wordSynced -> 3
            else -> 2
        }
        return Candidate(
            text = raw,
            lines = effective,
            source = if (effective.isEmpty() && raw.isNullOrBlank()) LyricsSource.NONE else LyricsSource.SERVER,
            rank = rank,
            wordSynced = wordSynced,
            synthetic = synthetic && effective.isNotEmpty()
        )
    }

    private fun fromOnline(
        record: LyricsAutoSearch.LyricsRecord,
        durationMs: Long
    ): Candidate {
        val synthetic = record.lines.isEmpty() && !record.rawText.isNullOrBlank()
        val effective = if (synthetic) {
            LyricsParser.spreadPlainLyrics(record.rawText, durationMs)
        } else {
            record.lines
        }
        val rank = when {
            synthetic -> 1
            effective.isEmpty() -> 0
            record.wordSynced -> 3
            else -> 2
        }
        return Candidate(
            text = record.rawText,
            lines = effective,
            source = if (effective.isEmpty() && record.rawText.isNullOrBlank()) {
                LyricsSource.NONE
            } else {
                LyricsSource.ONLINE
            },
            rank = rank,
            wordSynced = record.wordSynced,
            synthetic = synthetic,
            fromCache = record.fromCache
        )
    }
}
