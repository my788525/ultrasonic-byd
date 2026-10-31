/*
 * SyncedLyricsView.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
 * that the current line stays in the middle of the view. Servers that only return plain text are
 * shown as before, without any scrolling, because there is nothing to synchronise to.
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
    private var currentIndex = -1
    private var accentColor = 0
    private var currentTrackId: String? = null

    val hasTimedLyrics: Boolean
        get() = lines.isNotEmpty()

    fun setAccentColor(color: Int) {
        accentColor = color
    }

    fun trackId(): String? = currentTrackId

    fun setTrackId(id: String?) {
        currentTrackId = id
    }

    /** Shows a localised placeholder such as "loading" or "no lyrics". */
    fun showPlaceholder(text: CharSequence) {
        lines = emptyList()
        lineStarts = IntArray(0)
        currentIndex = -1
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
            currentIndex = -1
            textView.text = raw.orEmpty()
            scroll?.scrollTo(0, 0)
            return
        }

        lines = resolved
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
        val builder = SpannableStringBuilder()
        lineStarts = IntArray(lines.size)

        lines.forEachIndexed { index, line ->
            lineStarts[index] = builder.length
            builder.append(line.text)
            builder.append('\n')
        }

        if (highlight in lines.indices) {
            val start = lineStarts[highlight]
            val end = (start + lines[highlight].text.length).coerceAtMost(builder.length)
            if (end > start) {
                builder.setSpan(
                    ForegroundColorSpan(accentColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    RelativeSizeSpan(HIGHLIGHT_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        textView.text = builder
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
                    scrollToLine(index)
                } else {
                    textView.postOnAnimation(this)
                }
            }
        }
        textView.postOnAnimation(attempt)
    }

    private fun scrollToLine(index: Int) {
        val target = scroll ?: return
        val layout = textView.layout ?: return

        // Every lyric line is one line of text, so the line of the layout is the index of the
        // lyric. The text may be wrapped on a narrow screen, which makes the layout have more
        // lines than the lyrics, so the index is only used when it fits.
        val textLine = if (index < layout.lineCount) index else return

        val lineCenter = (layout.getLineTop(textLine) + layout.getLineBottom(textLine)) / 2
        val offset = (lineCenter - target.height / 2).coerceAtLeast(0)

        when (target) {
            is ScrollView -> target.smoothScrollTo(0, offset)
            is NestedScrollView -> target.smoothScrollTo(0, offset)
            else -> target.scrollTo(0, offset)
        }
    }

    companion object {
        private const val HIGHLIGHT_SCALE = 1.12f

        /** Convenience factory which also remembers the track the lyrics belong to. */
        fun attach(scroll: View?, textView: TextView, accentColor: Int): SyncedLyricsView =
            SyncedLyricsView(scroll, textView).apply { setAccentColor(accentColor) }

        fun trackIdOf(track: Track?): String? = track?.id
    }
}
