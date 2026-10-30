/*
 * CacheCleanerTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import android.content.res.AssetFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache cleaner must never delete a song that the user downloaded on purpose: on a car
 * the music is downloaded exactly to have it available without a network. Only half finished
 * downloads may be thrown away.
 */
class CacheCleanerTest {

    /**
     * A file in the music directory. The name is what the cleaner looks at, the path is what
     * it protects.
     */
    private class FakeFile(
        override val name: String,
        override val path: String = "/music/$name",
        override val length: Long = 1024L,
        override val lastModified: Long = 0L,
        private val children: Array<AbstractFile> = emptyArray()
    ) : AbstractFile() {
        override val isDirectory: Boolean = children.isNotEmpty()
        override val isFile: Boolean = !isDirectory
        override val parent: AbstractFile? = null

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

    private fun dir(name: String, vararg children: AbstractFile) =
        FakeFile(name, "/music/$name", 0L, 0L, arrayOf(*children))

    private fun file(name: String) = FakeFile(name)

    /**
     * Calls a private function of the companion object of CacheCleaner, so that the real
     * production logic decides and not a re-implementation of it.
     */
    private fun callCompanion(methodName: String, vararg args: Any?): List<String> {
        val method = CacheCleaner.Companion.javaClass
            .getDeclaredMethod(methodName, AbstractFile::class.java, List::class.java, List::class.java)
        method.isAccessible = true
        val files = mutableListOf<AbstractFile>()
        val dirs = mutableListOf<AbstractFile>()
        method.invoke(CacheCleaner.Companion, args[0], files, dirs)
        return files.map { it.name }
    }

    /**
     * The files that the cleaner is allowed to delete.
     */
    private fun deletionCandidates(root: AbstractFile): List<String> =
        callCompanion("findCandidatesForDeletion", root)

    /**
     * The songs that the user downloaded on purpose.
     */
    private fun completedDownloads(root: AbstractFile): List<String> =
        callCompanion("findCompletedDownloads", root)

    @Test
    fun `a downloaded song is not a deletion candidate`() {
        val root = dir(
            "Album",
            file("01-Song.complete.mp3"),
            file("02-Other.complete.mp3")
        )

        val candidates = deletionCandidates(root)

        assertFalse(
            "A downloaded song must not be deleted silently",
            candidates.contains("01-Song.complete.mp3")
        )
        assertFalse(candidates.contains("02-Other.complete.mp3"))
    }

    @Test
    fun `a half finished download is a deletion candidate`() {
        val root = dir(
            "Album",
            file("01-Song.partial.mp3"),
            file("02-Other.complete.mp3")
        )

        val candidates = deletionCandidates(root)

        assertTrue(candidates.contains("01-Song.partial.mp3"))
        assertFalse(candidates.contains("02-Other.complete.mp3"))
    }

    @Test
    fun `a pinned song keeps its original name and is never a candidate`() {
        // A pinned song is stored under its original name, without any suffix.
        val root = dir(
            "Album",
            file("01-Song.mp3"),
            file("02-Song.partial.mp3")
        )

        val candidates = deletionCandidates(root)

        assertFalse(candidates.contains("01-Song.mp3"))
        assertTrue(candidates.contains("02-Song.partial.mp3"))
    }

    @Test
    fun `downloaded songs are collected separately for the emergency reclaim`() {
        val root = dir(
            "Album",
            file("01-Song.complete.mp3"),
            file("02-Song.partial.mp3")
        )

        assertEquals(listOf("01-Song.complete.mp3"), completedDownloads(root))
    }

    @Test
    fun `nested albums are searched`() {
        val root = dir(
            "Artist",
            dir("Album", file("01-Song.complete.mp3"))
        )

        assertTrue(deletionCandidates(root).isEmpty())
        assertEquals(listOf("01-Song.complete.mp3"), completedDownloads(root))
    }
}
