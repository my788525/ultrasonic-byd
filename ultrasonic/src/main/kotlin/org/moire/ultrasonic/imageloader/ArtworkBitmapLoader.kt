/*
 * ArtworkBitmapLoader.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.imageloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.FileUtil

@SuppressLint("UnsafeOptInUsageError")
class ArtworkBitmapLoader : BitmapLoader, KoinComponent {

    private val imageLoaderProvider: ImageLoaderProvider by inject()

    // Artwork is only drawn as a small notification / media-session icon. Decode on a SINGLE
    // thread so at most one bitmap is ever being allocated, and downsample to ICON_SIZE_PX so
    // each bitmap is ~0.25 MB instead of the ~4.6 MB full-resolution original. A fixed pool
    // with an unbounded queue let rapid track changes pile up full-size decodes, which caused
    // repeated GC pauses that froze the UI while audio (on its own thread) kept playing.
    private val executorService: ListeningExecutorService by lazy {
        MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "artwork-decode").apply { isDaemon = true }
            }
        )
    }

    // Media3 re-requests artwork on every metadata / notification refresh. Reuse the in-flight
    // (or last completed) future for the same uri so a burst of refreshes cannot enqueue N
    // decodes. Bound the map so the entries do not grow with the number of songs played; the
    // access-order LRU evicts the least recently used entry beyond MAX_TRACKED_URIS.
    private val recentUris = object :
        LinkedHashMap<String, ListenableFuture<Bitmap>>(MAX_TRACKED_URIS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ListenableFuture<Bitmap>>?
        ): Boolean = size > MAX_TRACKED_URIS
    }

    @Synchronized
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executorService.submit<Bitmap> { decode(data) }
    }

    @Synchronized
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        recentUris[key]?.let { return it }
        val future = executorService.submit<Bitmap> { load(uri) }
        recentUris[key] = future
        // A failed load means the cover was not on disk yet. Drop it from the cache so the
        // next metadata refresh can retry after the background download has finished —
        // otherwise a cached failure would permanently hide a cover that is now available.
        // A successful future stays cached and is reused without re-decoding.
        Futures.addCallback(
            future,
            object : FutureCallback<Bitmap> {
                override fun onSuccess(result: Bitmap?) {}
                override fun onFailure(t: Throwable) {
                    synchronized(this@ArtworkBitmapLoader) {
                        if (recentUris[key] === future) recentUris.remove(key)
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
        return future
    }

    private fun decode(data: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw IllegalArgumentException("Could not decode bitmap")
    }

    /** Decode at roughly notification-icon resolution to keep the bitmap small in memory. */
    private fun decodeFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /** Power-of-two sample size that brings the longest edge down to about ICON_SIZE_PX. */
    private fun sampleSize(width: Int, height: Int): Int {
        val longest = max(width, height)
        if (longest <= ICON_SIZE_PX) return 1
        return max(1, (longest.toFloat() / ICON_SIZE_PX).roundToInt())
    }

    @Throws(IOException::class)
    private fun load(uri: Uri): Bitmap {
        val parts = uri.path?.trim('/')?.split('|')

        require(parts!!.count() == 2) { "Invalid bitmap Uri" }
        val coverId = parts[0]
        val cacheKey = parts[1]

        // Fast path: load from disk cache if already downloaded (downsampled to icon size)
        if (cacheKey.isNotBlank() && cacheKey != "-1") {
            val file = File(FileUtil.getAlbumArtFile(cacheKey))
            if (file.exists() && file.length() > 0L) {
                val bitmap = decodeFile(file.absolutePath)
                if (bitmap != null) return bitmap
            }
            val largeKey = cacheKey.replace(FileUtil.SUFFIX_SMALL, FileUtil.SUFFIX_LARGE)
            if (largeKey != cacheKey) {
                val largeFile = File(FileUtil.getAlbumArtFile(largeKey))
                if (largeFile.exists() && largeFile.length() > 0L) {
                    val bitmap = decodeFile(largeFile.absolutePath)
                    if (bitmap != null) return bitmap
                }
            }
        }

        // If not on disk, trigger async background download without blocking MediaSession
        if (coverId.isNotBlank()) {
            imageLoaderProvider.executeOn {
                it.downloadCoverArt(coverId, FileUtil.getAlbumArtFile(cacheKey))
            }
        }

        // Fail fast so MediaSession does not block the song transition for 15s!
        throw IOException("Artwork not cached on disk for $coverId")
    }

    private companion object {
        /** Longest edge of the decoded artwork; media-session icons never need full size. */
        const val ICON_SIZE_PX = 256
        /** Upper bound on remembered artwork requests, so the map cannot grow per song. */
        const val MAX_TRACKED_URIS = 32
    }
}
