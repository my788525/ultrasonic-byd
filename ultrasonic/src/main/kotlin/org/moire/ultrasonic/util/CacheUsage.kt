/*
 * CacheUsage.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

/**
 * How much space the music on the device takes, and how much of it may be thrown away.
 *
 * The numbers have to be kept apart, because they answer different questions:
 *
 *  - [bytesMusicDir] is everything that sits in the music directory. This is what the user
 *    sees as "my music takes 4 GB".
 *  - [bytesPartial] are the downloads that did not finish. These are the only files that the
 *    cleaner is allowed to remove on its own.
 *  - [bytesLibrary] are the songs the user downloaded on purpose. On a car these are the
 *    offline library, so they are never thrown away without the user saying so.
 *
 * Mixing those up is how a cache screen ends up promising to free 4 GB and then freeing
 * 12 MB, so the report keeps them in separate fields instead of one "used" number.
 */
data class CacheUsage(
    /** The bytes used by all files in the music directory. */
    val bytesMusicDir: Long = 0L,
    /** The bytes used by downloads that did not finish (".partial"). */
    val bytesPartial: Long = 0L,
    /** The bytes used by the songs the user downloaded on purpose. */
    val bytesLibrary: Long = 0L,
    /** The size of the whole file system. */
    val bytesTotalFs: Long = 0L,
    /** The space that is still free on the file system. */
    val bytesAvailableFs: Long = 0L,
    /** The maximum size of the cache, as configured by the user. */
    val bytesCacheLimit: Long = 0L
) {
    /**
     * The bytes that can be freed without touching the offline library: only the half
     * finished downloads.
     */
    val bytesReclaimable: Long
        get() = bytesPartial

    /**
     * The bytes that are used more than the cache limit allows. Only the half finished
     * downloads count here, because the library is never cleaned automatically.
     */
    val bytesOverLimit: Long
        get() = (bytesPartial - bytesCacheLimit).coerceAtLeast(0L)

    /** True when the file system has less free space than the reserved minimum. */
    val isFileSystemTight: Boolean
        get() = bytesTotalFs > 0L && bytesAvailableFs < bytesTotalFs - MIN_FREE_SPACE

    /**
     * A file in the music directory, reduced to what the accounting needs.
     */
    data class ScannedFile(val name: String, val size: Long)

    companion object {
        /** The space that is kept free on the file system, whatever the cache size is. */
        const val MIN_FREE_SPACE = 500 * 1024L * 1024L

        /**
         * The number of bytes that have to be deleted to get back within the limits.
         *
         * Two limits apply and the stricter one wins: the cache may not grow beyond the
         * size the user configured, and the file system may not fill up completely.
         *
         * @param bytesCacheUsed the size of the files that count against the cache limit
         * @param bytesCacheLimit the cache size the user configured
         * @param bytesUsedFs the space that is used on the file system
         * @param bytesTotalFs the size of the file system, or 0 when it is unknown
         */
        fun minimumToDelete(
            bytesCacheUsed: Long,
            bytesCacheLimit: Long,
            bytesUsedFs: Long,
            bytesTotalFs: Long
        ): Long {
            val bytesToDeleteCacheLimit = (bytesCacheUsed - bytesCacheLimit).coerceAtLeast(0L)
            // A file system of size 0 means that the size could not be read. Treating that
            // as "completely full" would make the cleaner delete hundreds of megabytes
            // for no reason, so an unknown file system simply does not constrain anything.
            val bytesToDeleteFsLimit = if (bytesTotalFs <= 0L) {
                0L
            } else {
                val minFsAvailability = bytesTotalFs - MIN_FREE_SPACE
                (bytesUsedFs - minFsAvailability).coerceAtLeast(0L)
            }
            return bytesToDeleteCacheLimit.coerceAtLeast(bytesToDeleteFsLimit)
        }

        /**
         * Sums up the files of the music directory.
         *
         * The three sums are deliberately different: the music directory also holds the
         * album art, which belongs to neither the library nor the unfinished downloads.
         */
        fun summarize(files: List<ScannedFile>): CacheUsage {
            var musicDir = 0L
            var partial = 0L
            var library = 0L

            for (file in files) {
                musicDir += file.size
                when {
                    isPartial(file.name) -> partial += file.size
                    isComplete(file.name) -> library += file.size
                    FileUtil.isMusicFile(file.name) -> library += file.size
                }
            }

            return CacheUsage(
                bytesMusicDir = musicDir,
                bytesPartial = partial,
                bytesLibrary = library
            )
        }

        /** True for the files of a download that did not finish. */
        fun isPartial(fileName: String): Boolean {
            return fileName.endsWith(".partial") || fileName.contains(".partial.")
        }

        /** True for the files of a download that finished. */
        fun isComplete(fileName: String): Boolean {
            return fileName.endsWith(".complete") || fileName.contains(".complete.")
        }
    }
}
