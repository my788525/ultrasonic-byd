/*
 * LyricsLengthGate.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import kotlin.math.abs

/**
 * Decides whether a lyric record belongs to the song that is playing.
 *
 * The lyrics of a song can come from two places. The server hands out the lyrics of the very
 * file that is playing, so they always fit. The online service (lrclib.net) collects the
 * lyrics of many recordings of a song: a studio take, a live take, a remix, a compilation, a
 * cover. Only one of them is the recording this car is playing.
 *
 * The record carries the length of the recording it was made for. When that length does not
 * match the length of the song, the time axis belongs to another recording: the highlight
 * runs ahead of the music and the lyrics do not match what is sung. Such a record is not
 * used; the server lyrics are used instead, they belong to the song by definition.
 *
 * The two lengths come from different places - the file on the server and the recording the
 * lyrics were made for - so a small difference is normal and only a larger one is a sign of
 * another recording.
 */
object LyricsLengthGate {

    /**
     * How far the length of a lyric record may be from the length of the song, in seconds.
     *
     * Five seconds covers the difference between the length the server reports and the real
     * length of the file, including the rounding both of them do.
     */
    const val MAX_LENGTH_DIFFERENCE_SEC = 5.0

    /**
     * Whether a lyric record of [recordSeconds] may be used for a song of [songSeconds].
     *
     * A length that is not known on either side is accepted: without a length there is
     * nothing to compare, and the lyrics are still better than none.
     */
    fun fits(recordSeconds: Double, songSeconds: Double): Boolean {
        if (songSeconds <= 0.0 || recordSeconds <= 0.0) return true
        return abs(recordSeconds - songSeconds) <= MAX_LENGTH_DIFFERENCE_SEC
    }
}
