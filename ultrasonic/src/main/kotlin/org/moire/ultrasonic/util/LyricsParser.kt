/*
 * LyricsParser.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

/** A single line of lyrics together with the moment it has to be shown. */
data class TimedLyric(val startMs: Long, val text: String)

/**
 * Parser for the lyrics formats a Subsonic / Navidrome server returns.
 *
 * The servers either send plain text without any timing, or LRC lines like
 * `[00:12.34]text`, sometimes with several timestamps in front of one line. Both are supported:
 * timed lyrics are returned as a list, untimed lyrics return an empty list and the caller falls
 * back to showing the text as it is.
 */
object LyricsParser {

    /**
     * `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` and `[hh:mm:ss.xx]`, sometimes several of them
     * in front of one line. Metadata tags such as `[ar:...]` do not match, because the second
     * group has to be digits followed by a colon.
     */
    private val TIME_TAG = Regex("""\[(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * `[offset:±123]` or `[offset:±1.5]`. A negative value shifts the lyrics earlier, a positive
     * one later. The LRC spec uses milliseconds, but a decimal point means seconds, which a lot of
     * editors emit, so both are supported. One such line anywhere in the file shifts every line.
     */
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+(?:\.\d+)?)\s*]""")

    private const val MS_PER_SECOND = 1_000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L

    fun parse(raw: String?): List<TimedLyric> {
        if (raw.isNullOrBlank()) return emptyList()

        var globalOffsetMs = 0L
        val timed = ArrayList<TimedLyric>()
        raw.lineSequence().forEach { line ->
            val offsetMatch = OFFSET_TAG.find(line)
            if (offsetMatch != null) {
                globalOffsetMs = parseOffset(offsetMatch.groupValues[1])
                return@forEach
            }

            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            val text = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { match ->
                val hours = match.groupValues[1].toLongOrNull() ?: 0L
                val minutes = match.groupValues[2].toLongOrNull() ?: 0L
                val seconds = match.groupValues[3].toLongOrNull() ?: 0L
                val startMs = hours * MS_PER_HOUR + minutes * MS_PER_MINUTE +
                    seconds * MS_PER_SECOND + fractionMs(match.groupValues[4])
                timed.add(TimedLyric(startMs + globalOffsetMs, text))
            }
        }

        return timed.sortedBy { it.startMs }
    }

    /** Parses an LRC offset value: decimals are seconds, integers are milliseconds. */
    private fun parseOffset(value: String): Long {
        val double = value.toDoubleOrNull() ?: return 0L
        return if (value.contains('.')) (double * MS_PER_SECOND).toLong() else double.toLong()
    }

    /** Index of the line that is playing at [positionMs], or -1 before the first line starts. */
    fun lineIndexAt(lines: List<TimedLyric>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1

        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /**
     * Spreads the lines of lyrics without a time axis over the length of the song.
     *
     * A lot of the songs on a server only have plain lyrics. Without a time axis the text
     * cannot follow the song, and showing the whole text in a three line window means that
     * only the first three lines are ever visible.
     *
     * The lines are spread evenly over the duration of the song instead. The result is not
     * the real timing of the words, but the lyrics move along with the song and every line
     * becomes visible, which is much better than a frozen block of text.
     *
     * @param text the plain lyrics.
     * @param durationMs the length of the song, in milliseconds.
     * @return the lines with an evenly spread time axis.
     */
    fun spreadPlainLyrics(text: String?, durationMs: Long): List<TimedLyric> {
        if (text.isNullOrBlank()) return emptyList()

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        // A song of unknown length would put every line at the very beginning, so the
        // lines would all appear at once. In that case there is no time axis to make.
        if (durationMs <= 0) {
            return lines.mapIndexed { index, line ->
                TimedLyric(index.toLong(), line)
            }
        }

        val step = durationMs / lines.size
        return lines.mapIndexed { index, line ->
            TimedLyric(index * step, line)
        }
    }

    private fun fractionMs(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        return fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    }
}
