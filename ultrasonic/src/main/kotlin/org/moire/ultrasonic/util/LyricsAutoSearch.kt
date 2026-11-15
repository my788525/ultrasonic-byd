/*
 * LyricsAutoSearch.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
 * The official docs require an identifying User-Agent, a 200-500 ms pause between requests,
 * and honouring `Retry-After` after HTTP 429 ("Ignoring this may result in a temporary ban").
 * All three are implemented: requests are serialized with a 250 ms gap, a 429 starts a global
 * cooldown read from the header, and during the cooldown no request is sent at all. A rate
 * limit never writes a negative cache entry, so the song is looked up again as soon as the
 * cooldown ends.
 *
 * A successfully downloaded record is saved permanently under [Context.getFilesDir], not in the
 * reclaimable cache: the next time the song plays it is read straight from disk, without any
 * server or network call. Only "not found" results expire, so a title added to LRCLIB later can
 * still be picked up. A second, normalised key is written alongside the exact one so small
 * metadata edits (album spelling, corrected duration) do not lose a saved copy. Alias hits are
 * verified against the saved track name, artist and duration before being used, so a live or
 * remix version of a song cannot silently borrow another version's lyrics.
 *
 * This is a regular blocking client with its own hard deadline: callers must invoke it from
 * Dispatchers.IO or a worker thread. Every request is bounded by the time left of [search]'s
 * `timeoutMs`, so a cancelled lookup cannot keep a socket open or write a cache entry after the
 * caller has given up.
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

    private data class HttpResult(
        val code: Int,
        val body: String?,
        val retryAfterSec: Long?
    )

    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT =
        "NavMusic/4.8.0 (https://github.com/my788525/ultrasonic-byd)"
    private const val SAVED_DIR = "lyrics_saved"
    private const val LEGACY_DIR = "lyrics_auto"
    private const val ALIAS_SUFFIX = ".alias.json"
    private const val NEGATIVE_TTL_MS = 6 * 60 * 60 * 1000L
    /** LRCLIB asks for 200-500 ms between requests. */
    private const val MIN_REQUEST_GAP_MS = 250L
    private const val DEFAULT_RETRY_AFTER_SEC = 60L
    /** Roughly 1500 songs (each song keeps an exact file and a normalised alias). */
    private const val MAX_CACHE_FILES = 3000

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
    private val rateLimitedUntil = AtomicLong(0L)
    private val requestLock = Any()
    private var lastRequestAt = 0L

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
        val aliasBody = if (exactBody == null) readPositive(alias) else null
        val body = exactBody ?: aliasBody

        // A record saved before the normalised key existed gets one on its first read.
        if (exactBody != null && !alias.exists()) writePositive(alias, exactBody)

        val record = body?.let { parseRecord(it, fromCache = true, saved = true) }
        if (body == null) return null
        if (record == null) {
            exact.delete()
            alias.delete()
            return null
        }
        if (aliasBody != null && !aliasMatches(record, cleanTitle, artist, durationSec)) {
            // The alias belongs to a different recording (live, remix, a different album cut).
            // Dropping it lets the real lookup run instead of showing the wrong words.
            alias.delete()
            Timber.i("Rejected a saved lyrics alias for %s", cleanTitle)
            return null
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
     *
     * [timeoutMs] is a hard deadline for the whole lookup, not just one request. When it runs
     * out no further request is made and no cache entry is written by an abandoned call.
     */
    fun search(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        forceRefresh: Boolean = false,
        useSaved: Boolean = true,
        timeoutMs: Long = 14_000L
    ): LyricsRecord? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        migrateLegacy(context)

        val durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
        val (exact, alias) = cacheFiles(context, cleanTitle, artist, album, durationSec)
        val savedCopy = { readSaved(context, cleanTitle, artist, album, durationMs) }

        if (!forceRefresh) {
            if (useSaved) {
                val saved = savedCopy()
                if (saved != null) return saved
            }
            if (hasFreshNegative(exact)) return null
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1_000L)
        var body: String? = null
        var rateLimited = false

        val exactResponse = get(buildGetUrl(cleanTitle, artist, album, durationSec), deadline)
        if (exactResponse != null && exactResponse.code == 200 && !exactResponse.body.isNullOrBlank()) {
            body = exactResponse.body
        } else if (exactResponse?.code == 429) {
            rateLimited = true
            startCooldown(exactResponse.retryAfterSec)
        }

        if (body == null && !rateLimited && timeLeft(deadline)) {
            val structured = get(buildSearchUrl(cleanTitle, artist), deadline)
            if (structured?.code == 429) {
                rateLimited = true
                startCooldown(structured.retryAfterSec)
            } else if (structured != null && structured.code == 200 && !structured.body.isNullOrBlank()) {
                body = pickBest(structured.body, cleanTitle, artist, durationSec)
            }
        }

        if (body == null && !rateLimited && timeLeft(deadline)) {
            val keyword = cleanedQuery(cleanTitle, artist)
            if (keyword.isNotBlank()) {
                val fallback = get("$BASE_URL/api/search?q=${enc(keyword)}", deadline)
                if (fallback?.code == 429) {
                    rateLimited = true
                    startCooldown(fallback.retryAfterSec)
                } else if (fallback != null && fallback.code == 200 && !fallback.body.isNullOrBlank()) {
                    body = pickBest(fallback.body, cleanTitle, artist, durationSec)
                }
            }
        }

        if (rateLimited) {
            Timber.i("LRCLIB rate limited; retrying after the server cooldown")
            // Never write a negative result here: the 6 h negative TTL would hide a title that
            // is available as soon as the cooldown is over. Also keep any already saved copy.
            return if (useSaved) savedCopy() else null
        }

        // Only a record that really carries lyrics becomes permanent. An instrumental or an
        // empty response is a short lived negative result instead.
        if (body == null) {
            val existing = if (useSaved) savedCopy() else null
            if (existing != null) {
                Timber.i("Online lyrics refresh failed, keeping the saved copy")
                return existing
            }
            writeNegative(exact)
            return null
        }

        val record = parseRecord(body, fromCache = false)
        return if (record != null) {
            writePositive(exact, body)
            writePositive(alias, body)
            record
        } else {
            // An instrumental or a broken payload must not erase a copy that was already good.
            val existing = if (useSaved) savedCopy() else null
            if (existing != null) return existing
            writeNegative(exact)
            null
        }
    }

    /** Number of songs with a permanently saved lyrics record. May do disk IO: call off main. */
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
        rateLimitedUntil.set(0L)
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
            // A driver who picked "synced lyrics" gets the line timed LRC, not the word timed
            // Lyricsfile. Auto and word mode use the richer timeline when the record has one.
            val wordLines = if (Settings.lyricsDisplayMode != "synced") {
                WordLyricsParser.parseLyricsFile(record.optString("lyricsfile"))
            } else {
                emptyList()
            }
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

    /**
     * One request. Honours the global 429 cooldown and the inter-request gap, and bounds the
     * call by whatever time is left of the whole lookup.
     */
    private fun get(url: String, deadline: Long): HttpResult? {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return null
        if (SystemClock.elapsedRealtime() < rateLimitedUntil.get()) {
            Timber.i("Skipping LRCLIB request during the Retry-After cooldown")
            return null
        }

        synchronized(requestLock) {
            val wait = MIN_REQUEST_GAP_MS -
                (SystemClock.elapsedRealtime() - lastRequestAt)
            if (wait in 1L..MIN_REQUEST_GAP_MS) Thread.sleep(wait)
            lastRequestAt = SystemClock.elapsedRealtime()
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            // newBuilder() shares the connection pool and dispatcher, but lets us cap this one
            // call at the remaining budget so an abandoned lookup cannot keep a socket open.
            val timedClient = client.newBuilder()
                .callTimeout(remaining.coerceAtMost(20_000L), TimeUnit.MILLISECONDS)
                .build()
            timedClient.newCall(request).execute().use { response ->
                HttpResult(
                    code = response.code,
                    body = response.body?.string(),
                    retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                )
            }
        } catch (all: Exception) {
            Timber.w(all, "LRCLIB request failed: %s", url.take(120))
            null
        }
    }

    private fun timeLeft(deadline: Long): Boolean =
        SystemClock.elapsedRealtime() < deadline &&
            SystemClock.elapsedRealtime() >= rateLimitedUntil.get()

    private fun startCooldown(retryAfterSec: Long?) {
        val seconds = (retryAfterSec ?: DEFAULT_RETRY_AFTER_SEC)
            .coerceIn(1L, 3_600L)
        val until = SystemClock.elapsedRealtime() + seconds * 1_000L
        var changed: Boolean
        do {
            val previous = rateLimitedUntil.get()
            changed = rateLimitedUntil.compareAndSet(previous, maxOf(previous, until))
        } while (!changed)
        Timber.i("LRCLIB asked to retry after %d s", seconds)
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

    /**
     * Guards the album/duration-free alias key against a different recording of the same song.
     * A live or remix cut usually has a very different length, and the name check catches the
     * cases where the tagger kept the version in the title.
     */
    private fun aliasMatches(
        record: LyricsRecord,
        title: String,
        artist: String,
        durationSec: Double
    ): Boolean {
        val wantedTitle = normalizeTitle(title)
        val foundTitle = normalizeTitle(record.trackName)
        val titleOk = wantedTitle.isBlank() || foundTitle.isBlank() ||
            wantedTitle == foundTitle ||
            wantedTitle.contains(foundTitle) || foundTitle.contains(wantedTitle)
        if (!titleOk) return false

        val wantedArtist = normalizeArtist(artist)
        val foundArtist = normalizeArtist(record.artistName)
        val artistOk = wantedArtist.isBlank() || foundArtist.isBlank() ||
            wantedArtist == foundArtist ||
            (wantedArtist.length >= 4 && foundArtist.contains(wantedArtist.take(5), ignoreCase = true)) ||
            (foundArtist.length >= 4 && wantedArtist.contains(foundArtist.take(5), ignoreCase = true))
        if (!artistOk) return false

        val savedDuration = record.durationSec
        if (durationSec > 1.0 && savedDuration > 1.0 &&
            abs(durationSec - savedDuration) > 15.0
        ) {
            return false
        }
        return true
    }

    /** Positive records never expire; that is the point of keeping a local library of lyrics. */
    private fun readPositive(file: File): String? {
        if (!file.exists()) return null
        return try {
            val entry = JSONObject(file.readText())
            if (entry.optBoolean("negative", false)) null
            else entry.optString("body", "").ifBlank { null }?.also {
                // Touch the file so the LRU trim keeps the songs played most recently.
                file.setLastModified(System.currentTimeMillis())
            }
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
            enforceFileLimit(file.parentFile)
        } catch (all: Exception) {
            Timber.w(all, "Could not save online lyrics")
        }
    }

    /** Least-recently-used cap: a car used for years must not grow an unbounded lyrics folder. */
    private fun enforceFileLimit(dir: File?) {
        val files = dir?.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val excess = files.size - MAX_CACHE_FILES
        if (excess <= 0) return
        files.take(excess).forEach { it.delete() }
        Timber.i("Trimmed %d old lyrics records to keep the cache under %d files", excess, MAX_CACHE_FILES)
    }

    private fun writeNegative(file: File) {
        try {
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("negative", true)
            entry.put("body", "")
            file.writeText(entry.toString())
            enforceFileLimit(file.parentFile)
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
