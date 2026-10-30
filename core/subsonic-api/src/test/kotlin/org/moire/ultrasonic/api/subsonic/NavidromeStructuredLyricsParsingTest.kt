package org.moire.ultrasonic.api.subsonic

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moire.ultrasonic.api.subsonic.response.GetLyricsBySongIdResponse

/**
 * Regression test driven by a real payload captured from a Navidrome server (0.61.2).
 *
 * The plain Subsonic endpoint flattens the lyrics into one text block and drops every
 * timestamp, so the app cannot scroll along with the song when it only asks for that one.
 * The OpenSubsonic endpoint keeps the timings, but it names the list "structuredLyrics" and
 * puts the timing in a "start" field of every line.
 *
 * If this payload fails to parse, the lyrics show up as one flat block that never moves.
 */
class NavidromeStructuredLyricsParsingTest {

    private val mapper: ObjectMapper = SubsonicAPIClient.jacksonMapper

    private fun readFixture(name: String): String =
        javaClass.getResourceAsStream("/navidrome/$name")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `Should parse the structured lyrics of Navidrome`() {
        val response: GetLyricsBySongIdResponse =
            mapper.readValue(
                readFixture("getLyricsBySongId.json"),
                GetLyricsBySongIdResponse::class.java
            )

        val lyrics = response.lyricsList.structuredLyrics
        assertTrue("the lyrics must not be empty", lyrics.isNotEmpty())

        val first = lyrics.first()
        assertEquals("Tule/Chris Linton", first.displayArtist)
        assertEquals("Fearless, Pt. II", first.displayTitle)
        assertTrue("the lyrics must be marked as synced", first.synced)
        assertTrue("the lines must not be empty", first.lines.isNotEmpty())
    }

    @Test
    fun `Should read the time of every line`() {
        val response: GetLyricsBySongIdResponse =
            mapper.readValue(
                readFixture("getLyricsBySongId.json"),
                GetLyricsBySongIdResponse::class.java
            )

        val lines = response.lyricsList.structuredLyrics.first().lines

        assertEquals(0L, lines[0].startMs)
        assertEquals("Fearless Pt. II - Tule/Chris Linton", lines[0].text)
        assertEquals(5_070L, lines[1].startMs)
        assertEquals(10_150L, lines[2].startMs)

        // Every line has to carry a time, otherwise the lyrics cannot be shown in sync.
        assertTrue("some lines have no time", lines.all { it.startMs != null })
    }

    @Test
    fun `Should keep the lines in the order of the song`() {
        val response: GetLyricsBySongIdResponse =
            mapper.readValue(
                readFixture("getLyricsBySongId.json"),
                GetLyricsBySongIdResponse::class.java
            )

        val lines = response.lyricsList.structuredLyrics.first().lines
        val times = lines.mapNotNull { it.startMs }

        assertEquals("the times must not run backwards", times.sorted(), times)
        assertFalse("the song must have more than one line", times.size < 2)
    }
}
