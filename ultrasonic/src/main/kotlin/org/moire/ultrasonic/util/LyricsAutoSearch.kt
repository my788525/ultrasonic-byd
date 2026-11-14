/*
 * LyricsAutoSearch.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Automatic online lyrics search backed by LRCLIB, the free and open lyrics database.
 *
 * The API is documented at https://lrclib.net/docs.txt (plain text copy of the official docs):
 *  - `GET /api/get`   matches a track by title, artist, optional album and duration.
 *  - `GET /api/search` returns up to 20 candidates when the exact signature has no record.
 *  - every record has `syncedLyrics` (LRC) and `lyricsfile` (YAML). Records with
 *    `hasWordSync: true` carry real per word timings in `lines[].words[].start_ms`, which is
 *    what drives the word by word highlight.
 *
 * No API key is needed, but the service requires an identifying User-Agent and asks clients to
 * send requests sequentially and to honour `Retry-After` on HTTP 429. Both are implemented here.
 * Results (and "not found" results) are cached on disk so a song played again never hits the
 * service twice, and a car with a weak connection is not left waiting on every track change.
 *
 * This is a regular blocking client: callers must invoke it from Dispatchers.IO.
 */
object LyricsAutoSearch {

    /** A parsed lyrics record, ready for SyncedLyricsView. */
    data class LyricsRecord(
        val rawText: String?,
        val lines: List<TimedLyric>,
        val wordSynced: Boolean,
        val durationSec: Double,
        val trackName: String,
        val artistName: String,
        val fromCache: Boolean
    )

    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT =
        "NavMusic/4.8.0 (https://github.com/my788525/ultrasonic-byd)"
    private const val CACHE_DIR = "lyrics_auto"
    private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_TTL_MS = 6 * 60 * 60 * 1000L

    private val PAREN_NOISE = Regex(
        """(?i)\s*\([^)]*(?:remaster|live|version|edit|mix|feat|ft\.?|official|audio|video)[^)]*\)"""
    )
    private val BRACKET_NOISE = Regex(
        """(?i)\s*\[[^\]]*(?:remaster|live|version|edit|mix|feat|ft\.?|official|audio|video)[^\]]*\]"""
    )
    private val ARTIST_SPLIT = Regex(
        """(?i)\s*(?:feat\.?|ft\.?|featuring|&|,|/|、|with)\s*"""
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Finds the best lyrics for a track.
     *
     * The order is: exact signature -> structured search -> keyword search with cleaned
     * metadata. A request is only made when there is no fresh cache entry. `forceRefresh`
     * bypasses the cache (used by the pull-to-refresh gesture) but never bypasses 429.
     */
    fun search(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        forceRefresh: Boolean = false
    ): LyricsRecord? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null

        val durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
        val cache = cacheFile(context, cacheKey(cleanTitle, artist, album, durationSec.toInt()))

        if (!forceRefresh) {
            val cached = readCache(cache)
            if (cached != null) {
                return cached.body?.let { parseRecord(it, fromCache = true) }
            }
        }

        var body: String? = null

        val exact = get(buildGetUrl(cleanTitle, artist, album, durationSec))
        val exactBody = exact?.second
        if (exact != null && exact.first == 200 && !exactBody.isNullOrBlank()) {
            body = exactBody
        } else if (exact?.first == 429) {
            // The docs require honouring Retry-After. Skipping this round and caching the
            // negative result for a short while is the polite behaviour on a car network.
            Timber.i("LRCLIB rate limited, skipping this lyrics lookup")
            writeCache(cache, null)
            return null
        }

        if (body == null) {
            val structured = get(buildSearchUrl(cleanTitle, artist))
            val structuredBody = structured?.second
            if (structured != null && structured.first == 200 && !structuredBody.isNullOrBlank()) {
                body = pickBest(structuredBody, cleanTitle, artist, durationSec)
            }
        }

        if (body == null) {
            val keyword = cleanedQuery(cleanTitle, artist)
            if (keyword.isNotBlank()) {
                val fallback = get("$BASE_URL/api/search?q=${enc(keyword)}")
                val fallbackBody = fallback?.second
                if (fallback != null && fallback.first == 200 && !fallbackBody.isNullOrBlank()) {
                    body = pickBest(fallbackBody, cleanTitle, artist, durationSec)
                }
            }
        }

        writeCache(cache, body)
        return body?.let { parseRecord(it, fromCache = false) }
    }

    /** Number of cached lookups, shown in the settings screen. */
    fun cacheCount(context: Context): Int = cacheDir(context).listFiles()?.size ?: 0

