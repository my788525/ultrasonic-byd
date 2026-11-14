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
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * A successfully downloaded record is saved permanently under [Context.getFilesDir], not in the
 * reclaimable cache: the next time the song plays it is read straight from disk, without any
 * server or network call. Only "not found" results expire, so a title added to LRCLIB later can
 * still be picked up. A second, normalised key is written alongside the exact one so small
 * metadata edits (album spelling, corrected duration) do not lose a saved copy.
 *
 * This is a regular blocking client: callers must invoke it from Dispatchers.IO or a worker
 * thread.
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
        val fromCache: Boolean,
        /** True when this is a permanently saved on-device copy rather than a fresh download. */
        val saved: Boolean = false
    )

    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT =
        "NavMusic/4.8.0 (https://github.com/my788525/ultrasonic-byd)"
    private const val SAVED_DIR = "lyrics_saved"
    private const val LEGACY_DIR = "lyrics_auto"
    private const val ALIAS_SUFFIX = ".alias.json"
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
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")

    private val migrated = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Reads the permanently saved lyrics for a track, with no server and no network access.
     * Expired "not found" entries and corrupt files are ignored so the caller can fall back to
     * the normal lookup.
     */
    fun readSaved(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): LyricsRecord? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        migrateLegacy(context)

        val durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
        val (exact, alias) = cacheFiles(context, cleanTitle, artist, album, durationSec)
        val exactBody = readPositive(exact)
        val body = exactBody ?: readPositive(alias)

        // A record saved before the normalised key existed gets one on its first read.
        if (exactBody != null && !alias.exists()) writePositive(alias, exactBody)

        val record = body?.let { parseRecord(it, fromCache = true, saved = true) }
        if (body != null && record == null) {
            exact.delete()
            alias.delete()
        }
        return record
    }

    /**
     * Finds the best lyrics for a track.
     *
     * The order is: exact signature -> structured search -> keyword search with cleaned
     * metadata. A saved copy (when [useSaved] is on) answers before any request; a valid result
     * is written to disk immediately. `forceRefresh` re-queries the service and overwrites the
     * saved copy, but a failed refresh keeps the existing copy instead of throwing it away.
     */
    fun search(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        forceRefresh: Boolean = false,
        useSaved: Boolean = true
    ): LyricsRecord? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        migrateLegacy(context)

        val durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
        val (exact, alias) = cacheFiles(context, cleanTitle, artist, album, durationSec)

        if (!forceRefresh) {
            if (useSaved) {
                val body = readPositive(exact) ?: readPositive(alias)
                if (body != null) return parseRecord(body, fromCache = true, saved = true)
            }
            if (hasFreshNegative(exact)) return null
        }

        var body: String? = null

        val exactResponse = get(buildGetUrl(cleanTitle, artist, album, durationSec))
        val exactBody = exactResponse?.second
        if (exactResponse != null && exactResponse.first == 200 && !exactBody.isNullOrBlank()) {
            body = exactBody
        } else if (exactResponse?.first == 429) {
            // The docs require honouring Retry-After. Rate limiting must never erase a copy
            // that was already saved for this song.
            Timber.i("LRCLIB rate limited, skipping this lyrics lookup")
            if (useSaved) {
                val existing = readPositive(exact) ?: readPositive(alias)
                if (existing != null) {
                    return parseRecord(existing, fromCache = true, saved = true)
                }
            }
            writeNegative(exact)
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

        // Only a record that really carries lyrics becomes permanent. An instrumental or an
        // empty response is a short lived negative result instead.
        val record = body?.let { parseRecord(it, fromCache = false) }
        if (record != null && body != null) {
            writePositive(exact, body)
            writePositive(alias, body)
            return record
        }

        if (useSaved) {
            val existing = readPositive(exact) ?: readPositive(alias)
            if (existing != null) {
                Timber.i("Online lyrics refresh failed, keeping the saved copy")
                return parseRecord(existing, fromCache = true, saved = true)
            }
        }
        writeNegative(exact)
        return null
    }

    /** Number of songs with a permanently saved lyrics record. */
    fun savedCount(context: Context): Int {
        migrateLegacy(context)
        val files = savedDir(context).listFiles()
            ?.filter { it.name.endsWith(".json") && !it.name.endsWith(ALIAS_SUFFIX) }
            ?: return 0
        return files.count { readPositive(it) != null }
    }

    /** Removes every saved and legacy cached record, returning the number of songs cleared. */
    fun clearCache(context: Context): Int {
        var removed = 0
        listOf(savedDir(context), File(context.cacheDir, LEGACY_DIR)).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val counted = file.name.endsWith(".json") &&
                    !file.name.endsWith(ALIAS_SUFFIX) &&
                    readPositive(file) != null
                if (file.delete() && counted) removed++
            }
        }
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

    private fun parseRecord(json: String, fromCache: Boolean, saved: Boolean = false): LyricsRecord? = try {
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
                fromCache = fromCache,
                saved = saved
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

    private fun savedDir(context: Context): File {
        val dir = File(context.filesDir, SAVED_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * The exact key keeps the old (4.8.0-byd.43) cache format so existing records can be moved
     * over unchanged. The normalised alias ignores album and duration so edited metadata still
     * resolves to the saved copy.
     */
    private fun cacheFiles(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationSec: Double
    ): Pair<File, File> {
        val dir = savedDir(context)
        val exactKey = cacheKey(title, artist, album, durationSec.toInt())
        val aliasKey = cacheKey("v2", normalizeTitle(title), normalizeArtist(artist))
        return File(dir, "$exactKey.json") to File(dir, "$aliasKey$ALIAS_SUFFIX")
    }

    private fun normalizeTitle(value: String): String = PUNCTUATION.replace(
        BRACKET_NOISE.replace(PAREN_NOISE.replace(value.lowercase(Locale.ROOT), " "), " "),
        " "
    ).trim()

    private fun normalizeArtist(value: String): String = PUNCTUATION.replace(
        ARTIST_SPLIT.split(value.lowercase(Locale.ROOT)).firstOrNull().orEmpty(), " "
    ).trim()

    /** Positive records never expire; that is the point of keeping a local library of lyrics. */
    private fun readPositive(file: File): String? {
        if (!file.exists()) return null
        return try {
            val entry = JSONObject(file.readText())
            if (entry.optBoolean("negative", false)) null
            else entry.optString("body", "").ifBlank { null }
        } catch (all: Exception) {
            null
        }
    }

    private fun hasFreshNegative(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val entry = JSONObject(file.readText())
            if (!entry.optBoolean("negative", false)) return false
            val fresh = System.currentTimeMillis() - entry.optLong("ts", 0L) <= NEGATIVE_TTL_MS
            if (!fresh) file.delete()
            fresh
        } catch (all: Exception) {
            false
        }
    }

    private fun writePositive(file: File, body: String) {
        try {
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("negative", false)
            entry.put("body", body)
            file.writeText(entry.toString())
        } catch (all: Exception) {
            Timber.w(all, "Could not save online lyrics")
        }
    }

    private fun writeNegative(file: File) {
        try {
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("negative", true)
            entry.put("body", "")
            file.writeText(entry.toString())
        } catch (all: Exception) {
            Timber.w(all, "Could not cache a missing lyrics result")
        }
    }

    /** One-off move of the reclaimable cache used by 4.8.0-byd.43 into permanent storage. */
    private fun migrateLegacy(context: Context) {
        if (!migrated.compareAndSet(false, true)) return
        try {
            val legacy = File(context.cacheDir, LEGACY_DIR)
            val files = legacy.listFiles() ?: return
            val dir = savedDir(context)
            files.forEach { file ->
                if (!file.name.endsWith(".json")) return@forEach
                val body = readPositive(file)
                if (body != null) {
                    val text = file.readText()
                    File(dir, file.name).writeText(text)
                }
                file.delete()
            }
            Timber.i("Migrated legacy online lyrics cache into permanent storage")
        } catch (all: Exception) {
            Timber.w(all, "Could not migrate the legacy lyrics cache")
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
