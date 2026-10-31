/*
 * LyricsParserTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsParserTest {

    @Test
    fun `parses simple LRC lines`() {
        val raw = """
            [00:01.00]first line
            [00:12.50]second line
            [01:05.25]third line
        """.trimIndent()

        val lines = LyricsParser.parse(raw)

        assertEquals(3, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals("first line", lines[0].text)
        assertEquals(12_500L, lines[1].startMs)
        assertEquals(65_250L, lines[2].startMs)
    }

    @Test
    fun `expands several timestamps in front of one line`() {
        val lines = LyricsParser.parse("[00:10.00][00:40.00]chorus text")

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].startMs)
        assertEquals(40_000L, lines[1].startMs)
        assertEquals("chorus text", lines[1].text)
    }

    @Test
    fun `ignores metadata tags`() {
        val raw = """
            [ar:Some Artist]
            [ti:Some Title]
            [00:03.00]only line
        """.trimIndent()

        val lines = LyricsParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals("only line", lines[0].text)
    }

    @Test
    fun `plain text without timestamps returns no timed lines`() {
        val lines = LyricsParser.parse("just some plain lyrics\nwithout any timing")

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `line lookup follows the playback position`() {
        val lines = listOf(
            TimedLyric(1_000L, "a"),
            TimedLyric(5_000L, "b"),
            TimedLyric(9_000L, "c")
        )

        assertEquals(-1, LyricsParser.lineIndexAt(lines, 0L))
        assertEquals(0, LyricsParser.lineIndexAt(lines, 1_000L))
        assertEquals(0, LyricsParser.lineIndexAt(lines, 4_999L))
        assertEquals(1, LyricsParser.lineIndexAt(lines, 5_000L))
        assertEquals(2, LyricsParser.lineIndexAt(lines, 60_000L))
    }

    @Test
    fun `parses timestamps without a fraction`() {
        val lines = LyricsParser.parse("[00:12]a\n[01:05]b")

        assertEquals(2, lines.size)
        assertEquals(12_000L, lines[0].startMs)
        assertEquals(65_000L, lines[1].startMs)
    }

    @Test
    fun `parses single digit minutes and milliseconds`() {
        val lines = LyricsParser.parse("[1:02.345]x")

        assertEquals(1, lines.size)
        assertEquals(62_345L, lines[0].startMs)
        assertEquals("x", lines[0].text)
    }

    @Test
    fun `parses hour prefixed timestamps`() {
        val lines = LyricsParser.parse("[01:02:03.45]x")

        assertEquals(1, lines.size)
        assertEquals(3_723_450L, lines[0].startMs)
    }

    @Test
    fun `keeps empty lines as timed entries`() {
        val lines = LyricsParser.parse("[00:10.00]\n[00:20.00]text")

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].startMs)
        assertEquals("", lines[0].text)
        assertEquals("text", lines[1].text)
    }

    @Test
    fun `handles carriage returns between the lines`() {
        val lines = LyricsParser.parse("[00:10.00]a\r\n[00:20.00]b")

        assertEquals(2, lines.size)
        assertEquals("a", lines[0].text)
        assertEquals("b", lines[1].text)
    }

    @Test
    fun `sorts the lines by their time`() {
        val lines = LyricsParser.parse("[00:30.00]third\n[00:10.00]first\n[00:20.00]second")

        assertEquals(3, lines.size)
        assertEquals("first", lines[0].text)
        assertEquals("second", lines[1].text)
        assertEquals("third", lines[2].text)
    }

    @Test
    fun `the highlight follows the position over a whole song`() {
        // This is the behaviour the lyrics scrolling depends on: the line that is
        // highlighted is the one that is being sung at the current position.
        val raw = """
            [00:00.00]intro
            [00:15.00]verse one
            [00:32.50]chorus
            [01:10.00]bridge
        """.trimIndent()

        val lines = LyricsParser.parse(raw)

        assertEquals(0, LyricsParser.lineIndexAt(lines, 0L))
        assertEquals(0, LyricsParser.lineIndexAt(lines, 14_999L))
        assertEquals(1, LyricsParser.lineIndexAt(lines, 15_000L))
        assertEquals(2, LyricsParser.lineIndexAt(lines, 40_000L))
        assertEquals(3, LyricsParser.lineIndexAt(lines, 70_000L))
        // The song is over, the last line stays highlighted.
        assertEquals(3, LyricsParser.lineIndexAt(lines, 600_000L))
    }

    @Test
    fun `a single line without a newline is still timed`() {
        val lines = LyricsParser.parse("[00:05.00]only line")

        assertEquals(1, lines.size)
        assertEquals(5_000L, lines[0].startMs)
        assertEquals("only line", lines[0].text)
    }

    @Test
    fun `plain lyrics are spread over the length of the song`() {
        val text = """
            first line
            second line
            third line
            fourth line
        """.trimIndent()

        val lines = LyricsParser.spreadPlainLyrics(text, durationMs = 240_000L)

        assertEquals(4, lines.size)
        // Four lines over four minutes: one line per minute.
        assertEquals(0L, lines[0].startMs)
        assertEquals(60_000L, lines[1].startMs)
        assertEquals(120_000L, lines[2].startMs)
        assertEquals(180_000L, lines[3].startMs)
    }

    @Test
    fun `the spread plain lyrics move along with the song`() {
        val text = "one\ntwo\nthree\nfour"
        val lines = LyricsParser.spreadPlainLyrics(text, durationMs = 240_000L)

        // This is what the scrolling depends on: the highlighted line changes over time.
        assertEquals(0, LyricsParser.lineIndexAt(lines, 0L))
        assertEquals(0, LyricsParser.lineIndexAt(lines, 59_999L))
        assertEquals(1, LyricsParser.lineIndexAt(lines, 60_000L))
        assertEquals(2, LyricsParser.lineIndexAt(lines, 130_000L))
        assertEquals(3, LyricsParser.lineIndexAt(lines, 200_000L))
    }

    @Test
    fun `empty lines of plain lyrics are dropped`() {
        val text = "first\n\n\nsecond\n   \nthird"

        val lines = LyricsParser.spreadPlainLyrics(text, durationMs = 60_000L)

        assertEquals(3, lines.size)
        assertEquals("first", lines[0].text)
        assertEquals("second", lines[1].text)
        assertEquals("third", lines[2].text)
    }

    @Test
    fun `plain lyrics without a known length still give every line a position`() {
        val text = "one\ntwo\nthree"

        val lines = LyricsParser.spreadPlainLyrics(text, durationMs = 0L)

        assertEquals(3, lines.size)
        // Without a length there is no time axis, but every line has to be reachable.
        assertEquals(0L, lines[0].startMs)
        assertEquals(1L, lines[1].startMs)
        assertEquals(2L, lines[2].startMs)
    }

    @Test
    fun `blank plain lyrics give nothing`() {
        assertTrue(LyricsParser.spreadPlainLyrics(null, 60_000L).isEmpty())
        assertTrue(LyricsParser.spreadPlainLyrics("", 60_000L).isEmpty())
        assertTrue(LyricsParser.spreadPlainLyrics("   \n  \n", 60_000L).isEmpty())
    }
}
