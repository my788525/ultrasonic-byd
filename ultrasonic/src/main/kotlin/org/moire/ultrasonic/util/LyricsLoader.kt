/*
 * LyricsLoader.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.util.LruCache
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    val synthetic: Boolean,
    /** True when the result came from a permanently saved online copy. */
    val saved: Boolean = false
) {
    val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
}

/**
 * One place that decides which lyrics a song gets.
 *
 * The priority is **online first, server fallback**: a lyrics record downloaded from LRCLIB is
 * the preferred source, because a personal Navidrome library often carries incomplete lyrics.
 * A record that was saved once is read from disk with no network (and no server call), so the
 * car keeps showing the same word by word lyrics even fully offline; when no saved copy exists
 * a fresh LRCLIB download is attempted. Only when LRCLIB has no record for the song does the
 * loader fall back to the Subsonic / Navidrome server (with `enhanced=true` word timings when
 * available). Any successful online download is saved immediately for later plays.
 *
 * The full screen player, the permanent car panel and the floating window all use this loader,
 * so they always show exactly the same lyrics and the same source label. A small LRU keeps the
 * last results in memory, and [prefetch] warms it for the next song in the queue.
 */
object LyricsLoader {

    private const val SERVER_TIMEOUT_MS = 8_000L
    private const val ONLINE_TIMEOUT_MS = 14_000L
    private const val MEMORY_CACHE_SIZE = 24

    private val memoryCache = LruCache<String, LyricsLoadResult>(MEMORY_CACHE_SIZE)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    suspend fun load(
        context: Context,
        track: Track,
        durationMs: Long,
        forceRefresh: Boolean = false
    ): LyricsLoadResult = withContext(Dispatchers.IO) {
        loadInternal(context.applicationContext, track, durationMs, forceRefresh)
    }

