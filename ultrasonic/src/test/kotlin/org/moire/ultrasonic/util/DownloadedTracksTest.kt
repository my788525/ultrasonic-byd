/*
 * DownloadedTracksTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import android.content.res.AssetFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The songs that were downloaded must be found again on the device, so that they can be
 * listed and played without a network connection.
 */
class DownloadedTracksTest {

    private class FakeFile(
        override val name: String,
        override val path: String,
        override val parent: AbstractFile? = null,
        private val children: Array<AbstractFile> = emptyArray()
    ) : AbstractFile() {
        override val isDirectory: Boolean = children.isNotEmpty()
        override val isFile: Boolean = !isDirectory
        override val length: Long = 4096L
        override val lastModified: Long = 0L

        override fun delete(): Boolean = true
        override fun listFiles(): Array<AbstractFile> = children
        override fun getFileOutputStream(append: Boolean): OutputStream =
            throw UnsupportedOperationException()

        override fun getFileInputStream(): InputStream = throw UnsupportedOperationException()
        override fun getDocumentFileDescriptor(openMode: String): AssetFileDescriptor? = null
        override fun getOrCreateFileFromPath(path: String): AbstractFile = this
        override fun isPathExists(path: String): Boolean = false
        override fun getFromPath(path: String): AbstractFile? = null
        override fun createDirsOnPath(path: String) = Unit
        override fun rename(pathFrom: AbstractFile, pathTo: String) = Unit
    }

    @Test
    fun `a finished download is complete`() {
        assertEquals(
            DownloadedTracks.State.COMPLETE,
            DownloadedTracks.stateOf("01-Song.complete.mp3")
        )
    }

    @Test
    fun `an unfinished download is partial`() {
        assertEquals(
            DownloadedTracks.State.PARTIAL,
            DownloadedTracks.stateOf("01-Song.partial.mp3")
        )
    }

    @Test
    fun `a pinned song keeps its original name`() {
        assertEquals(
            DownloadedTracks.State.PINNED,
            DownloadedTracks.stateOf("01-Song.mp3")
        )
    }

    @Test
    fun `the album art is not a song`() {
        assertNull(DownloadedTracks.stateOf("cover.jpg"))
        assertNull(DownloadedTracks.stateOf("cover.jpeg"))
        assertNull(DownloadedTracks.stateOf("cover.jpeg-small"))
    }
}
