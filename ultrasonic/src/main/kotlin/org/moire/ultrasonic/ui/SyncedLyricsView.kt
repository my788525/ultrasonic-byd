/*
 * SyncedLyricsView.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

import android.graphics.Color
import android.graphics.Typeface
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
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.LyricsParser
import org.moire.ultrasonic.util.TimedLyric
import org.moire.ultrasonic.util.WordLyricsParser
import timber.log.Timber

/**
 * Shows lyrics that follow the song, one line at a time or word by word.
 *
 * Servers with LRC lyrics return timed lines; those are highlighted line by line and scrolled
 * so that the current line stays slightly above the middle of the view. When a line carries
 * word timings (Enhanced LRC, or the Lyricsfile YAML returned by the automatic LRCLIB search)
 * the sung part of the line is painted in the accent colour and the rest stays grey, so the
 * words light up as they are heard. Lines without word timings keep the old line highlight.
 *
 * The visual language follows what works at arm's length in a car: the active line is bright
 * and larger, already sung lines recede, and lines that are still far away dim further. That is
 * the same hierarchy SimpMusic uses for its Apple Music style lyrics sheet.
 *
 * Colour spans are pooled and updated in place. A word change only invalidates the TextView,
 * it does not call setText(): a full measure/layout pass every couple of hundred milliseconds
 * would stutter on a head unit and would also restart the smooth scroll.
 *
 * The same helper drives the lyrics page of the player, the right hand pane and the floating
 * window, so all three behave identically.
 */
