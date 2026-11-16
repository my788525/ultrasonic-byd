/*
 * LyricsTranslation.kt
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
import kotlin.math.abs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.moire.ultrasonic.domain.Track
import timber.log.Timber

/**
 * Chinese (and other) translations for the lyrics shown on the head unit.
 *
 * LRCLIB's Lyricsfile 1.0 has no translation field (see https://lrclib.net/lyricsfile), so a
 * translation can only come from two honest places:
 *
 *  1. **Embedded bilingual LRC** - very common on personal Navidrome libraries. Either the
 *     original and its translation share one timestamp (two consecutive lines), or they sit in
 *     one line separated by a slash. The line containing CJK characters is the translation.
 *  2. **Optional online lookup** - the NetEase Cloud Music lyric endpoint returns `tlyric`,
 *     a human made Chinese translation in LRC form with real timestamps. It is off by default
 *     and is only consulted when the driver explicitly enables it; a failed or empty lookup
 *     never changes the lyrics, it just leaves the original lines alone.
 *
 * Remote translations are matched to the (LRCLIB or server) original lines by their real
 * timestamps. Two passes are tried - unchanged timestamps and a globally estimated offset - and
 * whichever matches more lines wins. Nothing is invented: a line with no translation stays as
 * it is.
 *
 * Positive results are saved under [Context.getFilesDir] so a translation costs no network on
 * the next play; "not available" results expire after a day and are looked up again later.
 * Every call must run on a background thread.
 */
object LyricsTranslation {

    private const val BASE_URL = "https://music.163.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; NavMusic) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val SAVED_DIR = "lyrics_translations"
    private const val NEGATIVE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CACHE_FILES = 1500
    private const val LOOKUP_TIMEOUT_MS = 9_000L

    /** Same or almost the same timestamp means two lines belong to one sung line. */
    private const val EMBEDDED_TOLERANCE_MS = 150L

    /** How far a translation timestamp may drift from the original in the direct pass. */
    private const val DIRECT_TOLERANCE_MS = 2_000L

    /** Stricter tolerance once a global offset has been estimated. */
    private const val OFFSET_TOLERANCE_MS = 900L

    private val CJK = Regex("""[\u3400-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]""")
    private val INLINE_SPLIT = Regex("""\s*(?:/|\||｜|\uFF5C)\s*""")
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Attaches translations to [lines]. The data is always collected - embedded bilingual lines
     * are merged and the optional online lookup fills the gaps - because the driver can switch
     * between "original only", "original + translation" and "translation only" at any time.
     * Keeping the same underlying lines makes that switch instant and consistent.
     */
    fun apply(
        context: Context,
        track: Track,
        lines: List<TimedLyric>,
        durationMs: Long
    ): List<TimedLyric> {
        if (lines.isEmpty()) return lines

        val bilingual = mergeEmbedded(lines)
        val result = if (!Settings.onlineLyricsTranslation) {
            bilingual
        } else {
            // A Navidrome library with its own translations needs no extra request.
            val covered = bilingual.count { !it.translation.isNullOrBlank() }
            if (covered * 2 >= bilingual.size) {
                bilingual
            } else {
                val remote = fetch(
                    context.applicationContext,
                    track.title.orEmpty(),
                    track.artist.orEmpty(),
                    durationMs
                )
                if (remote.isNullOrEmpty()) bilingual else align(bilingual, remote)
            }
        }
        // NetEase `tlyric` (and some embedded bilingual LRC) is often Traditional Chinese.
        // Normalise only the translation slot to Simplified. Original text/words keep their
        // exact characters so the karaoke wipe timeline is untouched. This is the single
        // exit of apply(), so old Traditional bodies in the on-disk tlyric cache are
        // converted on every read without any cache-key bump.
        return result.map { line ->
            line.translation?.let { text ->
                val simplified = ChineseText.toSimplifiedChinese(text)
                if (simplified === text) line else line.copy(translation = simplified)
            } ?: line
        }
    }

    // -------------------------------------------------------------------------------------
    // Embedded bilingual LRC
    // -------------------------------------------------------------------------------------

