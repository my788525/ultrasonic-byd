/*
 * WordLyricsParser.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

/**
 * Parser for word level lyrics (karaoke / 逐字歌词).
 *
 * Two formats are handled, both with real, externally documented timestamps:
 *
 *  - Enhanced LRC (the A2 extension): `[00:12.34]<00:12.34>Every <00:12.60>time ...`.
 *    OpenSubsonic servers and user supplied .lrc files sometimes deliver this. The same
 *    `<MM:SS.mm> word` grammar is what SimpMusic's RichSyncParser consumes.
 *  - LRCLIB's "Lyricsfile" YAML: `lines[].words[]` with `text` and `start_ms`, documented at
 *    https://lrclib.net/docs.txt. Every LRCLIB record ships one and records with
 *    `hasWordSync: true` carry real per word timings in it. This is the source used by the
 *    automatic online lyrics search.
 *
 * Only the small, regular subset of YAML that LRCLIB emits is understood; no YAML library is
 * pulled into the app for it. Anything unexpected is ignored rather than guessed, and the caller
 * falls back to line synced lyrics.
 *
 * Invariant: concatenating the [TimedWord.text] of a line reproduces [TimedLyric.text] exactly,
 * including the spaces between words. SyncedLyricsView relies on that to place the wipe.
 */
object WordLyricsParser {

    /** `<mm:ss.xx>`, `<mm:ss.xxx>`, `<hh:mm:ss.xx>` and `<mm:ss>`. */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?>|<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** A `lines:` item in LRCLIB's YAML starts at column zero. */
    private val LINE_ITEM = Regex("""^-\s*text:\s*(.*)$""")

    /** A `words:` item is indented by at least one space. */
    private val WORD_ITEM = Regex("""^\s+-\s*text:\s*(.*)$""")

    private val NUMBER = Regex("""-?\d+""")

    private const val MS_PER_SECOND = 1_000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L

    /**
     * Splits the body of an Enhanced LRC line into the text to display and its word timings.
     *
     * @return null when the line has no usable word tags, so the caller keeps ordinary line
     *         synced highlighting instead of showing a broken wipe.
     */
    fun parseEnhanced(body: String): Pair<String, List<TimedWord>>? {
        if (body.isBlank() || !body.contains('<')) return null

        val tags = WORD_TAG.findAll(body).toList()
        if (tags.size < 2) return null

        val words = ArrayList<TimedWord>(tags.size)
        val display = StringBuilder()
        tags.forEachIndexed { index, match ->
            val end = if (index + 1 < tags.size) tags[index + 1].range.first else body.length
            var segment = body.substring(match.range.last + 1, end)
            if (index == 0) segment = segment.trimStart()
            if (index == tags.size - 1) segment = segment.trimEnd()
            if (segment.isEmpty()) return@forEachIndexed

            words.add(TimedWord(tagTimeMs(match), segment))
            display.append(segment)
        }

        if (words.size < 2 || display.isBlank()) return null
        return display.toString() to words
    }

    /** Parses LRCLIB's Lyricsfile YAML into timed lines, with word timings when present. */
    fun parseLyricsFile(yaml: String?): List<TimedLyric> {
        if (yaml.isNullOrBlank() || !yaml.contains("lines:")) return emptyList()

        val result = ArrayList<TimedLyric>()
        var inLines = false
        var inWords = false
        var lineText = ""
        var lineStartMs = 0L
        val words = ArrayList<TimedWord>()
        var pendingWordText: String? = null
        var pendingWordStartMs = 0L

        fun flushWord() {
            val text = pendingWordText ?: return
            words.add(TimedWord(pendingWordStartMs, text))
            pendingWordText = null
            pendingWordStartMs = 0L
        }

        fun flushLine() {
            flushWord()
            val wordList: List<TimedWord>
            val text: String
            if (words.isNotEmpty()) {
                text = words.joinToString("") { it.text }
                wordList = ArrayList(words)
            } else {
                text = lineText
                wordList = emptyList()
            }
            if (text.isNotBlank()) {
                result.add(TimedLyric(lineStartMs, text, wordList))
            }
            lineText = ""
            lineStartMs = 0L
            words.clear()
            inWords = false
        }

        for (rawLine in yaml.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed == "lines:") {
                flushLine()
                inLines = true
                continue
            }
            if (!inLines) continue

            // Indented "- text:" belongs to the current "words:" list; a column-zero one starts
            // a new lyric line. LRCLIB emits both forms exactly like this.
            val wordItem = if (inWords && line.startsWith(" ")) WORD_ITEM.matchEntire(line) else null
            if (wordItem != null) {
                flushWord()
                pendingWordText = yamlScalar(wordItem.groupValues[1])
                continue
            }
            val lineItem = LINE_ITEM.matchEntire(line)
            if (lineItem != null) {
                flushLine()
                lineText = yamlScalar(lineItem.groupValues[1])
                continue
            }

            when {
                trimmed == "words:" -> inWords = true
                trimmed.startsWith("start_ms:") -> {
                    val value = NUMBER.find(trimmed)?.value?.toLongOrNull() ?: 0L
                    if (inWords && pendingWordText != null) {
                        pendingWordStartMs = value
                    } else if (lineText.isNotEmpty()) {
                        lineStartMs = value
                    }
                }
                // end_ms, translations, language and block scalars carry nothing we need.
            }
        }
        flushLine()