class SyncedLyricsView(
    private val scroll: View?,
    private val textView: TextView,
    /** Optional small badge showing where the lyrics came from (server / online / word sync). */
    private val sourceBadge: TextView? = null
) {

    private var lines: List<TimedLyric> = emptyList()
    private var lineStarts: IntArray = IntArray(0)
    private var cachedBuilder: SpannableStringBuilder? = null
    private var currentIndex = -1
    private var currentWordIndex = -1
    private var accentColor = 0
    private var currentTrackId: String? = null

    /** Colour spans currently attached to the builder, by identity, so they can be removed. */
    private val usedColorSpans = java.util.IdentityHashMap<ForegroundColorSpan, Boolean>()
    private val colorSpanPools = HashMap<Int, ArrayList<ForegroundColorSpan>>()
    private val emphasisSpans = ArrayList<Any>()

    /** The padding the layout started with, restored when there are no timed lyrics to centre. */
    private var originalPaddingBottom = -1

    val hasTimedLyrics: Boolean
        get() = lines.isNotEmpty()

    /** True when at least one line carries real word timings. */
    val hasWordTimedLyrics: Boolean
        get() = lines.any { it.words.isNotEmpty() }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        colorSpanPools.clear()
        if (lines.isNotEmpty()) {
            renderColors(currentIndex, currentWordIndex)
            textView.invalidate()
        }
    }

    fun trackId(): String? = currentTrackId

    fun setTrackId(id: String?) {
        currentTrackId = id
    }

    /** Shows where the lyrics came from. An empty label hides the badge. */
    fun setSourceLabel(label: CharSequence?) {
        val badge = sourceBadge ?: return
        if (label.isNullOrBlank()) {
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
        lines = emptyList()
        lineStarts = IntArray(0)
        cachedBuilder = null
        currentIndex = -1
        currentWordIndex = -1
        usedColorSpans.clear()
        emphasisSpans.clear()
        restorePaddingIfNeeded()
        textView.text = text
        scroll?.scrollTo(0, 0)
    }

    /** Sets the lyrics font size in sp. Applied to both timed and plain lyrics. */
    fun setFontSizeSp(sp: Int) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        if (lines.isNotEmpty()) {
            // Changing the size changes the line wrap, so the highlight has to be laid out again.
            val builder = cachedBuilder ?: return
            textView.setText(builder, TextView.BufferType.SPANNABLE)
            if (currentIndex >= 0) scrollWhenLayoutReady(currentIndex)
        }
    }

    /** Replaces the shown lyrics. Timed lines are detected, everything else is shown verbatim. */
    fun setLyrics(raw: String?) {
        setLyrics(raw, emptyList())
    }

    /**
     * Replaces the shown lyrics.
     *
     * [timed] carries the time axis when the server delivered one. Without it the text is
     * parsed as LRC, which is the fallback for servers that only send plain lyrics.
     */
    fun setLyrics(raw: String?, timed: List<TimedLyric>) {
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
            currentWordIndex = -1
            usedColorSpans.clear()
            emphasisSpans.clear()
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
        usedColorSpans.clear()
        emphasisSpans.clear()
        currentIndex = -1
        currentWordIndex = -1

        renderColors(-1, -1)
        textView.setText(builder, TextView.BufferType.SPANNABLE)
        Timber.d(
            "Loaded %d timed lyric lines (%d with word timings)",
            lines.size,
            lines.count { it.words.isNotEmpty() }
        )
    }

    /** Moves the highlight to the line and word that belong to [positionMs]. */
    fun update(positionMs: Long) {
        if (lines.isEmpty()) return

        val index = LyricsParser.lineIndexAt(lines, positionMs)
        val wordIndex = if (index in lines.indices && lines[index].words.isNotEmpty()) {
            WordLyricsParser.wordIndexAt(lines[index].words, positionMs)
        } else {
            -1
        }
        if (index == currentIndex && wordIndex == currentWordIndex) return

        val lineChanged = index != currentIndex
        currentIndex = index
        currentWordIndex = wordIndex

        renderColors(index, wordIndex)

        if (lineChanged) {
            val builder = cachedBuilder ?: return
            clearEmphasis(builder)
            if (index in lines.indices) {
                val start = lineStarts[index]
                val end = (start + lines[index].text.length).coerceAtMost(builder.length)
                applyEmphasis(builder, start, end)
            }
            textView.setText(builder, TextView.BufferType.SPANNABLE)
            if (index >= 0) scrollWhenLayoutReady(index)
        } else {
            // Only the colours changed. The Spannable is mutable and already installed on the
            // TextView, so a repaint is enough and the scroll animation keeps running.
            textView.invalidate()
        }
    }

    /** Called when the track changes, so the next position does not reuse the old highlight. */
    fun reset() {
        currentIndex = -1
        currentWordIndex = -1
    }

    // -------------------------------------------------------------------------------------
    // Span painting
    // -------------------------------------------------------------------------------------

    private fun renderColors(highlight: Int, wordIndex: Int) {
        val builder = cachedBuilder ?: return
        clearColorSpans(builder)

        val base = baseRgb()
        val active = if (accentColor != 0) accentColor else base
        val inactive = withAlpha(base, INACTIVE_ALPHA)
        val pending = withAlpha(base, PENDING_ALPHA)
        val sungLine = withAlpha(active, SUNG_LINE_ALPHA)

        for (index in lines.indices) {
            val start = lineStarts[index]
            val end = (start + lines[index].text.length).coerceAtMost(builder.length)
            if (end <= start) continue

            when {
                index < highlight -> applyColor(builder, sungLine, start, end)
                index > highlight -> applyColor(builder, inactive, start, end)
                highlight < 0 -> applyColor(builder, inactive, start, end)
                else -> {
                    val words = lines[highlight].words
                    if (words.isEmpty()) {
                        applyColor(builder, active, start, end)
                    } else {
                        val sung = (start + WordLyricsParser.sungLength(words, wordIndex))
                            .coerceIn(start, end)
                        applyColor(builder, active, start, sung)
                        applyColor(builder, pending, sung, end)
                    }
                }
            }
        }
    }

    private fun clearColorSpans(builder: SpannableStringBuilder) {
        usedColorSpans.keys.forEach { builder.removeSpan(it) }
        usedColorSpans.clear()
    }

    private fun applyColor(
        builder: SpannableStringBuilder,
        color: Int,
        start: Int,
        end: Int
    ) {
        if (end <= start) return
        val pool = colorSpanPools.getOrPut(color) { ArrayList() }
        val span = pool.firstOrNull { !usedColorSpans.containsKey(it) }
            ?: ForegroundColorSpan(color).also { pool.add(it) }
        builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        usedColorSpans[span] = true
    }

    private fun clearEmphasis(builder: SpannableStringBuilder) {
        emphasisSpans.forEach { builder.removeSpan(it) }
        emphasisSpans.clear()
    }

    private fun applyEmphasis(
        builder: SpannableStringBuilder,
        start: Int,
        end: Int
    ) {
        if (end <= start) return
        val bold = StyleSpan(Typeface.BOLD)
        val larger = RelativeSizeSpan(HIGHLIGHT_SCALE)
        builder.setSpan(bold, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(larger, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        emphasisSpans.add(bold)
        emphasisSpans.add(larger)
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

    // -------------------------------------------------------------------------------------
    // Scrolling (unchanged: a rotation or a wrapped bilingual line must not shift the anchor)
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
     * Without this the last lines of a song stay glued to the bottom of the pane, behind the
     * lyric-offset controls, because a ScrollView cannot scroll past its content.
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
        // pane almost always do. Using the lyric index directly as a layout line index used to
        // scroll far too little, leaving the highlighted line at the very bottom of the screen.
        // The character offset of the lyric maps it to the real layout line.
        val charOffset = lineStarts[index]
            .coerceAtMost(layout.getLineEnd(layout.lineCount - 1))
        val textLine = layout.getLineForOffset(charOffset)

        val lineCenter = (layout.getLineTop(textLine) + layout.getLineBottom(textLine)) / 2
        // Slightly above the vertical centre: the pane has the lyric-offset and transport
        // controls along its bottom edge, so a centred line would sit right behind them.
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

        /** Convenience factory which also remembers the track the lyrics belong to. */
        fun attach(scroll: View?, textView: TextView, accentColor: Int): SyncedLyricsView =
            SyncedLyricsView(scroll, textView).apply { setAccentColor(accentColor) }

        fun trackIdOf(track: Track?): String? = track?.id
    }
}
