/*
 * OfflineProbeService.kt
 * Debug only probe that verifies the offline playback path on a real device.
 *
 * It exercises the exact same code path as a real download:
 *   - it writes a file with the ".complete" suffix that DownloadService produces
 *   - it asks the real CachedDataSource whether that file is a cache hit
 *   - it asks the real CacheCleaner which files it protects from deletion
 *
 * The result decides whether the "offline mode" feature actually works on the car head unit:
 * a song that was downloaded must be found by the player and must survive the cache cleanup.
 */
package org.moire.ultrasonic.debug

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.StatsDataSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.data.CachedDataSource
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.util.AbstractFile
import org.moire.ultrasonic.util.CacheCleaner
import org.moire.ultrasonic.util.DownloadedTracks
import org.moire.ultrasonic.util.FileUtil
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Storage
import timber.log.Timber

@androidx.annotation.OptIn(UnstableApi::class)
class OfflineProbeService : Service(), KoinComponent {

    private val cacheCleaner: CacheCleaner by inject()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WRITE_DOWNLOADED_FILE -> writeDownloadedFile()
            ACTION_PROBE_CACHE_HIT -> probeCacheHit()
            ACTION_PROBE_CLEANER -> probeCleaner()
            ACTION_PROBE_CLEANUP_KEEPS_DOWNLOAD -> probeCleanupKeepsDownload()
            ACTION_SET_TINY_CACHE_LIMIT -> setTinyCacheLimit()
            ACTION_PROBE_DOWNLOADED_LIST -> probeDownloadedList()
        }
        return START_NOT_STICKY
    }

    /**
     * Creates a file exactly the way DownloadService does: the same directory layout and the
     * same ".complete.<ext>" suffix that the player looks for.
     */
    private fun writeDownloadedFile() {
        // The same directory the downloader writes into: Storage.mediaRoot, which is an
        // absolute path.
        val dir = File(FileUtil.defaultMusicDirectory, "ProbeArtist/ProbeAlbum")
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("OFFLINE-PROBE: could not create %s", dir)
            return
        }
        val file = File(dir, "01-Probe Song.complete.mp3")
        file.writeBytes(ByteArray(4096) { i -> (i % 128).toByte() })
        Timber.i(
            "OFFLINE-PROBE: wrote downloaded file %s (%d bytes)",
            file.absolutePath,
            file.length()
        )
    }

    /**
     * Builds the same three part URI that MediaItemConverter builds for a track and lets the
     * real CachedDataSource try to open it. A cache hit means the song would be played from
     * the local file instead of the network.
     */
    private fun probeCacheHit() {
        // MediaItemConverter puts FileUtil.getSongFile(track) into the third segment, which is
        // an absolute path below the music directory.
        val absolutePath = File(
            FileUtil.defaultMusicDirectory,
            "ProbeArtist/ProbeAlbum/01-Probe Song.mp3"
        ).absolutePath
        val uri = "probe-id|0|$absolutePath"
        val upstream: DataSource.Factory = DefaultDataSource.Factory(this)
        val cached = CachedDataSource.Factory(upstream).createDataSource()
        val stats = StatsDataSource(cached)

        try {
            val length = stats.open(DataSpec.Builder().setUri(Uri.parse(uri)).build())
            Timber.i(
                "OFFLINE-PROBE: cache hit=%s length=%d uri=%s",
                length >= 0,
                length,
                stats.uri
            )
        } catch (e: Exception) {
            // A miss is not an error: the data source then forwards to the network.
            Timber.i("OFFLINE-PROBE: cache miss (forwarding to network): %s", e.message)
        } finally {
            try {
                stats.close()
            } catch (ignored: Exception) {
                // Nothing to do.
            }
        }
    }

    /**
     * Reports whether CacheCleaner protects a downloaded file that is not in the current
     * playlist. If it does not, the cleanup silently deletes music the user downloaded on
     * purpose.
     */
    private fun probeCleaner() {
        scope.launch {
            // The cleaner takes the files it must not delete from the currently playing
            // playlist. Publish a playlist that does not contain the probe song, which is the
            // normal situation: the user downloaded a song, played something else, and the
            // download is not in the queue anymore.
            //
            // The subscription has to exist before the value is published: the subject is a
            // plain PublishSubject, so a value that is published before the first subscriber
            // is lost, and blockingLast() would then wait forever.
            val subscription = RxBus.playlistObservable.subscribe { }
            RxBus.playlistPublisher.onNext(emptyList())
            // Give the replay buffer a moment to pick the value up.
            delay(300)

            val absolutePath = File(
                FileUtil.defaultMusicDirectory,
                "ProbeArtist/ProbeAlbum/01-Probe Song.mp3"
            ).absolutePath
            val complete = File(FileUtil.getCompleteFile(absolutePath))
            Timber.i(
                "OFFLINE-PROBE: complete file exists=%s path=%s",
                complete.exists(),
                complete.absolutePath
            )

            // 1. Which files does the cleaner consider as deletion candidates at all?
            val candidates = readDeletionCandidates()
            val probeInCandidates = candidates.any {
                it.endsWith("01-Probe Song.complete.mp3")
            }
            Timber.i(
                "OFFLINE-PROBE: deletion candidates=%d, downloaded file is a candidate=%s",
                candidates.size,
                probeInCandidates
            )

            // 2. Which files does the cleaner promise never to delete?
            val protected = readProtectedFiles()
            val probeProtected = protected.any {
                it.endsWith("01-Probe Song.complete.mp3")
            }
            Timber.i(
                "OFFLINE-PROBE: cleaner protects %d files, downloaded file protected=%s",
                protected.size,
                probeProtected
            )
            if (probeInCandidates && !probeProtected) {
                Timber.w(
                    "OFFLINE-PROBE: a downloaded song that is not in the current playlist " +
                        "is a deletion candidate and is not protected -> it can be deleted"
                )
            }
            subscription.dispose()
        }
    }

    /**
     * Reports whether the downloaded songs are found again on the device, which is what the
     * downloads page shows.
     */
    private fun probeDownloadedList() {
        scope.launch {
            val downloaded = DownloadedTracks.scan()
            Timber.i(
                "OFFLINE-PROBE: found %d songs on the device",
                downloaded.size
            )
            downloaded.forEach {
                Timber.i(
                    "OFFLINE-PROBE: state=%s title=%s artist=%s album=%s",
                    it.state,
                    it.track.title,
                    it.track.artist,
                    it.track.album
                )
            }
            // The player has to find the file again by the id of the track, which is the
            // path of the file.
            val track = downloaded.firstOrNull()?.track
            if (track != null) {
                val upstream: DataSource.Factory = DefaultDataSource.Factory(this@OfflineProbeService)
                val cached = CachedDataSource.Factory(upstream).createDataSource()
                val stats = StatsDataSource(cached)
                val uri = "${track.id}|0|${track.id}"
                try {
                    val length = stats.open(DataSpec.Builder().setUri(Uri.parse(uri)).build())
                    Timber.i(
                        "OFFLINE-PROBE: the listed song can be played from the file, hit=%s length=%d",
                        length >= 0,
                        length
                    )
                } catch (e: Exception) {
                    Timber.i("OFFLINE-PROBE: the listed song was NOT found: %s", e.message)
                } finally {
                    try {
                        stats.close()
                    } catch (ignored: Exception) {
                        // Nothing to do.
                    }
                }
            }
        }
    }

    /**
     * Sets the cache limit to 0 MB, so that the next cleanup really has to free space.
     * Without this the default limit is unlimited and nothing is ever deleted.
     */
    private fun setTinyCacheLimit() {
        val key = getString(R.string.setting_key_cache_size)
        Settings.preferences.edit().putString(key, "0").apply()
        Timber.i("OFFLINE-PROBE: cache limit set to 0 MB (key=%s)", key)
    }

    /**
     * Runs the real space cleanup and reports whether the downloaded song survived it.
     * This is the end to end proof that the offline library is safe.
     */
    private fun probeCleanupKeepsDownload() {
        scope.launch {
            val subscription = RxBus.playlistObservable.subscribe { }
            RxBus.playlistPublisher.onNext(emptyList())
            delay(300)

            val absolutePath = File(
                FileUtil.defaultMusicDirectory,
                "ProbeArtist/ProbeAlbum/01-Probe Song.mp3"
            ).absolutePath
            val complete = File(FileUtil.getCompleteFile(absolutePath))
            if (!complete.exists()) {
                Timber.w("OFFLINE-PROBE: no downloaded file to test, write it first")
                subscription.dispose()
                return@launch
            }
            val sizeBefore = complete.length()

            // Run the real cleaner.
            cacheCleaner.cleanSpace()
            delay(3000)

            Timber.i(
                "OFFLINE-PROBE: after cleanSpace, downloaded file exists=%s size=%d",
                complete.exists(),
                complete.length()
            )
            Timber.i(
                "OFFLINE-PROBE: RESULT %s",
                if (complete.exists() && complete.length() == sizeBefore) {
                    "PASS - the downloaded song survived the cleanup"
                } else {
                    "FAIL - the downloaded song was deleted by the cleanup"
                }
            )
            subscription.dispose()
        }
    }

    /**
     * Calls the private findCandidatesForDeletion() of CacheCleaner through reflection, so
     * that the real production logic decides and not a re-implementation of it.
     *
     * The method lives in the companion object of CacheCleaner.
     */
    private fun readDeletionCandidates(): List<String> {
        return try {
            val companion = CacheCleaner.Companion
            val method = companion.javaClass.getDeclaredMethod(
                "findCandidatesForDeletion",
                AbstractFile::class.java,
                MutableList::class.java,
                MutableList::class.java
            )
            method.isAccessible = true
            val files = mutableListOf<AbstractFile>()
            val dirs = mutableListOf<AbstractFile>()
            method.invoke(
                companion,
                FileUtil.musicDirectory,
                files,
                dirs
            )
            files.map { it.path }
        } catch (e: Exception) {
            Timber.e(e, "OFFLINE-PROBE: could not read deletion candidates")
            emptyList()
        }
    }

    /**
     * Calls the private findFilesToNotDelete() of CacheCleaner through reflection, so that
     * the real production logic decides and not a re-implementation of it.
     *
     * The method is a private member of CacheCleaner itself, not of its companion object.
     */
    private fun readProtectedFiles(): Set<String> {
        return try {
            val method = CacheCleaner::class.java
                .getDeclaredMethod("findFilesToNotDelete")
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (method.invoke(cacheCleaner) as Set<String>)
        } catch (e: Exception) {
            Timber.e(e, "OFFLINE-PROBE: could not read protected files")
            emptySet()
        }
    }

    companion object {
        const val ACTION_WRITE_DOWNLOADED_FILE =
            "org.moire.ultrasonic.debug.WRITE_DOWNLOADED_FILE"
        const val ACTION_PROBE_CACHE_HIT =
            "org.moire.ultrasonic.debug.PROBE_CACHE_HIT"
        const val ACTION_PROBE_CLEANER =
            "org.moire.ultrasonic.debug.PROBE_CLEANER"
        const val ACTION_PROBE_CLEANUP_KEEPS_DOWNLOAD =
            "org.moire.ultrasonic.debug.PROBE_CLEANUP_KEEPS_DOWNLOAD"
        const val ACTION_SET_TINY_CACHE_LIMIT =
            "org.moire.ultrasonic.debug.SET_TINY_CACHE_LIMIT"
        const val ACTION_PROBE_DOWNLOADED_LIST =
            "org.moire.ultrasonic.debug.PROBE_DOWNLOADED_LIST"
    }
}