    /**
     * Merges translation lines that carry the same timestamp as the original into a single
     * [TimedLyric.translation]. Also recognises `original / translation` inside one line.
     *
     * The word timeline of the original is preserved verbatim; a translation never takes part
     * in the karaoke wipe.
     */
    fun mergeEmbedded(lines: List<TimedLyric>): List<TimedLyric> {
        if (lines.isEmpty()) return lines
        val result = ArrayList<TimedLyric>(lines.size)

        for (line in lines.sortedBy { it.startMs }) {
            val body = line.text.trim()
            val parts = INLINE_SPLIT.split(body).filter { it.isNotBlank() }
            val inline = parts.takeIf { it.size == 2 && !hasCjk(it[0]) && hasCjk(it[1]) }
            val main = inline?.get(0) ?: body
            val ownTranslation = inline?.get(1)

            val previous = result.lastOrNull()
            val sameTime = previous != null &&
                abs(previous.startMs - line.startMs) <= EMBEDDED_TOLERANCE_MS &&
                previous.translation.isNullOrBlank()

            when {
                // "original / 译文" in one line. The text no longer matches the word timeline,
                // so this line falls back to whole-line highlighting.
                inline != null -> result.add(
                    TimedLyric(line.startMs, main, emptyList(), ownTranslation)
                )
                // The CJK line follows the original at the same timestamp.
                sameTime && hasCjk(main) && !hasCjk(previous!!.text) -> {
                    result[result.lastIndex] = previous.copy(translation = main)
                }
                // The CJK line came first: keep the non-CJK one as the sung line.
                sameTime && !hasCjk(main) && hasCjk(previous!!.text) -> {
                    result[result.lastIndex] = TimedLyric(
                        line.startMs,
                        main,
                        line.words,
                        previous.text
                    )
                }
                else -> result.add(
                    TimedLyric(line.startMs, main, line.words, ownTranslation)
                )
            }
        }
        return result
    }

    private fun hasCjk(text: String): Boolean = CJK.containsMatchIn(text)

    // -------------------------------------------------------------------------------------
    // Timestamp alignment
    // -------------------------------------------------------------------------------------

    private data class Match(val lines: List<TimedLyric>, val count: Int)

    /**
     * Matches translations by their real timestamps. The direct pass handles sources that use
     * exactly the same recording; the offset pass handles a translation whose LRC was written
     * against a slightly different cut of the song.
     */
    fun align(lines: List<TimedLyric>, translations: List<TimedLyric>): List<TimedLyric> {
        val remote = translations
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startMs }
        if (remote.isEmpty()) return lines

        val direct = match(lines, remote, 0L, DIRECT_TOLERANCE_MS)

        // Estimate one global offset from the nearest translation of each line. A median is
        // robust against the metadata lines some LRC files start with.
        val deltas = lines.mapNotNull { line ->
            remote.minByOrNull { abs(it.startMs - line.startMs) }
                ?.let { it.startMs - line.startMs }
        }.filter { abs(it) <= 20_000L }.sorted()
        val offset = if (deltas.size >= 3) deltas[deltas.size / 2] else 0L

