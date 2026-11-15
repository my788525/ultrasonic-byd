/*
 * SyncedLyricsView.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import kotlin.math.abs
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.LyricsParser
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.TimedLyric
import org.moire.ultrasonic.util.WordLyricsParser
import timber.log.Timber

/**
 * Shows lyrics that follow the song, one line at a time or word by word.
 *
 * Word level lyrics are painted as a *continuous* wipe instead of lighting up one whole word at
 * a time: [WordLyricsParser.sungFraction] interpolates the playback position between two real
 * word timestamps and returns a fractional character position. The fully sung characters take
 * the accent colour, the character being sung right now takes a blended colour that moves from
 * dim to accent, and the rest stays dim. A ~30 fps self driven animation interpolates between
 * the (500 ms) position updates coming from the player, so the wipe advances smoothly even
 * though the rest of the UI only refreshes twice a second.
 *
 * Only three colour spans on the active line change per frame. The text is never re-set for a
 * word change, so there is no measure/layout pass and the smooth scroll is not restarted.
 *
 * The same helper drives the lyrics page of the player and the right hand pane, so both look
 * identical. Word by word rendering can be turned off in settings; the view then highlights
 * whole lines, which is also the fallback for lyrics without word timings.
 */
class SyncedLyricsView(
    private val scroll: View?,
    private val textView: TextView,
    /** Optional small badge showing the lyrics source. Hidden unless enabled in settings. */
    private val sourceBadge: TextView? = null
) {

    private var lines: List<TimedLyric> = emptyList()
    private var lineStarts: IntArray = IntArray(0)

    /**
     * The *displayed* spannable. TextView.setText(..., SPANNABLE) copies its argument, so this
     * must be re-read from the TextView after every setText; otherwise span changes land on an
     * orphan object and the wipe never appears on screen.
     */
    private var cachedBuilder: Spannable? = null
    private var currentIndex = -1
    private var accentColor = 0
    private var currentTrackId: String? = null
    private var lastPositionMs = 0L
    private var lastWipeFraction = INVALID_FRACTION

    /** The three spans that make up the wipe on the active line. Replaced in place each frame. */
    private val wipeSpans = ArrayList<ForegroundColorSpan>()

    /** The padding the layout started with, restored when there are no timed lyrics to centre. */
    private var originalPaddingBottom = -1

    // Playback anchor for the frame animation. The player reports its position every 500 ms;
    // between reports the view advances from the anchor using elapsedRealtime().
    private var anchorPositionMs = 0L
    private var anchorRealtimeMs = 0L
    private var animating = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            if (!textView.isAttachedToWindow) {
                stopAnimation()
                return
            }
            val lead = (SystemClock.elapsedRealtime() - anchorRealtimeMs)
                .coerceIn(0L, MAX_LEAD_MS)
            // Only the wipe is interpolated. Switching lines on an extrapolated position
            // would let a delayed player report pull the highlight back one line.
            renderAt(anchorPositionMs + lead, allowLineChange = false)
            if (animating) textView.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    val hasTimedLyrics: Boolean
        get() = lines.isNotEmpty()

    /** True when at least one line carries real word timings. */
    val hasWordTimedLyrics: Boolean
        get() = lines.any { it.words.isNotEmpty() }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        if (lines.isNotEmpty()) redrawAll()
    }

    fun trackId(): String? = currentTrackId

    fun setTrackId(id: String?) {
        currentTrackId = id
    }

    /** Shows where the lyrics came from. Hidden entirely unless the setting is enabled. */
    fun setSourceLabel(label: CharSequence?) {
        val badge = sourceBadge ?: return
        if (!Settings.showLyricsSourceBadge || label.isNullOrBlank()) {
            badge.visibility = View.GONE
            return
        }
        val text = label.toString()
        if (badge.visibility == View.VISIBLE && badge.text?.toString() == text) return
        badge.text = text
        badge.visibility = View.VISIBLE
    }

    /** Shows a localised placeholder such as "loading" or "no lyrics". */
    fun showPlaceholder(text: CharSequence) {
        stopAnimation()
        lines = emptyList()
        lineStarts = IntArray(0)
        cachedBuilder = null
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
        wipeSpans.clear()
        restorePaddingIfNeeded()
        textView.text = text
        scroll?.scrollTo(0, 0)
    }

    /** Sets the lyrics font size in sp. Applied to both timed and plain lyrics. */
    fun setFontSizeSp(sp: Int) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        if (lines.isNotEmpty()) {
            // Changing the size changes the line wrap, so the highlight has to be laid out again.
            redrawAll()
        }
    }

    /** Replaces the shown lyrics. Timed lines are detected, everything else is shown verbatim. */
    fun setLyrics(raw: String?) {
        setLyrics(raw, emptyList())
    }

    /**
     * Replaces the shown lyrics.
     *
     * [timed] carries the time axis when the source delivered one. Without it the text is
     * parsed as LRC, which is the fallback for servers that only send plain lyrics.
     */
    fun setLyrics(raw: String?, timed: List<TimedLyric>) {
        stopAnimation()
        val resolved = if (timed.isNotEmpty()) {
            timed.sortedBy { it.startMs }
        } else {
            LyricsParser.parse(raw)
        }

        if (resolved.isEmpty()) {
            lines = emptyList()
            lineStarts = IntArray(0)
            cachedBuilder = null
            currentIndex = -1
            lastWipeFraction = INVALID_FRACTION
            wipeSpans.clear()
            restorePaddingIfNeeded()
            textView.text = raw.orEmpty()
            scroll?.scrollTo(0, 0)
            return
        }

        lines = resolved
        val builder = SpannableStringBuilder()
        lineStarts = IntArray(lines.size)
        lines.forEachIndexed { index, line ->
            lineStarts[index] = builder.length
            builder.append(line.text)
            builder.append('\n')
        }
        cachedBuilder = builder
        wipeSpans.clear()
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION

        renderLines(-1)
        adoptDisplayedSpannable(builder)
        Timber.d(
            "Loaded %d timed lyric lines (%d with word timings)",
            lines.size,
            lines.count { it.words.isNotEmpty() }
        )
    }

    /**
     * Reports a fresh playback position.
     *
     * [isPlaying] gates the frame animation: a paused song must not keep wiping forwards. The
     * player calls this every 500 ms; between calls the animation interpolates from the anchor.
     */
    fun update(positionMs: Long, isPlaying: Boolean = true) {
        if (lines.isEmpty()) {
            stopAnimation()
            return
        }
        anchorPositionMs = positionMs
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        if (isPlaying && Settings.wordByWordLyrics && hasWordTimedLyrics) {
            startAnimation()
        } else {
            stopAnimation()
        }
        renderAt(positionMs)
    }

    /** Stops the frame animation, e.g. when the fragment is paused or the view is destroyed. */
    fun stopAnimation() {
        if (!animating) return
        animating = false
        textView.removeCallbacks(frameRunnable)
    }

    private fun startAnimation() {
        if (animating) return
        animating = true
        textView.removeCallbacks(frameRunnable)
        textView.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
    }

    /** Called when the track changes, so the next position does not reuse the old highlight. */
    fun reset() {
        stopAnimation()
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
    }

    /** Re-applies every span after a colour or font size change. */
    private fun redrawAll() {
        if (cachedBuilder == null) return
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
        renderAt(lastPositionMs)
    }

    private fun renderAt(positionMs: Long, allowLineChange: Boolean = true) {
        if (lines.isEmpty()) return
        lastPositionMs = positionMs
        val reportedIndex = LyricsParser.lineIndexAt(lines, positionMs)
        val index = if (allowLineChange) reportedIndex else currentIndex
        val lineChanged = allowLineChange && index != currentIndex
        val builder = cachedBuilder ?: return

        if (lineChanged) {
            currentIndex = index
            lastWipeFraction = INVALID_FRACTION
            renderLines(index)
            if (index in lines.indices) {
                val start = lineStarts[index]
                val end = (start + lines[index].text.length).coerceAtMost(builder.length)
                applyEmphasis(builder, start, end)
            }
            // A line change is the only moment the text is re-set; word changes never are.
            adoptDisplayedSpannable(builder)
            if (index >= 0) scrollWhenLayoutReady(index)
        }

        val fraction = if (index in lines.indices &&
            Settings.wordByWordLyrics &&
            lines[index].words.isNotEmpty()
        ) {
            WordLyricsParser.sungFraction(lines[index].words, positionMs, lineEndMs(index))
        } else {
            WHOLE_LINE
        }
        if (renderWipe(index, fraction, lineChanged)) textView.invalidate()
    }

    /**
     * TextView copies a Spannable passed to setText(..., SPANNABLE). Re-read the object it
     * actually holds, so every later span change lands on the text that is on screen.
     */
    private fun adoptDisplayedSpannable(builder: Spannable) {
        wipeSpans.forEach { builder.removeSpan(it) }
        wipeSpans.clear()
        textView.setText(builder, TextView.BufferType.SPANNABLE)
        cachedBuilder = (textView.text as? Spannable) ?: builder
    }

    /** The end of a line for the last word: the next line's real start, never an invented time. */
    private fun lineEndMs(index: Int): Long {
        val line = lines[index]
        val nextLine = lines.getOrNull(index + 1)?.startMs ?: -1L
        if (nextLine > line.startMs) return nextLine
        // Last line, or malformed timestamps: the last word is shown in full at its own start.
        return line.words.lastOrNull()?.startMs ?: line.startMs
    }

    // -------------------------------------------------------------------------------------
    // Span painting
    // -------------------------------------------------------------------------------------

    /** Colours of whole lines. Only runs when the active line changes. */
    private fun renderLines(highlight: Int) {
        val builder = cachedBuilder ?: return
        // Only these three span types are ever attached, so a full sweep is both cheap and
        // immune to the spannable being copied by TextView on the previous setText.
        builder.getSpans(0, builder.length, ForegroundColorSpan::class.java)
            .forEach { builder.removeSpan(it) }
        builder.getSpans(0, builder.length, StyleSpan::class.java)
            .forEach { builder.removeSpan(it) }
        builder.getSpans(0, builder.length, RelativeSizeSpan::class.java)
            .forEach { builder.removeSpan(it) }
        wipeSpans.clear()

        val base = baseRgb()
        val inactive = withAlpha(base, INACTIVE_ALPHA)
        val pending = withAlpha(base, PENDING_ALPHA)
        val sungLine = withAlpha(
            if (accentColor != 0) accentColor else base, SUNG_LINE_ALPHA
        )

        for (index in lines.indices) {
            val start = lineStarts[index]
            val end = (start + lines[index].text.length).coerceAtMost(builder.length)
            if (end <= start) continue
            when {
                index < highlight -> applyColor(builder, sungLine, start, end)
                index > highlight -> applyColor(builder, inactive, start, end)
                highlight < 0 -> applyColor(builder, inactive, start, end)
                // The active line starts dim; the wipe paints the sung part on top of it.
                else -> applyColor(builder, pending, start, end)
            }
        }
    }

    /**
     * Paints the continuous wipe on the active line.
     *
     * [fraction] is a fractional character offset. Everything before the whole part is sung,
     * the character at that offset is blended between dim and accent by its fractional part,
     * and the remainder stays dim. Only these three spans are touched per frame.
     */
    private fun renderWipe(index: Int, fraction: Float, force: Boolean): Boolean {
        val builder = cachedBuilder ?: return false
        if (!force && abs(fraction - lastWipeFraction) < WIPE_EPSILON) return false
        lastWipeFraction = fraction
        clearWipeSpans(builder)
        if (index !in lines.indices) return true

        val start = lineStarts[index]
        val end = (start + lines[index].text.length).coerceAtMost(builder.length)
        if (end <= start) return true

        val base = baseRgb()
        val active = if (accentColor != 0) accentColor else base
        val pending = withAlpha(base, PENDING_ALPHA)

        if (fraction < 0f) {
            // Line synced lyrics, or word by word disabled in settings.
            applyWipeColor(builder, active, start, end)
            return true
        }

        val whole = fraction.toInt().coerceIn(0, end - start)
        val partial = fraction - whole
        if (whole > 0) applyWipeColor(builder, active, start, (start + whole).coerceAtMost(end))
        val boundary = (start + whole).coerceAtMost(end)
        if (boundary >= end) return true
        if (partial > 0f) {
            applyWipeColor(
                builder,
                blend(pending, active, partial),
                boundary,
                (boundary + 1).coerceAtMost(end)
            )
            if (boundary + 1 < end) {
                applyWipeColor(builder, pending, boundary + 1, end)
            }
        } else {
            applyWipeColor(builder, pending, boundary, end)
        }
        return true
    }

    private fun applyColor(
        builder: Spannable,
        color: Int,
        start: Int,
        end: Int
    ) {
        if (end <= start) return
        builder.setSpan(
            ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun clearWipeSpans(builder: Spannable) {
        wipeSpans.forEach { builder.removeSpan(it) }
        wipeSpans.clear()
    }

    private fun applyWipeColor(
        builder: Spannable,
        color: Int,
        start: Int,
        end: Int
    ) {
        if (end <= start) return
        val span = ForegroundColorSpan(color)
        builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        wipeSpans.add(span)
    }

    private fun applyEmphasis(
        builder: Spannable,
        start: Int,
        end: Int
    ) {
        if (end <= start) return
        builder.setSpan(
            StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(HIGHLIGHT_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun baseRgb(): Int {
        val color = textView.currentTextColor
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    /** Linear ARGB blend used for the character that is being sung right now. */
    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            channel(Color.alpha(from), Color.alpha(to)),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to))
        )
    }

    // -------------------------------------------------------------------------------------
    // Scrolling (a rotation or a wrapped bilingual line must not shift the anchor)
    // -------------------------------------------------------------------------------------

    /**
     * Scrolls to the line once the text has been measured.
     *
     * A plain post() is not enough: it only puts the scroll at the end of the message queue,
     * which can be before the measure and layout pass that builds the new layout. Without a
     * layout the scroll silently does nothing, and because the line index is already updated
     * the scroll is never retried.
     */
    private fun scrollWhenLayoutReady(index: Int) {
        val attempt = object : Runnable {
            override fun run() {
                val layout = textView.layout
                if (layout != null && layout.lineCount > 0 && textView.width > 0) {
                    if (ensureTrailingSpace()) {
                        // Adding bottom padding changes the text layout, so give it one more
                        // frame before reading the line positions.
                        textView.postOnAnimation { scrollToLine(index) }
                    } else {
                        scrollToLine(index)
                    }
                } else {
                    textView.postOnAnimation(this)
                }
            }
        }
        textView.postOnAnimation(attempt)
    }

    /**
     * Reserves enough room below the last line for it to still reach the highlight anchor.
     * Without this the last lines of a song stay glued to the bottom of the pane.
     *
     * @return true when the padding changed and the layout needs another frame to settle.
     */
    private fun ensureTrailingSpace(): Boolean {
        val target = scroll ?: return false
        val viewport = target.height
        if (viewport <= 0) return false
        if (originalPaddingBottom < 0) originalPaddingBottom = textView.paddingBottom
        val needed = (viewport * (1f - CURRENT_LINE_ANCHOR)).toInt()
        if (textView.paddingBottom >= needed) return false
        textView.setPadding(
            textView.paddingLeft, textView.paddingTop, textView.paddingRight, needed
        )
        return true
    }

    private fun restorePaddingIfNeeded() {
        if (originalPaddingBottom >= 0 && textView.paddingBottom != originalPaddingBottom) {
            textView.setPadding(
                textView.paddingLeft, textView.paddingTop, textView.paddingRight,
                originalPaddingBottom
            )
        }
    }

    private fun scrollToLine(index: Int) {
        val target = scroll ?: return
        val layout = textView.layout ?: return
        if (index !in lineStarts.indices) return

        // One lyric can wrap into several TextView layout lines: bilingual lyrics on the car
        // pane almost always do. The character offset of the lyric maps it to the real line.
        val charOffset = lineStarts[index]
            .coerceAtMost(layout.getLineEnd(layout.lineCount - 1))
        val textLine = layout.getLineForOffset(charOffset)

        val lineCenter = (layout.getLineTop(textLine) + layout.getLineBottom(textLine)) / 2
        val anchor = (target.height * CURRENT_LINE_ANCHOR).toInt()
        val offset = (contentOffsetY(target) + lineCenter - anchor).coerceAtLeast(0)

        when (target) {
            is ScrollView -> target.smoothScrollTo(0, offset)
            is NestedScrollView -> target.smoothScrollTo(0, offset)
            else -> target.scrollTo(0, offset)
        }
    }

    /**
     * The lyric TextView is not always the only child of the scroll container: the full lyrics
     * page puts the artist and title above it. Translate the TextView's own layout coordinates
     * into the scroll container's content coordinates before computing the scroll offset.
     */
    private fun contentOffsetY(target: View): Int {
        var view: View = textView
        var total = 0
        var guard = 0
        while (view !== target && view.parent is View && guard++ < 10) {
            total += view.top
            view = view.parent as View
        }
        return total
    }

    companion object {
        private const val HIGHLIGHT_SCALE = 1.15f

        /** Where the current line sits inside the viewport: 0.5 = centre, 0.38 = slightly above. */
        private const val CURRENT_LINE_ANCHOR = 0.38f

        /** Lines far from the current one recede; the sung line stays the brightest thing. */
        private const val INACTIVE_ALPHA = 0.42f
        private const val PENDING_ALPHA = 0.75f
        private const val SUNG_LINE_ALPHA = 0.58f

        /** ~30 fps is smooth enough for a wipe and much kinder to a head unit than every frame. */
        private const val FRAME_INTERVAL_MS = 33L

        /** Never extrapolate further than one player update interval beyond the anchor. */
        private const val MAX_LEAD_MS = 600L

        /** Skip a frame when the wipe moved by less than 1/100 of a character. */
        private const val WIPE_EPSILON = 0.01f

        private const val INVALID_FRACTION = -999f
        private const val WHOLE_LINE = -1f

        /** Convenience factory which also remembers the track the lyrics belong to. */
        fun attach(scroll: View?, textView: TextView, accentColor: Int): SyncedLyricsView =
            SyncedLyricsView(scroll, textView).apply { setAccentColor(accentColor) }

        fun trackIdOf(track: Track?): String? = track?.id
    }
}
