// Converts Lyrics entity from [org.moire.ultrasonic.api.subsonic.SubsonicAPIClient]
// to app domain entities.
@file:JvmName("APILyricsConverter")
package org.moire.ultrasonic.domain

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
 * A song can have several lyrics: one plain text version for printing and one with the time
 * axis. The one with the timings is preferred, because that is the one that can be shown
 * along with the song.
 */
fun LyricsList.toDomainEntity(): Lyrics {
    val chosen = structuredLyrics.firstOrNull { it.synced }
        ?: structuredLyrics.firstOrNull { it.lines.any { line -> line.startMs != null } }
        ?: structuredLyrics.firstOrNull()
        ?: return Lyrics()

    return chosen.toDomainEntity()
}

fun StructuredLyric.toDomainEntity(): Lyrics {
    val timed = lines.mapNotNull { line ->
        line.startMs?.let { start -> TimedLyricLine(start, line.text) }
    }
    return Lyrics(
        artist = displayArtist.ifEmpty { null },
        title = displayTitle.ifEmpty { null },
        text = lines.joinToString("\n") { it.text },
        synced = synced,
        timedLines = timed.sortedBy { it.startMs }
    )
}