    /** Removes cached online lyrics so the next lookup starts fresh. */
    fun clearCache(context: Context): Int {
        val files = cacheDir(context).listFiles() ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    private fun buildGetUrl(
        title: String,
        artist: String,
        album: String,
        durationSec: Double
    ): String = buildString {
        append(BASE_URL).append("/api/get?track_name=").append(enc(title))
        append("&artist_name=").append(enc(artist.ifBlank { "Unknown" }))
        if (album.isNotBlank()) append("&album_name=").append(enc(album))
        // The API rejects durations outside 1..3600, and duration is the strongest match key.
        if (durationSec in 1.0..3600.0) append("&duration=").append(durationSec.toInt())
    }

    private fun buildSearchUrl(title: String, artist: String): String {
        if (artist.isBlank()) return "$BASE_URL/api/search?q=${enc(title)}"
        return buildString {
            append(BASE_URL).append("/api/search?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
        }
    }

    private fun cleanedQuery(title: String, artist: String): String {
        val cleanTitle = BRACKET_NOISE.replace(PAREN_NOISE.replace(title, " "), " ").trim()
        val cleanArtist = ARTIST_SPLIT.split(artist).firstOrNull().orEmpty().trim()
        return ("$cleanTitle $cleanArtist").trim()
    }

    /** Picks the closest result out of the (max 20) search candidates. */
    private fun pickBest(
        json: String,
        title: String,
        artist: String,
        durationSec: Double
    ): String? = try {
        val array = org.json.JSONArray(json)
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until array.length()) {
            val candidate = array.optJSONObject(index) ?: continue
            val score = score(candidate, title, artist, durationSec)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        // A candidate with no lyrics text at all is not worth showing.
        if (bestScore <= 0) null else best?.toString()
    } catch (all: Exception) {
        Timber.w(all, "Could not rank LRCLIB search results")
        null
    }

    private fun score(
        record: JSONObject,
        title: String,
        artist: String,
        durationSec: Double
    ): Int {
        if (record.optBoolean("instrumental", false)) return Int.MIN_VALUE
        var value = 0

        val duration = record.optDouble("duration", 0.0)
        if (durationSec > 0 && duration > 0) {
            val difference = abs(duration - durationSec)
            if (difference <= 2.0) value += 100
            else value -= (difference * 2).toInt().coerceAtMost(80)
        }
        if (record.optBoolean("hasWordSync", false)) value += 60
        if (record.optString("syncedLyrics").isNotBlank()) value += 25
        if (record.optString("plainLyrics").isNotBlank()) value += 5

        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerArtist = artist.lowercase(Locale.ROOT)
        val foundTitle = record.optString("trackName").lowercase(Locale.ROOT)
        val foundArtist = record.optString("artistName").lowercase(Locale.ROOT)
        if (foundTitle.isNotBlank() && (foundTitle.contains(lowerTitle) || lowerTitle.contains(foundTitle))) {
            value += 15
        }
        if (lowerArtist.length >= 3 &&
            foundArtist.contains(lowerArtist.take(6), ignoreCase = true)
        ) {
            value += 10
        }
        return value
    }

    private fun parseRecord(json: String, fromCache: Boolean): LyricsRecord? = try {
        val record = JSONObject(json)
        if (record.optBoolean("instrumental", false)) null
        else {
            val wordLines = WordLyricsParser.parseLyricsFile(record.optString("lyricsfile"))
            val synced = record.optString("syncedLyrics")
            val plain = record.optString("plainLyrics")
            val lines = when {
                wordLines.isNotEmpty() -> wordLines
                synced.isNotBlank() -> LyricsParser.parse(synced)
                else -> emptyList()
            }
            val rawText = when {
                plain.isNotBlank() -> plain
                lines.isNotEmpty() -> lines.joinToString("\n") { it.text }
                synced.isNotBlank() -> synced
                else -> ""
            }
            if (lines.isEmpty() && rawText.isBlank()) null
            else LyricsRecord(
                rawText = rawText,
                lines = lines,
                wordSynced = lines.any { it.words.isNotEmpty() },
                durationSec = record.optDouble("duration", 0.0),
                trackName = record.optString("trackName"),
                artistName = record.optString("artistName"),
                fromCache = fromCache
            )
        }
    } catch (all: Exception) {
        Timber.w(all, "Could not parse LRCLIB lyrics record")
        null
    }

    private fun get(url: String): Pair<Int, String?>? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            response.code to response.body?.string()
        }
    } catch (all: Exception) {
        Timber.w(all, "LRCLIB request failed: %s", url.take(120))
        null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private class CacheEntry(val body: String?)

    private fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR)

    private fun cacheFile(context: Context, key: String): File {
        val dir = cacheDir(context)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.json")
    }

    private fun readCache(file: File): CacheEntry? {
        if (!file.exists()) return null
        return try {
            val entry = JSONObject(file.readText())
            val negative = entry.optBoolean("negative", false)
            val ttl = if (negative) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (System.currentTimeMillis() - entry.optLong("ts", 0L) > ttl) {
                file.delete()
                null
            } else {
                CacheEntry(if (negative) null else entry.optString("body", "").ifBlank { null })
            }
        } catch (all: Exception) {
            null
        }
    }

    private fun writeCache(file: File, body: String?) {
        try {
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("negative", body == null)
            entry.put("body", body.orEmpty())
            file.writeText(entry.toString())
        } catch (all: Exception) {
            Timber.w(all, "Could not cache online lyrics")
        }
    }

    private fun cacheKey(vararg parts: Any): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        }
    }
}