        val shifted = if (abs(offset) > 250L) {
            match(lines, remote, offset, OFFSET_TOLERANCE_MS)
        } else {
            null
        }
        return (shifted?.takeIf { it.count > direct.count } ?: direct).lines
    }

    /** Nearest unused translation within [tolerance], one translation per original line. */
    private fun match(
        lines: List<TimedLyric>,
        remote: List<TimedLyric>,
        offsetMs: Long,
        toleranceMs: Long
    ): Match {
        val used = BooleanArray(remote.size)
        var count = 0
        val result = ArrayList<TimedLyric>(lines.size)
        for (line in lines) {
            if (!line.translation.isNullOrBlank()) {
                result.add(line)
                continue
            }
            val target = line.startMs + offsetMs
            var best = -1
            var bestDelta = Long.MAX_VALUE
            for (index in remote.indices) {
                if (used[index]) continue
                val delta = abs(remote[index].startMs - target)
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = index
                }
            }
            if (best >= 0 && bestDelta <= toleranceMs) {
                used[best] = true
                count++
                result.add(line.copy(translation = remote[best].text.trim()))
            } else {
                result.add(line)
            }
        }
        return Match(result, count)
    }

    // -------------------------------------------------------------------------------------
    // Optional online lookup
    // -------------------------------------------------------------------------------------

    /** Cached Chinese translation in LRC form, or null when the service has none. */
    private fun fetch(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long
    ): List<TimedLyric>? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        val file = cacheFile(context, cleanTitle, artist)
        readCached(file)?.let { cached ->
            return if (cached.isBlank()) null else LyricsParser.parse(cached)
        }

        val deadline = SystemClock.elapsedRealtime() + LOOKUP_TIMEOUT_MS
        val query = URLEncoder.encode("$cleanTitle ${artist.trim()}", Charsets.UTF_8.name())
        val search = get(
            "$BASE_URL/api/search/get/web?s=$query&type=1&offset=0&limit=5",
            deadline
        )
        val songId = bestSongId(search, cleanTitle, artist, durationMs)
        if (songId == null) {
            writeNegative(file)
            return null
        }

        val body = get(
            "$BASE_URL/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1",
            deadline
        )
        val translated = body?.let { parseTranslation(it) }
        if (translated.isNullOrBlank()) {
            writeNegative(file)
            return null
        }
        writePositive(file, translated)
        return LyricsParser.parse(translated)
    }

    /** Reads `tlyric.lyric`; an absent or empty translation is not an error, just no data. */
    private fun parseTranslation(json: String): String? = try {
        JSONObject(json)
            .optJSONObject("tlyric")
            ?.optString("lyric")
            ?.takeIf { it.isNotBlank() && LyricsParser.parse(it).isNotEmpty() }
    } catch (all: Exception) {
        Timber.w(all, "Could not read the online lyric translation")
        null
    }

    /** Picks the closest song by title, artist and duration. A weak match is rejected. */
    private fun bestSongId(
        json: String?,
        title: String,
        artist: String,
        durationMs: Long
    ): Long? {
        if (json.isNullOrBlank()) return null
        return try {
            val songs = JSONObject(json).optJSONObject("result")?.optJSONArray("songs")
                ?: return null
            var bestId: Long? = null
            var bestScore = 0
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                var score = 0
                val foundTitle = normalize(song.optString("name"))
                val wantedTitle = normalize(title)
                if (foundTitle.isNotBlank() && wantedTitle.isNotBlank() &&
                    (foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle))
                ) score += 40
                val foundArtist = normalize(
                    song.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                )
                val wantedArtist = normalize(artist)
                if (foundArtist.isNotBlank() && wantedArtist.isNotBlank() &&
                    (foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist))
                ) score += 25
                val foundDuration = song.optLong("duration", 0L)
                if (durationMs > 0 && foundDuration > 0) {
                    val difference = abs(foundDuration - durationMs)
                    if (difference <= 3_000L) score += 25
                    else if (difference <= 8_000L) score += 8
                }
                if (score > bestScore) {
                    bestScore = score
                    bestId = song.optLong("id", 0L).takeIf { it > 0 }
                }
            }
            // Title or artist alone is not enough: a wrong song would show a wrong translation.
            bestId.takeIf { bestScore >= 40 }
        } catch (all: Exception) {
            Timber.w(all, "Could not rank translation search results")
            null
        }
    }

    private fun get(url: String, deadline: Long): String? {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/")
                .header("Accept", "application/json")
                .build()
            val timed = client.newBuilder()
                .callTimeout(remaining.coerceAtMost(12_000L), TimeUnit.MILLISECONDS)
                .build()
            timed.newCall(request).execute().use { response ->
                if (response.code != 200) null else response.body?.string()
            }
        } catch (all: Exception) {
            Timber.w(all, "Translation lookup failed: %s", url.take(90))
            null
        }
    }

    // -------------------------------------------------------------------------------------
    // Small on-device cache (positive forever, negative for one day)
    // -------------------------------------------------------------------------------------

    /** Removes every cached translation. May do disk IO: call off the main thread. */
    fun clearCache(context: Context): Int {
        var removed = 0
        savedDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".json") && file.delete()) removed++
        }
        return removed
    }

    fun cachedCount(context: Context): Int = savedDir(context).listFiles()
        ?.count { it.name.endsWith(".json") && !readCached(it).isNullOrBlank() }
        ?: 0

    /** null = no usable entry, blank = fresh negative, text = saved translation. */
    private fun readCached(file: File): String? {
        if (!file.exists()) return null
        return try {
            val entry = JSONObject(file.readText())
            if (entry.optBoolean("negative", false)) {
                val fresh = System.currentTimeMillis() - entry.optLong("ts", 0L) <= NEGATIVE_TTL_MS
                if (!fresh) file.delete()
                if (fresh) "" else null
            } else {
                entry.optString("body", "").takeIf { it.isNotBlank() }?.also {
                    file.setLastModified(System.currentTimeMillis())
                }
            }
        } catch (all: Exception) {
            null
        }
    }

    private fun writePositive(file: File, body: String) =
        writeEntry(file, negative = false, body = body)

    private fun writeNegative(file: File) =
        writeEntry(file, negative = true, body = "")

    private fun writeEntry(file: File, negative: Boolean, body: String) {
        try {
            val entry = JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("negative", negative)
            entry.put("body", body)
            file.writeText(entry.toString())
            enforceFileLimit(file.parentFile)
        } catch (all: Exception) {
            Timber.w(all, "Could not save a lyric translation")
        }
    }

    private fun savedDir(context: Context): File {
        val dir = File(context.filesDir, SAVED_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun enforceFileLimit(dir: File?) {
        val files = dir?.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            ?: return
        val excess = files.size - MAX_CACHE_FILES
        if (excess > 0) files.take(excess).forEach { it.delete() }
    }

    private fun cacheFile(context: Context, title: String, artist: String): File =
        File(savedDir(context), "${cacheKey(normalize(title), normalize(artist))}.json")

    private fun normalize(value: String): String =
        PUNCTUATION.replace(value.lowercase(Locale.ROOT), " ").trim()

    private fun cacheKey(vararg parts: String): String =
        MessageDigest.getInstance("MD5")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }

    const val MODE_OFF = "off"
}
