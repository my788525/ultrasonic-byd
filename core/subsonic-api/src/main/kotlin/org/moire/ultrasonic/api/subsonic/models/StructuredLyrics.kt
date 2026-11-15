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
    @JsonProperty("value") val text: String = "",
    @JsonProperty("end") val endMs: Long? = null
)

/**
 * One timed word or syllable of a cue line (OpenSubsonic songLyrics v2, enhanced=true).
 *
 * `byteStart` / `byteEnd` are UTF-8 byte offsets into [LyricsCueLine.value]. They let the
 * client rebuild the exact displayed text, including the untimed text between two cues.
 * See https://opensubsonic.github.io/docs/responses/cueline/.
 */
data class LyricsCue(
    @JsonProperty("start") val startMs: Long = 0L,
    @JsonProperty("end") val endMs: Long? = null,
    @JsonProperty("value") val value: String = "",
    @JsonProperty("byteStart") val byteStart: Int? = null,
    @JsonProperty("byteEnd") val byteEnd: Int? = null,
    @JsonProperty("agentId") val agentId: String? = null
)

/**
 * Word level timing for one lyric line. [index] points at the matching [LyricsLine] in the
 * parent's `line` array. Several cue lines can share an index when the source distinguishes
 * lead and background vocals; the main layer is always first and is the one we render.
 */
data class LyricsCueLine(
    @JsonProperty("index") val index: Int? = null,
    @JsonProperty("start") val startMs: Long? = null,
    @JsonProperty("end") val endMs: Long? = null,
    @JsonProperty("value") val value: String = "",
    @JsonProperty("cue") val cue: List<LyricsCue> = emptyList(),
    @JsonProperty("agentId") val agentId: String? = null
)

/**
 * Lyrics with a time axis.
 *
 * The plain Subsonic endpoint flattens the lyrics into one block of text and drops all the
 * timing, so the app cannot scroll along with the song when it only asks for that one. This
 * is the OpenSubsonic shape, which keeps the timings. With `enhanced=true` it also carries
 * [cueLines] for karaoke style word highlighting and [kind] to separate translations.
 */
data class StructuredLyric(
    @JsonProperty("displayArtist") val displayArtist: String = "",
    @JsonProperty("displayTitle") val displayTitle: String = "",
    @JsonProperty("kind") val kind: String = "",
    @JsonProperty("lang") val lang: String = "",
    @JsonProperty("line") val lines: List<LyricsLine> = emptyList(),
    @JsonProperty("offset") val offsetMs: Long? = null,
    @JsonProperty("synced") val synced: Boolean = false,
    @JsonProperty("cueLine") val cueLines: List<LyricsCueLine> = emptyList()
)

/** The list of lyrics that the server has for one song. */
data class LyricsList(
    @JsonProperty("structuredLyrics") val structuredLyrics: List<StructuredLyric> = emptyList()
)
