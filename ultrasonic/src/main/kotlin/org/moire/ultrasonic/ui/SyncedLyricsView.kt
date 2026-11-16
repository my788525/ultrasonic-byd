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
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import kotlin.math.abs
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.util.LyricsParser
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.TimedLyric
import org.moire.ultrasonic.util.TimedWord
import org.moire.ultrasonic.util.WordLyricsParser
import timber.log.Timber

/**
 * Shows lyrics that follow the song, synced line by line or word by word, optionally with a
 * translation line under each original.
 *
 * Word level lyrics are painted as a *continuous* wipe instead of lighting up one whole word at
 * a time: [WordLyricsParser.sungFraction] interpolates the playback position between two real
 * word timestamps and returns a fractional character position. A self driven animation (15, 30
 * or 60 fps, chosen in settings) interpolates between the 500 ms position reports of the player.
 * Only three colour spans on the active line change per frame; the text is never re-set for a
 * word change, so there is no measure/layout pass and the smooth scroll is not restarted.
 *
 * Every visual parameter is read from [Settings]: display mode (auto / word / synced), font
 * size, alignment, line spacing, the on-screen position of the active line, the brightness of
 * the inactive lines, whether to auto scroll, whether to emphasise the active line, the frame
 * rate and the translation mode / size. [applySettings] is called by the hosting fragments on
 * their normal refresh, so a change in settings is visible as soon as the driver returns.
 *
 * A translation is a separate, smaller line under the original. It is never part of the sung
 * text, so the karaoke wipe still covers exactly the words that are heard.
 */
