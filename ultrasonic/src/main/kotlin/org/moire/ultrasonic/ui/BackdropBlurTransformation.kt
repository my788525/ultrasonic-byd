/*
 * BackdropBlurTransformation.kt
 * Lightweight software blur for the fullscreen cover backdrop.
 */
package org.moire.ultrasonic.ui

import android.graphics.Bitmap
import com.squareup.picasso.Transformation
import kotlin.math.max
import kotlin.math.min

/**
 * A pure-Kotlin, zero-dependency blur for the album art behind the fullscreen lyrics.
 *
 * The head unit runs Android 7: RenderEffect does not exist and RenderScript is deprecated
 * and unavailable in this build. Instead the cover is downscaled to a tiny bitmap, a separable
 * box blur runs over those few thousand pixels, and the ImageView scales that bitmap back up
 * with bilinear filtering. The result is the same soft look at a fraction of the cost, with no
 * native library and no extra permission. The working image is deliberately small:
 * blur buffers are the allocation that caused GC stalls on rapid track changes.
 *
 * @param strength 0..100; 0 means "no blur" and is handled by the caller (no transformation).
 */
class BackdropBlurTransformation(private val strength: Int) : Transformation {

    override fun transform(source: Bitmap): Bitmap {
        val level = strength.coerceIn(0, 100)
        if (level <= 0 || source.width < 2 || source.height < 2) return source

        // A stronger setting means a smaller downsample (softer) and a wider blur kernel.
        val targetW = min(source.width, max(20, (160 - level * 1.3f).toInt()))
        val targetH = max(1, (source.height.toLong() * targetW / source.width).toInt())
        val small = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        val radius = (level / 32 + 1).coerceIn(1, 3)

        val blurred = boxBlur(small, radius)
        if (blurred !== small) small.recycle()
        // Picasso 2.8 CONTRACT: when transform() returns a DIFFERENT bitmap than its
        // input, the transformation MUST recycle the input itself. Otherwise
        // BitmapHunter.applyCustomTransformations() posts an IllegalStateException
        // ("... mutated input Bitmap but failed to recycle the original") to the MAIN
        // thread Handler -> uncaught -> instant force-close. Verified by decompiling
        // the 2.8 AAR; this does NOT apply to Picasso 3.x, which recycles for you.
        // byd.61 removed this on that 3.x assumption and only avoided crashing because
        // the old targetW threshold (209) made small === source for typical covers;
        // byd.62's 114 threshold made small !== source the common case -> crash.
        // When small === source, small.recycle() above already covers it; recycle() on
        // an already-recycled bitmap is a safe no-op, so this is correct either way.
        source.recycle()
        return blurred
    }

    /** Part of Picasso's memory-cache key: changing the slider must invalidate the old bitmap. */
    override fun key(): String = "byd_backdrop_blur_${strength.coerceIn(0, 100)}"

    /**
     * Separable box blur with row / column prefix sums. Runs on the already downscaled bitmap,
     * so even the slowest setting touches only a few thousand pixels.
     */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val horizontal = IntArray(n)
        val psR = IntArray(w + 1)
        val psG = IntArray(w + 1)
        val psB = IntArray(w + 1)
        for (y in 0 until h) {
            val row = y * w
            psR[0] = 0
            psG[0] = 0
            psB[0] = 0
            for (x in 0 until w) {
                val c = px[row + x]
                psR[x + 1] = psR[x] + ((c shr 16) and 0xFF)
                psG[x + 1] = psG[x] + ((c shr 8) and 0xFF)
                psB[x + 1] = psB[x] + (c and 0xFF)
            }
            for (x in 0 until w) {
                val a = max(0, x - radius)
                val b = min(w, x + radius + 1)
                val count = b - a
                val r = (psR[b] - psR[a]) / count
                val g = (psG[b] - psG[a]) / count
                val bl = (psB[b] - psB[a]) / count
                horizontal[row + x] = (px[row + x] and 0xFF000000.toInt()) or
                    (r shl 16) or (g shl 8) or bl
            }
        }

        val out = IntArray(n)
        val vsR = IntArray(h + 1)
        val vsG = IntArray(h + 1)
        val vsB = IntArray(h + 1)
        for (x in 0 until w) {
            vsR[0] = 0
            vsG[0] = 0
            vsB[0] = 0
            for (y in 0 until h) {
                val c = horizontal[y * w + x]
                vsR[y + 1] = vsR[y] + ((c shr 16) and 0xFF)
                vsG[y + 1] = vsG[y] + ((c shr 8) and 0xFF)
                vsB[y + 1] = vsB[y] + (c and 0xFF)
            }
            for (y in 0 until h) {
                val a = max(0, y - radius)
                val b = min(h, y + radius + 1)
                val count = b - a
                val r = (vsR[b] - vsR[a]) / count
                val g = (vsG[b] - vsG[a]) / count
                val bl = (vsB[b] - vsB[a]) / count
                out[y * w + x] = (horizontal[y * w + x] and 0xFF000000.toInt()) or
                    (r shl 16) or (g shl 8) or bl
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}
