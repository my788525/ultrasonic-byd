package org.moire.ultrasonic.api.subsonic.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One line of a structured lyric.
 *
 * The start is the moment the line has to be shown, in milliseconds. It is null for lines
 * that carry no timing, for example the credits at the end of a song.
 */
data class LyricsLine(
    @JsonProperty("start") val startMs: Long? = null,
    @JsonProperty("value") val text: String = ""
)

/**
 * Lyrics with a time axis.
 *
 * The plain Subsonic endpoint flattens the lyrics into one block of text and drops all the
 * timing, so the app cannot scroll along with the song when it only asks for that one. This
 * is the OpenSubsonic shape, which keeps the timings.
 */
data class StructuredLyric(
    @JsonProperty("displayArtist") val displayArtist: String = "",
    @JsonProperty("displayTitle") val displayTitle: String = "",
    @JsonProperty("kind") val kind: String = "",
    @JsonProperty("lang") val lang: String = "",
    @JsonProperty("line") val lines: List<LyricsLine> = emptyList(),
    @JsonProperty("offset") val offsetMs: Long? = null,
    @JsonProperty("synced") val synced: Boolean = false
)

/** The list of lyrics that the server has for one song. */
data class LyricsList(
    @JsonProperty("structuredLyrics") val structuredLyrics: List<StructuredLyric> = emptyList()
)
