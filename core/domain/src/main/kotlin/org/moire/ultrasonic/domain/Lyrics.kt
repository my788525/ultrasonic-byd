package org.moire.ultrasonic.domain

/**
 * One line of lyrics together with the moment it has to be shown.
 */
data class TimedLyricLine(
    val startMs: Long,
    val text: String
)

/**
 * Song lyrics.
 *
 * The plain Subsonic endpoint only returns the text of the song, without any timing. The
 * OpenSubsonic endpoint returns the lyrics line by line with the moment each line starts, so
 * the app can highlight the line that is playing and scroll along with the song.
 */
data class Lyrics(
    val artist: String? = null,
    val title: String? = null,
    val text: String? = null,
    /** True when the server delivered a time axis. */
    val synced: Boolean = false,
    /** The lines with their timings, empty when the server has no time axis. */
    val timedLines: List<TimedLyricLine> = emptyList()
)
