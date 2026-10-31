package org.moire.ultrasonic.api.subsonic

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moire.ultrasonic.api.subsonic.response.GetPlaylistResponse
import org.moire.ultrasonic.api.subsonic.response.GetPlaylistsResponse

/**
 * Regression test driven by real payloads captured from a Navidrome server (0.64.2).
 *
 * Navidrome sends a few fields the Subsonic spec does not require (readonly, changed,
 * coverArt on playlists and many OpenSubsonic fields on the entries) and it uses nanosecond
 * precision timestamps. This test makes sure these payloads still deserialize, because a
 * failed parse silently turns the playlist screen into an empty list.
 */
class NavidromePlaylistParsingTest {

    private val mapper: ObjectMapper = SubsonicAPIClient.jacksonMapper

    private fun readFixture(name: String): String =
        javaClass.getResourceAsStream("/navidrome/$name")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `Should parse the playlist list of Navidrome`() {
        val response: GetPlaylistsResponse =
            mapper.readValue(readFixture("getPlaylists.json"), GetPlaylistsResponse::class.java)

        assertTrue("playlists must not be empty", response.playlists.isNotEmpty())

        val first = response.playlists.first()
        assertEquals("3YKTipFCYyzP98heEamNvZ", first.id)
        assertEquals("2026-10-21", first.name)
        assertTrue("songCount must be parsed", first.songCount > 0)
        assertNotNull("created must be parsed", first.created)
    }

    @Test
    fun `Should parse the playlist entries of Navidrome`() {
        val response: GetPlaylistResponse =
            mapper.readValue(readFixture("getPlaylist.json"), GetPlaylistResponse::class.java)

        val playlist = response.playlist
        assertEquals("2026-10-21", playlist.name)
        assertEquals(2, playlist.entriesList.size)

        val track = playlist.entriesList.first()
        assertTrue("title must be parsed", track.title.isNotBlank())
        assertTrue("duration must be parsed", track.duration > 0)
        assertTrue("suffix must be parsed", track.suffix.isNotBlank())
        // Entries must not look like directories, otherwise the player recurses into them
        assertFalse(track.isDir)
        assertNotNull("the entry timestamp is an OpenSubsonic nanos timestamp", track.created)
    }

    @Test
    fun `Should parse the playlist timestamp`() {
        val response: GetPlaylistsResponse =
            mapper.readValue(readFixture("getPlaylists.json"), GetPlaylistsResponse::class.java)

        val changed = response.playlists.first().changed
        assertNotNull("changed must be parsed", changed)
        assertTrue(changed!!.timeInMillis > 0L)
    }
}