    /**
     * Warms the next song's lyrics while the current one is playing. The lookup is deduplicated
     * against the memory cache and against lookups already running, so the player pane and the
     * full screen player cannot trigger two network searches for the same track.
     */
    fun prefetch(context: Context, track: Track?, durationMs: Long) {
        if (track == null || !Settings.prefetchLyrics) return
        val key = track.id
        if (key.isBlank() || memoryCache.get(key) != null) return
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        prefetchScope.launch {
            try {
                val result = runCatching {
                    loadInternal(context.applicationContext, track, durationMs, false)
                }.getOrNull()
                if (result != null) putCached(key, result)
            } catch (all: Exception) {
                Timber.w(all, "Could not prefetch lyrics for the next song")
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    private suspend fun loadInternal(
        context: Context,
        track: Track,
        durationMs: Long,
        forceRefresh: Boolean
    ): LyricsLoadResult {
        val title = track.title.orEmpty().trim()
        val artist = track.artist.orEmpty().trim()
        val album = track.album.orEmpty().trim()
        val effectiveDuration = if (durationMs > 0) {
            durationMs
        } else {
            (track.duration ?: 0) * 1_000L
        }

        if (forceRefresh) {
            memoryCache.remove(track.id)
        } else {
            memoryCache.get(track.id)?.let { return it }
        }

        // Online lyrics are the priority source. A permanently saved (offline) copy is answered
        // straight from disk with no network, and a fresh LRCLIB download is attempted whenever
        // auto search is enabled. Navidrome is consulted only afterwards, as a fallback for the
        // songs LRCLIB does not carry - the user's server lyrics are treated as secondary to the
        // (usually more complete) online record.
        val wantsOnline = Settings.autoSearchLyrics &&
            title.isNotBlank() && artist.isNotBlank()

        // The online lookup owns its own hard deadline: every request is bounded by the remaining
        // budget, so no socket stays open and no cache entry is written after the caller has
        // stopped waiting.
        val online = if (wantsOnline) {
            runCatching {
                LyricsAutoSearch.search(
                    context,
                    title,
                    artist,
                    album,
                    effectiveDuration,
                    forceRefresh,
                    Settings.preferSavedLyrics,
                    ONLINE_TIMEOUT_MS
                )
            }.onFailure { Timber.w(it, "Online lyrics search failed for %s", title) }
                .getOrNull()
        } else {
            null
        }
        val onlineCandidate = online?.let { fromOnline(it, effectiveDuration) }

        // Reach out to Navidrome only when the online lookup gave us nothing, or when the online
        // copy is merely plain text and the driver prefers real word timings that the server may
        // carry. When the online record is already word synced there is nothing to gain.
        val needServer = (onlineCandidate == null || onlineCandidate.isEmpty) ||
            (!onlineCandidate.wordSynced && Settings.preferWordLyrics)
        val server = if (needServer) {
            // The REST client has its own socket timeouts; this deadline only stops the caller
            // from waiting on a stalled server. A server lookup is read only and writes no cache.
            withTimeoutOrNull(SERVER_TIMEOUT_MS) {
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
        } else {
            null
        }
        val serverCandidate = fromServer(server, effectiveDuration)

        // Online wins whenever it produced usable lyrics. The only exception is a plain (non word
        // synced) online copy while the server offers real word timings, and only when the driver
        // asked to prefer word lyrics - then the server's karaoke timings are strictly better.
        val chosen = if (onlineCandidate != null && !onlineCandidate.isEmpty) {
            if (!onlineCandidate.wordSynced && Settings.preferWordLyrics && serverCandidate.wordSynced) {
                serverCandidate
            } else {
                onlineCandidate
            }
        } else {
            serverCandidate
        }

        return putCached(track.id, toResult(chosen))
    }

    private fun putCached(key: String, result: LyricsLoadResult): LyricsLoadResult {
        // A synthetic time axis depends on the duration reported when the song started, which
        // may differ the next time it plays. Real timed lines are safe to reuse.
        if (!result.isEmpty && !result.synthetic) memoryCache.put(key, result)
        return result
    }

    private fun toResult(candidate: Candidate): LyricsLoadResult {
        val label = when {
            candidate.isEmpty -> R.string.lyrics_source_not_found
            candidate.saved && candidate.wordSynced -> R.string.lyrics_source_saved_word
            candidate.saved -> R.string.lyrics_source_saved
            candidate.source == LyricsSource.ONLINE && candidate.wordSynced ->
                R.string.lyrics_source_online_word
            candidate.source == LyricsSource.ONLINE && candidate.fromCache ->
                R.string.lyrics_source_cached
            candidate.source == LyricsSource.ONLINE -> R.string.lyrics_source_online_synced
            candidate.wordSynced -> R.string.lyrics_source_server_word
            candidate.synthetic -> R.string.lyrics_source_plain
            else -> R.string.lyrics_source_server_synced
        }

        return LyricsLoadResult(
            text = candidate.text,
            lines = candidate.lines,
            source = candidate.source,
            labelRes = label,
            wordSynced = candidate.wordSynced,
            synthetic = candidate.synthetic,
            saved = candidate.saved
        )
    }

    private class Candidate(
        val text: String?,
        val lines: List<TimedLyric>,
        val source: LyricsSource,
        val rank: Int,
        val wordSynced: Boolean,
        val synthetic: Boolean,
        val fromCache: Boolean = false,
        val saved: Boolean = false
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
    }

    private fun fromServer(lyrics: Lyrics?, durationMs: Long): Candidate {
        val raw = lyrics?.text
        val timed = lyrics?.timedLines
        val lines = when {
            !timed.isNullOrEmpty() -> timed.map { line ->
                TimedLyric(
                    line.startMs,
                    line.text,
                    line.words.map { TimedWord(it.startMs, it.text) }
                )
            }
            !raw.isNullOrBlank() -> LyricsParser.parse(raw)
            else -> emptyList()
        }
        val synthetic = lines.isEmpty() && !raw.isNullOrBlank()
        val effective = if (synthetic) LyricsParser.spreadPlainLyrics(raw, durationMs) else lines
        val wordSynced = effective.any { it.words.isNotEmpty() }
        val rank = when {
            effective.isEmpty() && raw.isNullOrBlank() -> 0
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
            synthetic = synthetic
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
            effective.isEmpty() && record.rawText.isNullOrBlank() -> 0
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
            fromCache = record.fromCache,
            saved = record.saved
        )
    }
}
