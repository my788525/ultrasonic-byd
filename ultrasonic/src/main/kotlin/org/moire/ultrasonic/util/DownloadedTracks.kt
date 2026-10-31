/*
 * DownloadedTracks.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.util

import org.moire.ultrasonic.domain.Track

/**
 * Finds the songs that were downloaded to the device, so that they can be listed and played
 * without a network connection.
 *
 * The download service stores a song in three possible states:
 *
 *  - "Artist/Album/01-Title.mp3"             a pinned song, the user asked to keep it
 *  - "Artist/Album/01-Title.complete.mp3"    a downloaded song
 *  - "Artist/Album/01-Title.partial.mp3"     a download that did not finish
 *
 * The file name of a download follows the layout of the music library, so the title and the
 * album can be recovered from the path. The track id is the path of the file, which is also
 * what the player uses to find the file again: the media item URI carries the path as its
 * third segment.
 */
object DownloadedTracks {

    /**
     * The state of a file in the music directory.
     */
    enum class State {
        /** The download did not finish. */
        PARTIAL,

        /** The song was downloaded and is available offline. */
        COMPLETE,

        /** The song was pinned, so it keeps its original name. */
        PINNED
    }

    /**
     * A song that is stored on the device.
     */
    data class Downloaded(
        val track: Track,
        val state: State,
        val filePath: String
    )

    /**
     * Scans the music directory for the songs that are available offline.
     */
    fun scan(): List<Downloaded> {
        val result = mutableListOf<Downloaded>()
        collect(FileUtil.musicDirectory, result)
        return result.sortedWith(
            compareBy({ it.track.artist ?: "" }, { it.track.album ?: "" }, { it.track.title ?: "" })
        )
    }

    private fun collect(directory: AbstractFile, result: MutableList<Downloaded>) {
        if (!directory.isDirectory) return
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                collect(child, result)
            } else {
                val state = stateOf(child.name) ?: continue
                val track = toTrack(child, state)
                if (track != null) {
                    result.add(Downloaded(track, state, child.path))
                }
            }
        }
    }

    /**
     * Determines the state of a file from its name, or null when the file is not a song.
     */
    fun stateOf(fileName: String): State? {
        if (fileName.endsWith(".partial") || fileName.contains(".partial.")) {
            return State.PARTIAL
        }
        if (fileName.endsWith(".complete") || fileName.contains(".complete.")) {
            return State.COMPLETE
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
            fileName.endsWith(".png") || fileName.endsWith(".jpeg-small") ||
            fileName.endsWith("-small") || fileName.endsWith(".small")
        ) {
            return null
        }
        // A pinned song keeps its original name, but only inside the music directory.
        return State.PINNED
    }

    /**
     * Builds a track from a file in the music directory. The name of the file follows the
     * "<track number>-<title>.<suffix>" convention, and the parent directories hold the
     * artist and the album.
     */
    private fun toTrack(file: AbstractFile, state: State): Track? {
        val fileName = file.name
        val suffix = fileName.substringAfterLast('.', "")
        if (suffix.isEmpty()) return null

        val baseName = fileName.substringBeforeLast('.')
        // A downloaded file still carries the ".complete" or ".partial" marker.
        val cleanName = when (state) {
            State.COMPLETE -> baseName.removeSuffix(".complete")
            State.PARTIAL -> baseName.removeSuffix(".partial")
            State.PINNED -> baseName
        }
        if (cleanName.isEmpty()) return null

        val trackNumber = cleanName.substringBefore('-', "").toIntOrNull()
        val title = if (trackNumber != null) {
            cleanName.substringAfter('-', cleanName)
        } else {
            cleanName
        }

        val albumDirectory = file.parent
        val album = albumDirectory?.name
        val artist = albumDirectory?.parent?.name

        // The id is the path of the file: the player puts the path of a track into the
        // media item URI, and the cached data source looks the file up by that path.
        return Track(
            id = file.path,
            title = title,
            album = album,
            artist = artist,
            track = trackNumber,
            suffix = suffix,
            path = file.path,
            isDirectory = false,
            size = file.length
        )
    }
}
