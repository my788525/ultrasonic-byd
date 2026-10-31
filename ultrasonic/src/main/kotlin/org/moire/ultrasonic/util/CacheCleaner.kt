/*
 * CacheCleaner.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.res.AssetFileDescriptor
import android.system.Os
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.inject
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.util.FileUtil.getAlbumArtFile
import org.moire.ultrasonic.util.FileUtil.getCompleteFile
import org.moire.ultrasonic.util.FileUtil.getPartialFile
import org.moire.ultrasonic.util.FileUtil.getPinnedFile
import org.moire.ultrasonic.util.FileUtil.getPlaylistDirectory
import org.moire.ultrasonic.util.FileUtil.getPlaylistFile
import org.moire.ultrasonic.util.FileUtil.listFiles
import org.moire.ultrasonic.util.FileUtil.musicDirectory
import org.moire.ultrasonic.util.Settings.cacheSizeMB
import org.moire.ultrasonic.util.Storage.delete
import org.moire.ultrasonic.util.Util.formatBytes
import timber.log.Timber

/**
 * Responsible for cleaning up files from the offline download cache on the filesystem.
 */
class CacheCleaner : CoroutineScope by CoroutineScope(Dispatchers.IO), KoinComponent {

    private val activeServerProvider by inject<ActiveServerProvider>()

