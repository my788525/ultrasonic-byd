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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.io.IOException
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

    // Artwork is only a small notification / media-session icon. Downsample every decode to
    // ICON_SIZE_PX so each bitmap is ~0.25 MB instead of a ~4.6 MB full-resolution original;
    // that is what made rapid skips stutter under GC pressure (the real byd.59 fix).
    //
    // Keep the concurrency model deliberately simple. byd.56 proved the one rule that matters
    // for track switching: a cache miss must fail FAST so MediaSession can finish building the
    // metadata/notification and deliver onMediaItemTransition at once. byd.59 wrapped these
    // calls in a single-thread executor + @Synchronized + an in-flight LRU + a directExecutor
    // callback that re-locked the loader; that choked the MediaSession refresh path and brought
    // back the 15 s "UI stuck on the previous song" stall. Do not reintroduce locking/caching
    // here without a measurement on the real head unit.
    private val executorService: ListeningExecutorService by lazy {
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(2)
        )
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executorService.submit<Bitmap> { decode(data) }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executorService.submit<Bitmap> { load(uri) }
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
    }
}
