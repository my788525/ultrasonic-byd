/*
 * UtilTimelineTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The preload of the next songs depends on this function.
 *
 * A wrong result here does not only preload the wrong songs, it also crashes the app: the
 * timeline is read on the main thread while the song is playing, and the playlist can
 * change at any moment.
 */
class UtilTimelineTest {

    /**
     * A timeline that behaves like a plain list of songs.
     *
     * Media3 has no public builder for this in the version that is used here, so the
     * windows are filled in by hand.
     */
    private fun timelineOf(vararg titles: String): Timeline {
        val items = titles.mapIndexed { index, title ->
            MediaItem.Builder()
                .setMediaId("id-$index")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }

        val timeline = mock<Timeline>()
        whenever(timeline.windowCount).thenReturn(items.size)
        whenever(timeline.getFirstWindowIndex(any())).thenReturn(0)
        whenever(timeline.getLastWindowIndex(any())).thenReturn(items.size - 1)

        whenever(timeline.getWindow(any(), any(), any())).doAnswer { invocation ->
            val index = invocation.getArgument<Int>(0)
            val window = invocation.getArgument<Timeline.Window>(1)
            fillWindow(window, items[index], index)
            window
        }

        // The short form is final, so the mock maker replaces it as well and it needs its
        // own stubbing.
        whenever(timeline.getWindow(any(), any())).doAnswer { invocation ->
            val index = invocation.getArgument<Int>(0)
            val window = invocation.getArgument<Timeline.Window>(1)
            fillWindow(window, items[index], index)
            window
        }

        whenever(timeline.getNextWindowIndex(any(), any(), any())).doAnswer { invocation ->
            val index = invocation.getArgument<Int>(0)
            if (index + 1 < items.size) index + 1 else C.INDEX_UNSET
        }

        return timeline
    }

    /** Fills a window with the values that the playlist code reads from it. */
    private fun fillWindow(window: Timeline.Window, item: MediaItem, index: Int) {
        window.set(
            item,
            item,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            false,
            false,
            MediaItem.LiveConfiguration.UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            index,
            index,
            C.TIME_UNSET
        )
    }

    @Test
    fun `collects the requested number of songs from the given index`() {
        val timeline = timelineOf("a", "b", "c", "d")

        val items = Util.getPlayListFromTimeline(timeline, false, 1, 2)

        assertEquals(2, items.size)
        assertEquals("b", items[0].mediaMetadata.title)
        assertEquals("c", items[1].mediaMetadata.title)
    }

    @Test
    fun `stops at the end of the playlist`() {
        val timeline = timelineOf("a", "b")

        val items = Util.getPlayListFromTimeline(timeline, false, 1, 10)

        assertEquals(1, items.size)
        assertEquals("b", items[0].mediaMetadata.title)
    }

    @Test
    fun `an empty timeline gives no songs`() {
        assertTrue(Util.getPlayListFromTimeline(null, false, 0, 3).isEmpty())
    }

    @Test
    fun `an index outside of the timeline gives no songs`() {
        val timeline = timelineOf("a", "b")

        // The playlist can shrink while the song is playing, so the index that was read a
        // moment ago can point past the end. That must not crash the app.
        assertTrue(Util.getPlayListFromTimeline(timeline, false, 99, 3).isEmpty())
    }

    @Test
    fun `a negative index gives no songs`() {
        val timeline = timelineOf("a", "b")

        assertTrue(Util.getPlayListFromTimeline(timeline, false, -5, 3).isEmpty())
    }
}