    private fun exceptionHandler(tag: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, exception ->
            Timber.w(exception, "Exception in CacheCleaner.$tag")
        }
    }

    // Cache cleaning shouldn't run concurrently, as it is started after every completed download
    // TODO serializing and throttling these is an ideal task for Rx
    fun clean() {
        if (cleaning) return
        synchronized(lock) {
            if (cleaning) return
            cleaning = true
            launch(exceptionHandler("clean")) {
                backgroundCleanup()
                backgroundCleanWholeDatabase()
            }
        }
    }

    fun cleanSpace() {
        if (spaceCleaning) return
        synchronized(lock) {
            if (spaceCleaning) return
            spaceCleaning = true
            launch(exceptionHandler("cleanSpace")) {
                backgroundSpaceCleanup()
                // The files above are gone, but the offline database still holds a row for
                // each of them. Without this step the database grows forever, because
                // only clean() ever ran the full database sweep.
                backgroundCleanWholeDatabase()
            }
        }
    }

    fun cleanPlaylists(playlists: List<Playlist>) {
        if (playlistCleaning) return
        synchronized(lock) {
            if (playlistCleaning) return
            playlistCleaning = true
            launch(exceptionHandler("cleanPlaylists")) {
                backgroundPlaylistsCleanup(playlists)
            }
        }
    }

    private fun backgroundCleanWholeDatabase() {
        val offlineDB = activeServerProvider.offlineMetaDatabase

        Timber.i("Starting Database cleanup")

        // Get all tracks in the db
        val tracks = offlineDB.trackDao().get().toMutableSet()

        // Check all tracks if they have files
        val orphanedTracks = tracks.filter {
            !Storage.isPathExists(it.getPinnedFile()) && !Storage.isPathExists(it.getCompleteFile())
        }

        // Delete orphaned tracks
        orphanedTracks.forEach {
            offlineDB.trackDao().delete(it)
        }

        // Remove deleted tracks from our list
        tracks -= orphanedTracks.toSet()

        // Check for orphaned Albums
        val usedAlbumIds = tracks.mapNotNull { it.albumId }.toSet()
        val albums = offlineDB.albumDao().get().toMutableSet()
        val orphanedAlbums = albums.filterNot {
            usedAlbumIds.contains(it.id)
        }.toSet()

        // Delete orphaned Albums
        orphanedAlbums.forEach {
            offlineDB.albumDao().delete(it)
        }

        albums -= orphanedAlbums

        // Check for orphaned Artists
        val usedArtistsIds = tracks.mapNotNull { it.artistId } + albums.mapNotNull { it.artistId }
        val artists = offlineDB.artistDao().get().toSet()
        val orphanedArtists = artists.filterNot {
            usedArtistsIds.contains(it.id)
        }

        // Delete orphaned Artists
        orphanedArtists.forEach {
            offlineDB.artistDao().delete(it)
        }

        Timber.i("Database cleanup done")
    }

    fun cleanDatabaseSelective(trackToRemove: Track) {
        launch(exceptionHandler("cleanDatabase")) {
            backgroundDatabaseSelective(trackToRemove)
        }
    }

    /**
     * A snapshot of how much space the music directory uses, broken up so that the UI can
     * promise the user only what the cleaner is actually allowed to remove.
     *
     * The numbers come from the same single pass over the files that the cleaner itself
     * walks, so the "free X MB" button and the real deletion decision can never disagree.
     * Reading the file system is the slow part, which is why this runs on its own and
     * reports back when the reading is done.
     */
    fun usage(onResult: suspend (CacheUsage) -> Unit) {
        launch(exceptionHandler("usage")) {
            onResult(readUsage())
        }
    }

    private fun readUsage(): CacheUsage {
        val scanned = mutableListOf<CacheUsage.ScannedFile>()
        val stats = FsStats()
        collectUsage(musicDirectory, scanned, stats)

        val usage = CacheUsage.summarize(scanned)
            .copy(
                bytesTotalFs = stats.bytesTotal,
                bytesAvailableFs = stats.bytesAvailable,
                bytesCacheLimit = cacheSizeMB * 1024L * 1024L
            )
        return usage
    }

    /**
     * Walks the tree of the music directory and records every file.
     *
     * The byte counts of the file system are read from the first file that exposes a
     * descriptor. When nothing does (an empty or unreadable directory) the fields simply
     * stay at zero and the caller treats the file system size as unknown.
     */
    private fun collectUsage(
        directory: AbstractFile,
        out: MutableList<CacheUsage.ScannedFile>,
        stats: FsStats
    ) {
        if (!directory.isDirectory) return
        if (!stats.found) {
            documentFileDescriptorOrNull(directory)?.let { descriptor ->
                val stat = runCatching { Os.fstatvfs(descriptor.fileDescriptor) }.getOrNull()
                if (stat != null) {
                    stats.bytesTotal = stat.f_blocks * stat.f_bsize
                    stats.bytesAvailable = stat.f_bfree * stat.f_bsize
                    stats.found = true
                }
                runCatching { descriptor.close() }
            }
        }
        for (child in listFiles(directory)) {
            if (child.isDirectory) {
                collectUsage(child, out, stats)
            } else {
                out += CacheUsage.ScannedFile(child.name, child.length)
            }
        }
    }

    /**
     * The file system numbers that one file descriptor can answer.
     */
    private class FsStats {
        var found = false
        var bytesTotal = 0L
        var bytesAvailable = 0L
    }

    private fun backgroundDatabaseSelective(track: Track) {
        val offlineDB = activeServerProvider.offlineMetaDatabase

        // Delete track
        offlineDB.trackDao().delete(track)

        // Setup up artistList
        val artistsToCheck = mutableListOf(track.artistId)

        // Check if we have other tracks for the album
        var albumToDelete: String? = null
        if (track.albumId != null) {
            val tracks = offlineDB.trackDao().byAlbum(track.albumId!!)
            if (tracks.isEmpty()) albumToDelete = track.albumId!!
        }

        // Delete empty album
        if (albumToDelete != null) {
            val album = offlineDB.albumDao().get(albumToDelete)
            if (album != null) {
                artistsToCheck.add(album.artistId)
                offlineDB.albumDao().delete(album)
            }
        }

        // Check if we have an empty artist now..
        artistsToCheck.filterNotNull().forEach {
            val tracks = offlineDB.trackDao().byArtist(it)
            if (tracks.isEmpty()) offlineDB.artistDao().delete(it)
        }
    }

    private fun backgroundCleanup() {
        try {
            val files: MutableList<AbstractFile> = ArrayList()
            val dirs: MutableList<AbstractFile> = ArrayList()

            findCandidatesForDeletion(musicDirectory, files, dirs)
            sortByAscendingModificationTime(files)
            val filesToNotDelete = findFilesToNotDelete()

            deleteFiles(files, filesToNotDelete, getMinimumDelete(files), true)
            deleteEmptyDirs(dirs, filesToNotDelete)
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in cache cleaning.")
        } finally {
            cleaning = false
        }
    }

    private fun backgroundSpaceCleanup() {
        try {
            val files: MutableList<AbstractFile> = ArrayList()
            val dirs: MutableList<AbstractFile> = ArrayList()

            findCandidatesForDeletion(musicDirectory, files, dirs)

            val bytesToDelete = getMinimumDelete(files)
            if (bytesToDelete > 0L) {
                sortByAscendingModificationTime(files)
                val filesToNotDelete = findFilesToNotDelete()
                deleteFiles(files, filesToNotDelete, bytesToDelete, false)
            }

            // The songs that the user downloaded on purpose are the offline library of the
            // car, so they are only given up when the file system itself runs out of space.
            // The cache size setting must not touch them: the user asked for these songs to
            // be available without a network, not to be thrown away when a cache is full.
            if (isFileSystemCriticallyFull()) {
                reclaimCompletedDownloads()
            }
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in cache cleaning.")
        } finally {
            spaceCleaning = false
        }
    }

    /**
     * Gives back the space of downloaded songs, oldest first. This is the last resort when
     * the file system is full and nothing else can be freed.
     */
    private fun reclaimCompletedDownloads() {
        val files: MutableList<AbstractFile> = ArrayList()
        val dirs: MutableList<AbstractFile> = ArrayList()

        findCompletedDownloads(musicDirectory, files, dirs)
        if (files.isEmpty()) return

        sortByAscendingModificationTime(files)
        val filesToNotDelete = findFilesToNotDelete()
        val bytesToDelete = getMinimumDelete(files)
        Timber.w(
            "File system is critically full: reclaiming %d downloaded songs",
            files.size
        )
        deleteFiles(files, filesToNotDelete, bytesToDelete, false)
    }

    /**
     * True when the file system has less free space than the reserved minimum.
     *
     * The check reads the free space from the music directory itself, not from the list
     * of half finished downloads. Tying it to that list would make the safety net that
     * reclaims the offline library silent exactly when the disk is full but no download
     * is in progress.
     */
    private fun isFileSystemCriticallyFull(): Boolean {
        return runCatching {
            val descriptor = documentFileDescriptorOrNull(musicDirectory)
                ?: return@runCatching false
            val stat = Os.fstatvfs(descriptor.fileDescriptor)
            descriptor.close()

            val bytesTotalFs: Long = stat.f_blocks * stat.f_bsize
            val bytesAvailableFs: Long = stat.f_bfree * stat.f_bsize
            bytesTotalFs > 0L && bytesAvailableFs < bytesTotalFs - MIN_FREE_SPACE
        }.getOrElse {
            Timber.e(it, "Could not determine the free space of the file system")
            false
        }
    }
    private fun backgroundPlaylistsCleanup(vararg params: List<Playlist>) {
        try {
            val activeServerProvider = inject<ActiveServerProvider>(
                ActiveServerProvider::class.java
            )

            val server = activeServerProvider.value.getActiveServer().name
            val playlistFiles = listFiles(getPlaylistDirectory(server))
            val playlists = params[0]

            for ((_, name) in playlists) {
                playlistFiles.remove(getPlaylistFile(server, name))
            }

            for (playlist in playlistFiles) {
                playlist.delete()
            }
        } catch (all: RuntimeException) {
            Timber.e(all, "Error in playlist cache cleaning.")
        } finally {
            playlistCleaning = false
        }
    }

    private fun findFilesToNotDelete(): Set<String> {
        val filesToNotDelete: MutableSet<String> = HashSet(5)

        // We just take the last published playlist from RX.
        // blockingLast() waits forever when nothing was published yet, which happens when the
        // user only downloads music and never starts the player. In that case there is no
        // playlist to protect, so the empty list is used.
        val playlist = try {
            RxBus.playlistObservable
                .timeout(PLAYLIST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .blockingLast()
        } catch (e: RuntimeException) {
            Timber.i("No playlist published yet, protecting no queued songs")
            emptyList()
        }
        for (track in playlist) {
            filesToNotDelete.add(track.getPartialFile())
            filesToNotDelete.add(track.getCompleteFile())
            filesToNotDelete.add(track.getPinnedFile())
        }
        filesToNotDelete.add(musicDirectory.path)
        return filesToNotDelete
    }

    companion object {
        private val lock = Object()
        private var cleaning = false
        private var spaceCleaning = false
        private var playlistCleaning = false

        private const val MIN_FREE_SPACE = 500 * 1024L * 1024L

        /**
         * How long the cleanup waits for a playlist to be published before it gives up.
         */
        private const val PLAYLIST_TIMEOUT_MS = 2000L
        private fun deleteEmptyDirs(dirs: Iterable<AbstractFile>, doNotDelete: Collection<String>) {
            for (dir in dirs) {
                if (doNotDelete.contains(dir.path)) continue

                var children = dir.listFiles()
                // No songs left in the folder
                if (children.size == 1 && children[0].path == getAlbumArtFile(dir.path)) {
                    // Delete Artwork files
                    delete(children[0].path)
                    children = dir.listFiles()
                }

                // Delete empty directory
                if (children.isEmpty()) {
                    delete(dir.path)
                }
            }
        }

        private fun getMinimumDelete(files: List<AbstractFile>): Long {
            if (files.isEmpty()) return 0L

            val cacheSizeBytes = cacheSizeMB * 1024L * 1024L
            var bytesUsedBySubsonic = 0L

            for (file in files) {
                bytesUsedBySubsonic += file.length
            }

            // The file system numbers come from any file that exposes a descriptor; when
            // none does they stay at zero and the helper below simply stops constraining
            // on the file system instead of treating an unknown disk as "completely full".
            val fs = FsStats()
            for (file in files) {
                if (fs.found) break
                documentFileDescriptorOrNull(file)?.let { descriptor ->
                    val stat = runCatching { Os.fstatvfs(descriptor.fileDescriptor) }.getOrNull()
                    if (stat != null) {
                        fs.bytesTotal = stat.f_blocks * stat.f_bsize
                        fs.bytesAvailable = stat.f_bfree * stat.f_bsize
                        fs.found = true
                    }
                    runCatching { descriptor.close() }
                }
            }

            val bytesAvailableFs = fs.bytesAvailable
            val bytesTotalFs = fs.bytesTotal
            val bytesUsedFs = if (fs.found) bytesTotalFs - bytesAvailableFs else 0L

            val bytesToDelete = CacheUsage.minimumToDelete(
                bytesCacheUsed = bytesUsedBySubsonic,
                bytesCacheLimit = cacheSizeBytes,
                bytesUsedFs = bytesUsedFs,
                bytesTotalFs = bytesTotalFs
            )

            Timber.i(
                "File system       : %s of %s available",
                formatBytes(bytesAvailableFs),
                formatBytes(bytesTotalFs)
            )
            Timber.i("Cache limit       : %s", formatBytes(cacheSizeBytes))
            Timber.i("Cache size before : %s", formatBytes(bytesUsedBySubsonic))
            Timber.i("Minimum to delete : %s", formatBytes(bytesToDelete))

            return bytesToDelete
        }

        private fun isPartial(file: AbstractFile): Boolean {
            return CacheUsage.isPartial(file.name)
        }

        private fun isComplete(file: AbstractFile): Boolean {
            return CacheUsage.isComplete(file.name)
        }

        @Suppress("NestedBlockDepth")
        private fun deleteFiles(
            files: Collection<AbstractFile>,
            doNotDelete: Collection<String>,
            bytesToDelete: Long,
            deletePartials: Boolean
        ) {
            if (files.isEmpty()) {
                return
            }
            var bytesDeleted = 0L

            for (file in files) {
                if (!deletePartials && bytesDeleted > bytesToDelete) break
                if (bytesToDelete > bytesDeleted || deletePartials && isPartial(file)) {
                    if (!doNotDelete.contains(file.path) && file.name != Constants.ALBUM_ART_FILE) {
                        val size = file.length
                        if (delete(file.path)) {
                            bytesDeleted += size
                        }
                    }
                }
            }
            Timber.i("Deleted: %s", formatBytes(bytesDeleted))
        }

        private fun findCandidatesForDeletion(
            file: AbstractFile,
            files: MutableList<AbstractFile>,
            dirs: MutableList<AbstractFile>
        ) {
            if (file.isFile && isPartial(file)) {
                // Only half finished downloads are collected here. A song that the user
                // downloaded on purpose has the ".complete" suffix or keeps its original
                // name (pinned), and must never be deleted silently: on a car the user
                // downloads music exactly to have it available without a network.
                files.add(file)
            } else if (file.isDirectory) {
                // Depth-first
                for (child in listFiles(file)) {
                    findCandidatesForDeletion(child, files, dirs)
                }
                dirs.add(file)
            }
        }

        /**
         * Collects the songs that the user downloaded on purpose. They are only deleted when
         * the file system is critically full, because they are the offline library of the car.
         */
        private fun findCompletedDownloads(
            file: AbstractFile,
            files: MutableList<AbstractFile>,
            dirs: MutableList<AbstractFile>
        ) {
            if (file.isFile && isComplete(file)) {
                files.add(file)
            } else if (file.isDirectory) {
                for (child in listFiles(file)) {
                    findCompletedDownloads(child, files, dirs)
                }
                dirs.add(file)
            }
        }

        private fun sortByAscendingModificationTime(files: MutableList<AbstractFile>) {
            files.sortWith { a: AbstractFile, b: AbstractFile ->
                a.lastModified.compareTo(b.lastModified)
            }
        }
    }
}

/**
 * A descriptor for the file system, or null when the file does not expose one.
 *
 * Declared at file scope (not inside the class) so that both the class body and the
 * companion object can reach it: a companion object cannot see a private member function
 * of the enclosing class.
 */
private fun documentFileDescriptorOrNull(file: AbstractFile): AssetFileDescriptor? {
    return runCatching { file.getDocumentFileDescriptor("r") }.getOrNull()
}
