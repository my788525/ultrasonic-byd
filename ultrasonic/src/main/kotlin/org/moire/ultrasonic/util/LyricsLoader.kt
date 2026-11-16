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
    val saved: Boolean = false,
    /**
     * The length of the recording the lyrics were made for, in seconds. Zero when the lyrics
     * came from the server, which always belongs to the song that is playing.
     */
    val lyricsDurationSec: Double = 0.0
) {
    val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
}

/**
 * One place that decides which lyrics a song gets.
 *
 * The default priority is **online first, server fallback**: a lyrics record downloaded from
 * LRCLIB is the preferred source, because a personal Navidrome library often carries
 * incomplete lyrics. `Settings.lyricsSourcePriority` can flip that order. A record saved once
 * is read from disk with no network, so the car keeps showing the same word by word lyrics even
 * fully offline. The secondary source is always consulted when the primary has nothing, or when
 * it can supply real word timings the primary lacks. Any successful online download is saved
 * immediately, and translations (embedded bilingual lines or the optional online lookup) are
 * attached to the timed lines before the result is cached.
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
            memoryCache.get(track.id)?.let { cached ->
                // The warm cache was matched against the length from the song list, while the
                // song that plays now can be a little longer or shorter. A record of another
                // recording is dropped instead of being used.
                if (fitsTheSong(cached, effectiveDuration)) return cached
                memoryCache.remove(track.id)
            }
        }

        // Which source is asked first is a setting (online by default, see byd.46). The other
        // source is still consulted as a fallback, or when it can supply real word timings that
        // the primary source does not have.
        val wantsOnline = Settings.autoSearchLyrics &&
            title.isNotBlank() && artist.isNotBlank()
        val serverFirst = Settings.lyricsSourcePriority == "server"
        val wantWordTimings = Settings.wantWordTimings

        val onlineCandidate: Candidate?
        val serverCandidate: Candidate
        if (serverFirst) {
            serverCandidate = fetchServer(track, title, artist, effectiveDuration)
            val needOnline = serverCandidate.isEmpty ||
                (!serverCandidate.wordSynced && wantWordTimings)
            onlineCandidate = if (needOnline && wantsOnline) {
                fetchOnline(context, title, artist, album, effectiveDuration, forceRefresh)
            } else {
                null
            }
        } else {
            onlineCandidate = if (wantsOnline) {
                fetchOnline(context, title, artist, album, effectiveDuration, forceRefresh)
            } else {
                null
            }
            val needServer = (onlineCandidate == null || onlineCandidate.isEmpty) ||
                (!onlineCandidate.wordSynced && wantWordTimings)
            serverCandidate = if (needServer) {
                fetchServer(track, title, artist, effectiveDuration)
            } else {
                fromServer(null, effectiveDuration)
            }
        }

        // The primary source wins whenever it produced usable lyrics. The only exception is a
        // plain (non word synced) primary copy while the fallback offers real word timings, and
        // only when the driver asked for word timings at all.
        val chosen = if (serverFirst) {
            when {
                serverCandidate.isEmpty -> onlineCandidate ?: serverCandidate
                onlineCandidate != null && !onlineCandidate.isEmpty &&
                    !serverCandidate.wordSynced && onlineCandidate.wordSynced &&
                    wantWordTimings -> onlineCandidate
                else -> serverCandidate
            }
        } else {
            when {
                onlineCandidate == null || onlineCandidate.isEmpty -> serverCandidate
                !onlineCandidate.wordSynced && serverCandidate.wordSynced &&
                    wantWordTimings -> serverCandidate
                else -> onlineCandidate
            }
        }

        val result = toResult(chosen)
        // A synthetic time axis was invented by spreading plain text over the song length, so
        // there are no real timestamps to align a translation against. Show the text as it is.
        val translated = if (result.synthetic) {
            result.lines
        } else {
            LyricsTranslation.apply(context, track, result.lines, effectiveDuration)
        }
        return putCached(track.id, result.copy(lines = translated))
    }

    /**
     * LRCLIB lookup. A permanently saved (offline) copy is answered straight from disk, and a
     * fresh download is written back immediately. The lookup owns its own hard deadline, so no
     * socket stays open and no cache entry is written after the caller stopped waiting.
     */
    private fun fetchOnline(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        forceRefresh: Boolean
    ): Candidate? {
        val record = runCatching {
            LyricsAutoSearch.search(
                context,
                title,
                artist,
                album,
                durationMs,
                forceRefresh,
                Settings.preferSavedLyrics,
                ONLINE_TIMEOUT_MS
            )
        }.onFailure { Timber.w(it, "Online lyrics search failed for %s", title) }
            .getOrNull() ?: return null
        return fromOnline(record, durationMs)
    }

    /** Navidrome / OpenSubsonic lookup, preferring the endpoint that keeps the time axis. */
    private suspend fun fetchServer(
        track: Track,
        title: String,
        artist: String,
        durationMs: Long
    ): Candidate {
        val lyrics = withTimeoutOrNull(SERVER_TIMEOUT_MS) {
            runCatching {
                val service = MusicServiceFactory.getMusicService()
                runCatching { service.getSyncedLyrics(track.id) }.getOrNull()
                    ?.takeIf { it.timedLines.isNotEmpty() }
                    ?: service.getLyrics(artist, title)
            }.onFailure { Timber.w(it, "Server lyrics lookup failed for %s", title) }
                .getOrNull()
        }
        return fromServer(lyrics, durationMs)
    }

    /**
     * Drops the in-memory results, so a change of translation source or source priority is
     * picked up by the three lyric surfaces without forcing a new online download.
     */
    fun invalidateAll() {
        memoryCache.evictAll()
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
            saved = candidate.saved,
            lyricsDurationSec = candidate.lyricsDurationSec
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
        val saved: Boolean = false,
        /**
         * The length of the recording the lyrics were made for, in seconds. Zero when the
         * lyrics came from the server, which always belongs to the song that is playing.
         */
        val lyricsDurationSec: Double = 0.0
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() && text.isNullOrBlank()
    }

    /**
     * Whether the lyrics belong to the song that is playing.
     *
     * The lyrics of the server always do. The lyrics from the online service carry the length
     * of the recording they were made for, and a record of another recording has a time axis
     * that does not fit the song: its lyrics would run ahead of the music.
     */
    private fun fitsTheSong(result: LyricsLoadResult, durationMs: Long): Boolean =
        LyricsLengthGate.fits(result.lyricsDurationSec, durationMs / 1000.0)

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
            saved = record.saved,
            lyricsDurationSec = record.durationSec
        )
    }
}
