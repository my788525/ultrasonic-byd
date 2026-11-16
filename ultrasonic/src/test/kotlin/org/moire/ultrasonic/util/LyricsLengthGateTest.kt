@file:Suppress("IllegalIdentifier")

package org.moire.ultrasonic.util

import org.junit.Test

/**
 * The length of a lyric record and the length of the song have to agree.
 *
 * A song whose lyrics are fetched for the first time comes from the online service, and that
 * service may only have another recording of the same song: a longer remix, a live take, a
 * compilation. Its time axis does not belong to the song that is playing, so the highlight
 * runs ahead of the music and the lyrics do not match.
 *
 * The lengths below are the ones lrclib.net really offers for "Hello" by Adele, which is
 * 232 s long on the album "21".
 */
class LyricsLengthGateTest {

    private val songSeconds = 232.0

    @Test
    fun `a record of another recording is not used`() {
        val offered = listOf(253.0, 293.0, 296.0, 366.0)

        val accepted = offered.filter { LyricsLengthGate.fits(it, songSeconds) }

        // Not one of them belongs to this recording, so none of them may be used.
        assert(accepted.isEmpty()) {
            "records of another recording were accepted: $accepted"
        }
    }

    @Test
    fun `a record of the same recording is used`() {
        // The same recording: the two lengths agree.
        assert(LyricsLengthGate.fits(232.0, songSeconds))
        // A small difference is normal, the two lengths come from different places.
        assert(LyricsLengthGate.fits(233.0, songSeconds))
        assert(LyricsLengthGate.fits(230.5, songSeconds))
        // Another recording is not.
        assert(!LyricsLengthGate.fits(296.0, songSeconds))
        assert(!LyricsLengthGate.fits(253.0, songSeconds))
    }

    @Test
    fun `an unknown length is accepted`() {
        // Without a length there is nothing to compare, and lyrics are better than none.
        assert(LyricsLengthGate.fits(0.0, songSeconds))
        assert(LyricsLengthGate.fits(296.0, 0.0))
        assert(LyricsLengthGate.fits(0.0, 0.0))
    }
}