        return result.sortedBy { it.startMs }
    }

    /** Index of the word that is being sung at [positionMs], or -1 before the first one. */
    fun wordIndexAt(words: List<TimedWord>, positionMs: Long): Int {
        if (words.isEmpty()) return -1
        var low = 0
        var high = words.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (words[middle].startMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /** Character count of the words already sung, used as the end of the highlighted span. */
    fun sungLength(words: List<TimedWord>, wordIndex: Int): Int {
        if (wordIndex < 0 || words.isEmpty()) return 0
        var total = 0
        val last = wordIndex.coerceAtMost(words.size - 1)
        for (index in 0..last) total += words[index].text.length
        return total
    }

    /**
     * Fractional character offset of the wipe at [positionMs].
     *
     * Unlike [sungLength], which jumps a whole word at a time, this interpolates *inside* the
     * word being sung using its real start timestamp and the next real timestamp (the following
     * word's start, or [lineEndMs] for the last word). The integer part is the number of fully
     * sung characters; the fractional part is how far the character currently being sung has
     * progressed. The lyrics view blends that character's colour, which removes the
     * "one block at a time" stepping even when the player only reports its position twice a
     * second.
     *
     * No duration is invented: when the next timestamp is missing or malformed the word is
     * simply shown complete at its own start.
     *
     * @param lineEndMs start of the next lyric line, used only as the end of the last word.
     */
    fun sungFraction(
        words: List<TimedWord>,
        positionMs: Long,
        lineEndMs: Long
    ): Float {
        if (words.isEmpty()) return 0f
        val index = wordIndexAt(words, positionMs)
        if (index < 0) return 0f

        val word = words[index]
        val before = sungLength(words, index - 1).toFloat()
        val length = word.text.length
        if (length == 0) return before

        val start = word.startMs
        val nextStart = words.getOrNull(index + 1)?.startMs ?: -1L
        val end = when {
            index < words.size - 1 && nextStart > start -> nextStart
            lineEndMs > start -> lineEndMs
            else -> -1L
        }
        if (end <= start) return before + length

        val progress = ((positionMs - start).toDouble() / (end - start).toDouble())
            .coerceIn(0.0, 1.0)
        return before + (length * progress).toFloat()
    }

    private fun tagTimeMs(match: MatchResult): Long {
        val groups = match.groupValues
        // The first alternative is <hh:mm:ss.xx>, the second <mm:ss.xx>.
        val hasHours = groups[1].isNotEmpty()
        val hours = if (hasHours) groups[1].toLongOrNull() ?: 0L else 0L
        val minutes = if (hasHours) groups[2].toLongOrNull() ?: 0L
        else groups[5].toLongOrNull() ?: 0L
        val seconds = if (hasHours) groups[3].toLongOrNull() ?: 0L
        else groups[6].toLongOrNull() ?: 0L
        val fraction = if (hasHours) groups[4] else groups[7]
        return hours * MS_PER_HOUR + minutes * MS_PER_MINUTE +
            seconds * MS_PER_SECOND + fractionMs(fraction)
    }

    private fun fractionMs(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        return fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    }

    /** Reads the scalar forms LRCLIB uses: 'single quoted', "double quoted" or bare. */
    private fun yamlScalar(raw: String): String {
        val value = raw.trim()
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
        }
        // "|", ">" and friends start a block scalar. LRCLIB does not use those for lyric
        // text, and inventing content for them would be worse than showing nothing.
        if (value == "|" || value == ">" || value == "|-" || value == ">-") return ""
        return value
    }
}