class SyncedLyricsView(
    private val scroll: View?,
    private val textView: TextView,
    /** Optional small badge showing the lyrics source. Hidden unless enabled in settings. */
    private val sourceBadge: TextView? = null
) {

    /** One rendered lyric: the sung text, its word timeline and an optional translation. */
    private data class DisplayLine(
        val main: String,
        val words: List<TimedWord>,
        val sub: String?,
        /** Characters in front of every word, so the wipe does not add them up again. */
        val wordPrefix: IntArray
    )

    /** Real time axis, used only to decide which line is active. */
    private var lines: List<TimedLyric> = emptyList()
    private var display: List<DisplayLine> = emptyList()
    private var lineStarts: IntArray = IntArray(0)
    private var subStarts: IntArray = IntArray(0)
    private var subEnds: IntArray = IntArray(0)

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
    private var currentSourceLabel: CharSequence? = null

    /** The three spans that make up the wipe on the active line. Reused for every frame. */
    private val wipeSpans = ArrayList<MutableColorSpan>(3)

    /** The spannable the wipe spans are currently attached to. */
    private var wipeSpansOwner: Spannable? = null

    /**
     * A foreground colour whose colour can be changed in place.
     *
     * The stock [ForegroundColorSpan] fixes its colour in the constructor, so the wipe had to
     * create one to three new spans for every frame. This one keeps the colour in a field,
     * so the same three objects serve the whole song. It stays a [ForegroundColorSpan] on
     * purpose: the line colours are spans of that type and the wipe has to win over them.
     */
    private class MutableColorSpan(color: Int) : ForegroundColorSpan(color) {
        private var mutableColor: Int = color

        /** A copied spannable recreates its spans through this constructor. */
        constructor(original: MutableColorSpan) : this(original.getForegroundColor()) {
            mutableColor = original.mutableColor
        }

        fun setColor(color: Int) {
            mutableColor = color
        }

        override fun updateDrawState(tp: TextPaint) {
            tp.color = mutableColor
        }
    }

    /** The padding the layout started with, restored when there are no timed lyrics to centre. */
    private var originalPaddingBottom = -1

    // Playback anchor for the frame animation. The player reports its position every 500 ms;
    // between reports the view advances from the anchor using elapsedRealtime().
    private var anchorPositionMs = 0L
    private var anchorRealtimeMs = 0L
    private var animating = false

    /** The earliest moment the next wipe frame may be drawn. */
    private var nextFrameAtMs = 0L

    /** The interval between two wipe frames, in ms. Refreshed with the other settings. */
    private var wipeFrameIntervalMs = 33L

    /**
     * The frame loop is driven by the display instead of by a timer.
     *
     * A timer that fires every 33 ms drifts against the 16.7 ms refresh of the head unit, so
     * the wipe was now and then drawn twice in a row and now and then not at all. Posting to
     * the choreographer puts every update on a real frame; the frame rate from the settings
     * is kept by skipping the frames that are not due yet.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            if (!textView.isAttachedToWindow) {
                stopAnimation()
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextFrameAtMs) {
                nextFrameAtMs = now + wipeFrameIntervalMs
                val lead = (now - anchorRealtimeMs).coerceIn(0L, MAX_LEAD_MS)
                // Only the wipe is interpolated. Switching lines on an extrapolated position
                // would let a delayed player report pull the highlight back one line.
                renderAt(anchorPositionMs + lead, allowLineChange = false)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    val hasTimedLyrics: Boolean
        get() = lines.isNotEmpty()

    /** True when at least one line carries real word timings. */
    val hasWordTimedLyrics: Boolean
        get() = display.any { it.words.isNotEmpty() }

    /** Whether the karaoke wipe is enabled for the current settings and lyrics. */
    private val wipeEnabled: Boolean
        get() = when (Settings.lyricsDisplayMode) {
            "synced" -> false
            "word" -> true
            else -> hasWordTimedLyrics
        } && hasWordTimedLyrics

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
        currentSourceLabel = label
        renderSourceBadge()
    }

    private fun renderSourceBadge() {
        val badge = sourceBadge ?: return
        val label = currentSourceLabel
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
        display = emptyList()
        lineStarts = IntArray(0)
        subStarts = IntArray(0)
        subEnds = IntArray(0)
        cachedBuilder = null
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
        detachWipeSpans()
        restorePaddingIfNeeded()
        textView.text = text
        scroll?.scrollTo(0, 0)
    }

    /**
     * Applies every visual setting at once. [fontSp] is supplied by the caller because the
     * right hand pane enlarges the lyrics while it is fullscreen.
     *
     * Rebuilding the display here (and not only on a new download) is what makes the
     * "original + translation / translation only / original only" switch instant.
     */
    fun applySettings(fontSp: Int = Settings.lyricsFontSizeSp) {
        refreshFrameInterval()
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
        textView.gravity = when (Settings.lyricsAlignment) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        val spacingPx = Settings.lyricsLineSpacingDp *
            textView.resources.displayMetrics.density
        textView.setLineSpacing(spacingPx, 1f)
        renderSourceBadge()
        if (lines.isNotEmpty()) {
            buildDisplay()
            rebuildText()
        }
    }

    /** Kept for callers that only want to set the size; delegates to [applySettings]. */
    fun setFontSizeSp(sp: Int) = applySettings(sp)

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
            display = emptyList()
            lineStarts = IntArray(0)
            subStarts = IntArray(0)
            subEnds = IntArray(0)
            cachedBuilder = null
            currentIndex = -1
            lastWipeFraction = INVALID_FRACTION
            detachWipeSpans()
            restorePaddingIfNeeded()
            textView.text = raw.orEmpty()
            scroll?.scrollTo(0, 0)
            return
        }

        lines = resolved
        buildDisplay()
        rebuildText()
        Timber.d(
            "Loaded %d timed lyric lines (%d word timed, %d translated)",
            lines.size,
            display.count { it.words.isNotEmpty() },
            display.count { it.sub != null }
        )
    }

    /** Turns the raw lines into what is actually put on screen, honouring the translation mode. */
    private fun buildDisplay() {
        val mode = Settings.lyricsTranslationMode
        display = lines.map { line ->
            val translation = line.translation?.trim()?.takeIf { it.isNotBlank() }
            val onlyTranslation = mode == "only" && translation != null
            val words = if (onlyTranslation) emptyList() else line.words
            DisplayLine(
                main = if (onlyTranslation) translation!! else line.text,
                words = words,
                sub = if (mode == "show") translation else null,
                wordPrefix = WordLyricsParser.prefixLengths(words)
            )
        }
    }

    /** Builds the spannable from [display] and puts it on the TextView. */
    private fun rebuildText() {
        stopAnimation()
        val builder = SpannableStringBuilder()
        lineStarts = IntArray(display.size)
        subStarts = IntArray(display.size) { -1 }
        subEnds = IntArray(display.size)
        display.forEachIndexed { index, line ->
            lineStarts[index] = builder.length
            builder.append(line.main)
            builder.append('\n')
            if (!line.sub.isNullOrBlank()) {
                val subStart = builder.length
                builder.append(line.sub)
                subStarts[index] = subStart
                subEnds[index] = builder.length
                builder.append('\n')
            }
        }
        detachWipeSpans()
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
        cachedBuilder = builder
        renderLines(-1)
        adoptDisplayedSpannable(builder)
        renderAt(lastPositionMs)
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
        if (isPlaying && wipeEnabled) restartAnimation() else stopAnimation()
        renderAt(positionMs)
    }

    /** Stops the frame animation, e.g. when the fragment is paused or the view is destroyed. */
    fun stopAnimation() {
        if (!animating) return
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * Re-anchors the frame loop on every player report. Besides preventing drift, this makes a
     * frame rate change in settings take effect on the very next 500 ms tick.
     */
    private fun restartAnimation() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        refreshFrameInterval()
        animating = true
        nextFrameAtMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * The interval between two wipe frames. It is read once per player report instead of
     * once per frame, so the animation loop itself never touches the preferences.
     */
    private fun refreshFrameInterval() {
        val fps = Settings.lyricsWipeFps.toIntOrNull()?.coerceIn(10, 60) ?: 30
        wipeFrameIntervalMs = (1_000L / fps).coerceIn(8L, 100L)
    }

    /** Called when the track changes, so the next position does not reuse the old highlight. */
    fun reset() {
        stopAnimation()
        currentIndex = -1
        lastWipeFraction = INVALID_FRACTION
    }

    /** Re-applies every span after a colour change. */
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
            if (index in display.indices && Settings.lyricsBoldCurrent) {
                val start = lineStarts[index]
                val end = (start + display[index].main.length).coerceAtMost(builder.length)
                applyEmphasis(builder, start, end)
            }
            // A line change is the only moment the text is re-set; word changes never are.
            adoptDisplayedSpannable(builder)
            if (index >= 0) scrollWhenLayoutReady(index)
        }

        val fraction = if (index in display.indices && wipeEnabled &&
            display[index].words.isNotEmpty()
        ) {
            WordLyricsParser.sungFraction(
                display[index].words,
                positionMs,
                lineEndMs(index),
                display[index].wordPrefix
            )
        } else {
            WHOLE_LINE
        }
        if (renderWipe(index, fraction, lineChanged)) {
            // A line change also repaints the line that was just sung, so the whole text has
            // to be invalidated then. In between, only the wipe inside the active line moved.
            if (lineChanged) textView.invalidate() else invalidateWipeArea(index)
        }
    }

    /**
     * TextView copies a Spannable passed to setText(..., SPANNABLE). Re-read the object it
     * actually holds, so every later span change lands on the text that is on screen.
     */
    private fun adoptDisplayedSpannable(builder: Spannable) {
        detachWipeSpans()
        textView.setText(builder, TextView.BufferType.SPANNABLE)
        val live = (textView.text as? Spannable) ?: builder
        // setText may copy the spannable. The copies of the wipe spans would sit on the
        // screen next to the reused ones, so they are removed again.
        live.getSpans(0, live.length, MutableColorSpan::class.java)
            .forEach { live.removeSpan(it) }
        cachedBuilder = live
    }

    /** The end of a line for the last word: the next line's real start, never an invented time. */
    private fun lineEndMs(index: Int): Long {
        val line = lines[index]
        val nextLine = lines.getOrNull(index + 1)?.startMs ?: -1L
        if (nextLine > line.startMs) return nextLine
        // Last line, or malformed timestamps: the last word is shown in full at its own start.
        return display[index].words.lastOrNull()?.startMs ?: line.startMs
    }

    // -------------------------------------------------------------------------------------
    // Span painting
    // -------------------------------------------------------------------------------------

    /** Colours of whole lines. Only runs when the active line changes or the text is rebuilt. */
    private fun renderLines(highlight: Int) {
        val builder = cachedBuilder ?: return
        detachWipeSpans()
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
        val inactive = withAlpha(base, Settings.lyricsInactiveAlpha / 100f)
        val pending = withAlpha(base, PENDING_ALPHA)
        val sungLine = withAlpha(
            if (accentColor != 0) accentColor else base, SUNG_LINE_ALPHA
        )
        val subInactive = withAlpha(base, (Settings.lyricsInactiveAlpha / 100f) * 0.85f)
        val subActive = withAlpha(base, 0.92f)
        val translationScale = Settings.lyricsTranslationFontScale / 100f

        for (index in display.indices) {
            val start = lineStarts[index]
            val end = (start + display[index].main.length).coerceAtMost(builder.length)
            if (end > start) {
                when {
                    index < highlight -> applyColor(builder, sungLine, start, end)
                    index > highlight -> applyColor(builder, inactive, start, end)
                    highlight < 0 -> applyColor(builder, inactive, start, end)
                    // The active line starts dim; the wipe paints the sung part on top of it.
                    else -> applyColor(builder, pending, start, end)
                }
            }
            val subStart = subStarts[index]
            val subEnd = subEnds[index]
            if (subStart >= 0 && subEnd > subStart && subEnd <= builder.length) {
                applyColor(
                    builder,
                    if (index == highlight) subActive else subInactive,
                    subStart,
                    subEnd
                )
                builder.setSpan(
                    RelativeSizeSpan(translationScale),
                    subStart,
                    subEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
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
        if (index !in display.indices) {
            detachWipeSpans()
            return true
        }

        val start = lineStarts[index]
        val end = (start + display[index].main.length).coerceAtMost(builder.length)
        if (end <= start) {
            detachWipeSpans()
            return true
        }

        val base = baseRgb()
        val active = if (accentColor != 0) accentColor else base
        val pending = withAlpha(base, PENDING_ALPHA)

        if (fraction < 0f) {
            // Line synced lyrics, or word by word disabled in settings.
            applyWipeSlot(builder, 0, active, start, end)
            trimWipeSlots(1)
            return true
        }

        val whole = fraction.toInt().coerceIn(0, end - start)
        val partial = fraction - whole
        val boundary = (start + whole).coerceAtMost(end)
        applyWipeSlot(builder, 0, active, start, boundary)
        if (boundary >= end) {
            trimWipeSlots(1)
            return true
        }
        if (partial > 0f) {
            applyWipeSlot(
                builder,
                1,
                blend(pending, active, partial),
                boundary,
                (boundary + 1).coerceAtMost(end)
            )
            applyWipeSlot(builder, 2, pending, boundary + 1, end)
        } else {
            removeWipeSlot(1)
            applyWipeSlot(builder, 2, pending, boundary, end)
        }
        trimWipeSlots(3)
        return true
    }

    /**
     * Puts one part of the wipe on the screen.
     *
     * The span of the previous frame is reused: only its colour and its range change, so a
     * frame of the animation does not allocate anything at all.
     */
    private fun applyWipeSlot(
        builder: Spannable,
        slot: Int,
        color: Int,
        start: Int,
        end: Int
    ) {
        if (end <= start) {
            removeWipeSlot(slot)
            return
        }
        if (wipeSpansOwner !== builder) {
            detachWipeSpans()
            wipeSpansOwner = builder
        }
        val span = wipeSpans.getOrNull(slot)
            ?: MutableColorSpan(color).also { wipeSpans.add(it) }
        // Detach it from its old range first, so it can never paint two places at once.
        wipeSpansOwner?.removeSpan(span)
        builder.removeSpan(span)
        span.setColor(color)
        builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Detaches one slot; its span object stays available for the next frame. */
    private fun removeWipeSlot(slot: Int) {
        val span = wipeSpans.getOrNull(slot) ?: return
        wipeSpansOwner?.removeSpan(span)
    }

    /** Detaches the slots from [keep] upwards; their span objects stay available for reuse. */
    private fun trimWipeSlots(keep: Int) {
        for (slot in keep until wipeSpans.size) {
            wipeSpansOwner?.removeSpan(wipeSpans[slot])
        }
    }

    /** Detaches every wipe span from the spannable that carries them. */
    private fun detachWipeSpans() {
        val owner = wipeSpansOwner ?: return
        wipeSpans.forEach { owner.removeSpan(it) }
        wipeSpansOwner = null
    }

    /**
     * Repaints only the row that changed.
     *
     * The wipe only touches the active lyric line, but invalidating the whole text made the
     * renderer redraw every visible line - and in fullscreen each of them carries a software
     * shadow, which is the most expensive thing on the screen. Invalidating the rectangle of
     * the active line paints exactly the same pixels with a lot less work.
     */
    private fun invalidateWipeArea(index: Int) {
        val layout = textView.layout
        if (layout == null || layout.lineCount == 0 || textView.width <= 0 ||
            index !in display.indices
        ) {
            textView.invalidate()
            return
        }
        val start = lineStarts[index].coerceIn(0, layout.getLineEnd(layout.lineCount - 1))
        val end = (start + display[index].main.length).coerceAtLeast(start)
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end.coerceAtLeast(start))
        val top = layout.getLineTop(firstLine)
        val bottom = layout.getLineBottom(lastLine)
        // postInvalidateOnAnimation is the supported way to repaint a part of a view: it
        // hands the rectangle to the next frame instead of doing it right away.
        textView.postInvalidateOnAnimation(
            0,
            top - WIPE_INVALIDATE_MARGIN_PX,
            textView.width,
            bottom + WIPE_INVALIDATE_MARGIN_PX
        )
    }

    private fun applyColor(builder: Spannable, color: Int, start: Int, end: Int) {
        if (end <= start) return
        builder.setSpan(
            ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun applyEmphasis(builder: Spannable, start: Int, end: Int) {
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

    /** Scrolls to the line once the text has been measured. */
    private fun scrollWhenLayoutReady(index: Int) {
        if (!Settings.lyricsAutoScroll) return
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

    /** Reserves enough room below the last line for it to still reach the highlight anchor. */
    private fun ensureTrailingSpace(): Boolean {
        val target = scroll ?: return false
        val viewport = target.height
        if (viewport <= 0) return false
        if (originalPaddingBottom < 0) originalPaddingBottom = textView.paddingBottom
        val needed = (viewport * (1f - anchorFraction())).toInt()
        if (textView.paddingBottom >= needed) return false
        textView.setPadding(
            textView.paddingLeft, textView.paddingTop, textView.paddingRight, needed
        )
        return true
    }

    private fun anchorFraction(): Float =
        (Settings.lyricsCurrentLineAnchor / 100f).coerceIn(0.15f, 0.8f)

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
        val anchor = (target.height * anchorFraction()).toInt()
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

        /** Lines far from the current one recede; the sung line stays the brightest thing. */
        private const val PENDING_ALPHA = 0.75f
        private const val SUNG_LINE_ALPHA = 0.58f

        /** Never extrapolate further than one player update interval beyond the anchor. */
        private const val MAX_LEAD_MS = 600L

        /** Skip a frame when the wipe moved by less than 1/100 of a character. */
        private const val WIPE_EPSILON = 0.01f

        /** Room around the active line for the shadow of the large fullscreen lyrics. */
        private const val WIPE_INVALIDATE_MARGIN_PX = 16

        private const val INVALID_FRACTION = -999f
        private const val WHOLE_LINE = -1f

        /** Convenience factory which also remembers the track the lyrics belong to. */
        fun attach(scroll: View?, textView: TextView, accentColor: Int): SyncedLyricsView =
            SyncedLyricsView(scroll, textView).apply { setAccentColor(accentColor) }

        fun trackIdOf(track: Track?): String? = track?.id
    }
}
