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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.util.FileUtil

@SuppressLint("UnsafeOptInUsageError")
class ArtworkBitmapLoader : BitmapLoader, KoinComponent {

    private val imageLoaderProvider: ImageLoaderProvider by inject()

    private val executorService: ListeningExecutorService by lazy {
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(2)
        )
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executorService.submit<Bitmap> {
            decode(
                data
            )
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executorService.submit<Bitmap> {
            load(uri)
        }
    }

    private fun decode(data: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        return bitmap ?: throw IllegalArgumentException("Could not decode bitmap")
    }

    @Throws(IOException::class)
    private fun load(uri: Uri): Bitmap {
        val parts = uri.path?.trim('/')?.split('|')

        require(parts!!.count() == 2) { "Invalid bitmap Uri" }
        val coverId = parts[0]
        val cacheKey = parts[1]

        // Fast path: load from disk cache if already downloaded
        if (cacheKey.isNotBlank() && cacheKey != "-1") {
            val file = File(FileUtil.getAlbumArtFile(cacheKey))
            if (file.exists() && file.length() > 0L) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) return bitmap
            }
            val largeKey = cacheKey.replace(FileUtil.SUFFIX_SMALL, FileUtil.SUFFIX_LARGE)
            if (largeKey != cacheKey) {
                val largeFile = File(FileUtil.getAlbumArtFile(largeKey))
                if (largeFile.exists() && largeFile.length() > 0L) {
                    val bitmap = BitmapFactory.decodeFile(largeFile.absolutePath)
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
}
