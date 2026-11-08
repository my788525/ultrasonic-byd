/*
 * SyncedLyricsView.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

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
import timber.log.Timber

/**
 * Shows lyrics that follow the song.
 *
 * Servers with LRC lyrics return timed lines; those are highlighted line by line and scrolled so
 * that the current line stays slightly above the middle of the view. Servers that only return
 * plain text are shown as before, without any scrolling, because there is nothing to synchronise
 * to.
 *
 * The same helper drives the lyrics page of the player, the right hand pane and the floating
 * window, so all three behave identically.
 */
class SyncedLyricsView(
    private val scroll: View?,
    private val textView: TextView
) {

    private var lines: List<TimedLyric> = emptyList()
    private var lineStarts: IntArray = IntArray(0)
    private var cachedBuilder: SpannableStringBuilder? = null
    private var activeColorSpan: ForegroundColorSpan? = null
    private val activeStyleSpan = StyleSpan(Typeface.BOLD)
    private val activeSizeSpan = RelativeSizeSpan(HIGHLIGHT_SCALE)
    private var currentIndex = -1
    private var accentColor = 0
    private var currentTrackId: String? = null

    /** The padding the layout started with, restored when there are no timed lyrics to centre. */
    private var originalPaddingBottom = -1

    val hasTimedLyrics: Boolean
        get() = lines.isNotEmpty()

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        if (lines.isNotEmpty() && currentIndex in lines.indices) {
            render(currentIndex)
        }
    }

    fun trackId(): String? = currentTrackId

    fun setTrackId(id: String?) {
        currentTrackId = id
    }

    /** Shows a localised placeholder such as "loading" or "no lyrics". */
    fun showPlaceholder(text: CharSequence) {
        lines = emptyList()
        lineStarts = IntArray(0)
        cachedBuilder = null
        currentIndex = -1
        restorePaddingIfNeeded()
        textView.text = text
        scroll?.scrollTo(0, 0)
    }

    /** Sets the lyrics font size in sp. Applied to both timed and plain lyrics. */
    fun setFontSizeSp(sp: Int) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
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
        currentIndex = -1
        render(-1)
        Timber.d("Loaded %d timed lyric lines", lines.size)
    }

    /** Moves the highlight to the line that belongs to [positionMs]. */
    fun update(positionMs: Long) {
        if (lines.isEmpty()) return

        val index = LyricsParser.lineIndexAt(lines, positionMs)
        if (index == currentIndex) return

        currentIndex = index
        render(index)
    }

    /** Called when the track changes, so the next position does not reuse the old highlight. */
    fun reset() {
        currentIndex = -1
    }

    private fun render(highlight: Int) {
        val builder = cachedBuilder ?: run {
            val fresh = SpannableStringBuilder()
            lineStarts = IntArray(lines.size)
            lines.forEachIndexed { index, line ->
                lineStarts[index] = fresh.length
                fresh.append(line.text)
                fresh.append('\n')
            }
            cachedBuilder = fresh
            fresh
        }

        activeColorSpan?.let { builder.removeSpan(it) }
        builder.removeSpan(activeStyleSpan)
        builder.removeSpan(activeSizeSpan)

        if (highlight in lines.indices) {
            val start = lineStarts[highlight]
            val end = (start + lines[highlight].text.length).coerceAtMost(builder.length)
            if (end > start) {
                val colorSpan = ForegroundColorSpan(accentColor)
                activeColorSpan = colorSpan
                builder.setSpan(colorSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(activeStyleSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(activeSizeSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        textView.setText(builder, TextView.BufferType.SPANNABLE)
        if (highlight >= 0) {
            // The layout of the new text is only valid after the next measure pass, so the
            // scroll has to wait one frame. Without this the lyrics never move on Android 7.
            scrollWhenLayoutReady(highlight)
        }
    }

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

        /** Convenience factory which also remembers the track the lyrics belong to. */
        fun attach(scroll: View?, textView: TextView, accentColor: Int): SyncedLyricsView =
            SyncedLyricsView(scroll, textView).apply { setAccentColor(accentColor) }

        fun trackIdOf(track: Track?): String? = track?.id
    }
}
