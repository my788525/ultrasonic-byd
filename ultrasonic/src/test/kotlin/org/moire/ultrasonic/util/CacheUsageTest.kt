/*
 * CacheUsageTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The space accounting has to keep three numbers apart (music directory, half finished
 * downloads, and the offline library) and it must never treat an unreadable file system as
 * "completely full". These tests lock that behaviour so a refactor cannot silently start
 * deleting hundreds of megabytes of the user's library.
 */
class CacheUsageTest {

    private companion object {
        val MB = 1024L * 1024L
        val GB = 1024L * 1024L * 1024L
        // Mirrors CacheUsage.MIN_FREE_SPACE (500 MB) so the tests reason in the same units.
        val MIN_FREE = 500L * MB
    }

    @Test
    fun `an unknown file system does not constrain the deletion`() {
        // bytesTotalFs <= 0 means the size could not be read. The cleaner must not invent a
        // "full disk" and must not delete anything beyond what the cache limit asks for.
        val toDelete = CacheUsage.minimumToDelete(
            bytesCacheUsed = 500L,
            bytesCacheLimit = 1000L,
            bytesUsedFs = 1000L,
            bytesTotalFs = 0L
        )
        assertEquals(0L, toDelete)
    }

    @Test
    fun `the cache limit alone decides when the file system size is unknown`() {
        val toDelete = CacheUsage.minimumToDelete(
            bytesCacheUsed = 2000L,
            bytesCacheLimit = 1000L,
            bytesUsedFs = 1_000_000L,
            bytesTotalFs = 0L
        )
        // Only the 1000 bytes over the cache limit are due, not the whole "used" amount.
        assertEquals(1000L, toDelete)
    }

    @Test
    fun `the stricter of the two limits wins`() {
        // Cache wants 1000 bytes; a nearly full 10 GB file system wants 200 MB. The larger
        // one (the file system) must win.
        val toDelete = CacheUsage.minimumToDelete(
            bytesCacheUsed = 2000L,
            bytesCacheLimit = 1000L,
            bytesUsedFs = 10 * GB - 300 * MB,
            bytesTotalFs = 10 * GB
        )
        // minFsAvailability = 10 GB - 500 MB, used = 10 GB - 300 MB => 200 MB to free.
        assertEquals(200 * MB, toDelete)
    }

    @Test
    fun `a critically full file system forces a reclaim beyond the cache limit`() {
        // Cache is far from full (wants 0), but the file system has only 400 MB free which is
        // below the 500 MB reserve, so the cleaner must still free 100 MB.
        val toDelete = CacheUsage.minimumToDelete(
            bytesCacheUsed = 100L,
            bytesCacheLimit = 1000L,
            bytesUsedFs = 10 * GB - 400 * MB,
            bytesTotalFs = 10 * GB
        )
        assertEquals(100 * MB, toDelete)
    }

    @Test
    fun `summarize splits the music directory, partials and the offline library`() {
        val files = listOf(
            CacheUsage.ScannedFile("01-Song.partial.mp3", 100L),
            CacheUsage.ScannedFile("02-Song.complete.mp3", 200L),
            CacheUsage.ScannedFile("03-Pinned.mp3", 300L),
            CacheUsage.ScannedFile("cover.jpg", 50L)
        )

        val usage = CacheUsage.summarize(files)

        assertEquals(650L, usage.bytesMusicDir)
        assertEquals(100L, usage.bytesPartial)
        // Complete + pinned (both belong to the user's library); cover.jpg is ignored.
        assertEquals(500L, usage.bytesLibrary)
        assertEquals(100L, usage.bytesReclaimable)
    }

    @Test
    fun `reclaimable space is only the half finished downloads`() {
        val usage = CacheUsage(
            bytesMusicDir = 1000L,
            bytesPartial = 250L,
            bytesLibrary = 750L
        )
        assertEquals(250L, usage.bytesReclaimable)
    }

    @Test
    fun `over-limit space ignores the library and counts only the partials against the cache`() {
        // With a 100 MB cache limit and 250 MB of half finished downloads, 150 MB is over.
        val usage = CacheUsage(
            bytesMusicDir = 1000 * MB,
            bytesPartial = 250 * MB,
            bytesLibrary = 750 * MB,
            bytesCacheLimit = 100 * MB
        )
        assertEquals(150 * MB, usage.bytesOverLimit)
    }

    @Test
    fun `partial and complete classification matches the file names`() {
        assertTrue(CacheUsage.isPartial("song.partial.mp3"))
        assertTrue(CacheUsage.isPartial("song.partial.001.mp3"))
        assertFalse(CacheUsage.isPartial("song.complete.mp3"))
        assertFalse(CacheUsage.isPartial("song.mp3"))

        assertTrue(CacheUsage.isComplete("song.complete.mp3"))
        assertTrue(CacheUsage.isComplete("song.complete.001.mp3"))
        assertFalse(CacheUsage.isComplete("song.partial.mp3"))
        assertFalse(CacheUsage.isComplete("song.mp3"))
    }
}
