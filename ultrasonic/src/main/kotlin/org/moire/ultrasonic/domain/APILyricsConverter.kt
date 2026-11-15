// Converts Lyrics entity from [org.moire.ultrasonic.api.subsonic.SubsonicAPIClient]
// to app domain entities.
@file:JvmName("APILyricsConverter")
package org.moire.ultrasonic.domain

import org.moire.ultrasonic.api.subsonic.models.LyricsCueLine
import org.moire.ultrasonic.api.subsonic.models.LyricsList
import org.moire.ultrasonic.api.subsonic.models.Lyrics as APILyrics
import org.moire.ultrasonic.api.subsonic.models.StructuredLyric

fun APILyrics.toDomainEntity(): Lyrics = Lyrics(
    artist = this@toDomainEntity.artist,
    title = this@toDomainEntity.title,
    text = this@toDomainEntity.text
)

/**
 * Converts the lyrics list of the OpenSubsonic endpoint.
 *
 * A song can have several lyrics: one plain text version for printing, one with the time axis,
 * and - with `enhanced=true` - translations and pronunciations. Only the main vocal layer is
 * displayed, and the entry with real timings is preferred.
 */
fun LyricsList.toDomainEntity(): Lyrics {
    val main = structuredLyrics.filter { it.kind.isBlank() || it.kind == "main" }
    val pool = main.ifEmpty { structuredLyrics }
    val chosen = pool.firstOrNull { it.synced && it.lines.any { line -> line.startMs != null } }
        ?: pool.firstOrNull { it.synced }
        ?: pool.firstOrNull { it.lines.any { line -> line.startMs != null } }
        ?: pool.firstOrNull()
        ?: return Lyrics()

    return chosen.toDomainEntity()
}

/**
 * Converts one structured lyric into the domain model.
 *
 * OpenSubsonic defines `offset` as "the offset to apply to all lyric timing ... Positive means
 * lyrics appear sooner, negative means later", so the value is **subtracted** from every start
 * (a positive number moves the lyric to an earlier position). Enhanced responses additionally
 * carry `cueLine` word/syllable timings, which are turned into [TimedLyricLine.words]. A cue
 * line whose byte offsets do not rebuild its own text exactly is ignored, so the wipe can never
 * drift away from the characters on screen.
 */
fun StructuredLyric.toDomainEntity(): Lyrics {
    val offset = offsetMs ?: 0L
    val cuesByIndex = LinkedHashMap<Int, LyricsCueLine>()
    if (synced) {
        cueLines.forEach { cueLine ->
            val index = cueLine.index ?: return@forEach
            // Several cue lines can share an index (lead + background vocals). The spec puts
            // the main layer first, and that is the one the player renders.
            if (!cuesByIndex.containsKey(index)) cuesByIndex[index] = cueLine
        }
    }

    val timed = lines.mapIndexedNotNull { index, line ->
        val start = line.startMs ?: return@mapIndexedNotNull null
        val cueLine = cuesByIndex[index]
        val cueWords = cueLine?.let { wordsFromCue(it, offset) }
        val text = cueWords?.first ?: line.text
        val words = cueWords?.second ?: emptyList()
        TimedLyricLine(
            startMs = (start - offset).coerceAtLeast(0L),
            text = text,
            words = words
        )
    }
    return Lyrics(
        artist = displayArtist.ifEmpty { null },
        title = displayTitle.ifEmpty { null },
        text = lines.joinToString("\n") { it.text },
        synced = synced,
        timedLines = timed.sortedBy { it.startMs }
    )
}

/**
 * Rebuilds the displayed text of a cue line from its cue byte ranges.
 *
 * `cue[].byteStart` / `byteEnd` are UTF-8 byte offsets into `cueLine.value`. Text between two
 * cues is untimed and is attached to the following timed segment; the resulting word texts
 * concatenate to exactly `cueLine.value`.
 */
private fun wordsFromCue(
    cueLine: LyricsCueLine,
    offset: Long
): Pair<String, List<TimedLyricWord>>? {
    val value = cueLine.value
    if (value.isEmpty() || cueLine.cue.isEmpty()) return null

    val byteCount = value.toByteArray(Charsets.UTF_8).size
    val byteToChar = IntArray(byteCount + 1)
    var byteIndex = 0
    var charIndex = 0
    while (charIndex < value.length) {
        val codePoint = value.codePointAt(charIndex)
        val width = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        if (byteIndex + width > byteCount) return null
        for (offsetInBytes in 0 until width) byteToChar[byteIndex + offsetInBytes] = charIndex
        byteIndex += width
        charIndex += Character.charCount(codePoint)
    }
    for (index in byteIndex..byteCount) byteToChar[index] = value.length

    fun charAt(byte: Int): Int? = if (byte in 0..byteCount) byteToChar[byte] else null

    val words = ArrayList<TimedLyricWord>()
    var cursor = 0
    var previousStart = Long.MIN_VALUE
    for (cue in cueLine.cue.sortedBy { it.byteStart ?: Int.MAX_VALUE }) {
        val startChar = charAt(cue.byteStart ?: return null) ?: return null
        val endChar = charAt(cue.byteEnd ?: return null) ?: return null
        if (startChar < cursor || endChar < startChar || endChar > value.length) return null
        val segment = value.substring(cursor, endChar)
        if (segment.isEmpty()) continue
        val startMs = (cue.startMs - offset).coerceAtLeast(0L)
        if (startMs < previousStart) return null
        words.add(TimedLyricWord(startMs, segment))
        previousStart = startMs
        cursor = endChar
    }

    if (cursor < value.length) {
        if (words.isEmpty()) return null
        val last = words.removeAt(words.size - 1)
        words.add(TimedLyricWord(last.startMs, last.text + value.substring(cursor)))
    }
    if (words.isEmpty() || words.joinToString("") { it.text } != value) return null
    return value to words
}
