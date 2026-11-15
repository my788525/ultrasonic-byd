/*
 * PlayerPaneFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.adapters.QueueAdapter
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.service.plusAssign
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.ui.SyncedLyricsView
import org.moire.ultrasonic.util.CarTransport
import org.moire.ultrasonic.util.LyricsLoader
import org.moire.ultrasonic.util.NowPlayingHint
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.SleepTimerManager
import org.moire.ultrasonic.util.UiState
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * The permanent player panel of the two pane layout.
 *
 * On a head unit the list on the left is useless if picking a song sends the user to another
 * screen, so the player is always visible on the right side: cover, lyrics, seek bar and the
 * controls that are actually needed while driving (previous, play/pause, next, shuffle, repeat).
 *
 * This has to be a [ScopeFragment]: the media player manager and its lifecycle support are
 * registered inside the NavigationActivity scope, so a plain Fragment would not be able to
 * resolve them and the app would crash while inflating the pane.
 */
@Suppress("TooManyFunctions")
class PlayerPaneFragment : ScopeFragment(), SleepTimerManager.Listener {

    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val sleepTimerManager: SleepTimerManager by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    private var headerCard: View? = null
    private var stageCard: View? = null
    private var coverView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var lyricsView: TextView? = null
    private var lyricsScroll: View? = null
    private var seekBar: SeekBar? = null
    private var elapsedView: TextView? = null
    private var totalView: TextView? = null
    private var playButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var shuffleButton: ImageButton? = null
    private var repeatButton: ImageButton? = null
    private var statusView: TextView? = null
    private var queueView: RecyclerView? = null
    private var queueButton: ImageButton? = null
    private var queueAdapter: QueueAdapter? = null
    private var sleepButton: ImageButton? = null
    private var fullscreenButton: ImageButton? = null

    /** Last applied visual settings signature, so a change in settings is picked up at once. */
    private var lastAppearanceSignature = 0
    private var lastFullscreen = false

    /** The track whose lyrics are being shown, kept for the "search again" action in settings. */
    private var lyricsTrack: Track? = null

    /** Which header is on screen right now, so the title is not re-set twice a second. */
    private var showingNextHint = false
    private var shownNextTrackId: String? = null

    /** Watches the settings action that asks for a fresh online lookup. */
    private var lastReloadToken = 0L

    /** True while the queue is shown instead of the lyrics. */
    private var showQueue = false

    /**
     * The time of the last tap on the previous and on the next button.
     *
     * The buttons are large and the driver taps them with a finger that is not exactly
     * steady, so a double tap is common. It must not skip two songs.
     */
    private var lastPreviousClickAt = 0L
    private var lastNextClickAt = 0L

    /** True while the app waits for the server to come back. */
    private var waitingForNetwork = false

    private var currentTrack: Track? = null
    private var lastQueueIndex = -2
    private var lastPlayingState: Boolean? = null
    private var stateInitialized = false
    private var currentLyricsTrackId: String? = null
    private var syncedLyrics: SyncedLyricsView? = null
    private var seekBarTracking = false

    private val handler = Handler(Looper.getMainLooper())
    private val disposables = CompositeDisposable()
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_player_pane, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        headerCard = view.findViewById(R.id.pane_header_card)
        stageCard = view.findViewById(R.id.pane_stage_card)
        coverView = view.findViewById(R.id.pane_cover)
        titleView = view.findViewById(R.id.pane_title)
        artistView = view.findViewById(R.id.pane_artist)
        lyricsView = view.findViewById(R.id.pane_lyrics)
        lyricsScroll = view.findViewById(R.id.pane_lyrics_scroll)
        seekBar = view.findViewById(R.id.pane_seek)
        elapsedView = view.findViewById(R.id.pane_elapsed)
        totalView = view.findViewById(R.id.pane_total)
        playButton = view.findViewById(R.id.pane_play)
        previousButton = view.findViewById(R.id.pane_previous)
        nextButton = view.findViewById(R.id.pane_next)
        shuffleButton = view.findViewById(R.id.pane_shuffle)
        repeatButton = view.findViewById(R.id.pane_repeat)

        statusView = view.findViewById(R.id.pane_status)

        queueView = view.findViewById(R.id.pane_queue)
        queueButton = view.findViewById(R.id.pane_queue_button)
        sleepButton = view.findViewById(R.id.pane_sleep)
        fullscreenButton = view.findViewById(R.id.pane_fullscreen)

        lastReloadToken = Settings.lyricsReloadToken

        headerCard?.setOnClickListener {
            // In cinema mode the library is hidden, so opening the full screen player there
            // would appear to do nothing. The header acts as a second "leave fullscreen" target.
            if (UiState.playerFullscreen) {
                (activity as? NavigationActivity)?.togglePlayerFullscreen()
                return@setOnClickListener
            }
            runCatching {
                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.playerFragment)
            }
        }

        fullscreenButton?.setOnClickListener {
            (activity as? NavigationActivity)?.togglePlayerFullscreen()
        }

        decoratePane()

        lyricsView?.let { lyrics ->
            syncedLyrics = SyncedLyricsView(
                lyricsScroll,
                lyrics,
                view.findViewById(R.id.pane_lyrics_source)
            ).apply {
                setAccentColor(CarTheme.color(requireContext(), CarPalette.current().accent))
                applySettings(effectiveFontSp())
            }
        }
        syncFullscreenButton()
        lastAppearanceSignature = Settings.lyricsAppearanceSignature()
        lastFullscreen = UiState.playerFullscreen

        playButton?.setOnClickListener { mediaPlayerManager.togglePlayPause() }
        previousButton?.setOnClickListener { onPreviousPressed() }
        nextButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (CarTransport.isDebounced(lastNextClickAt, now)) return@setOnClickListener
            lastNextClickAt = now
            mediaPlayerManager.seekToNext()
        }
        shuffleButton?.setOnClickListener {
            val enabled = mediaPlayerManager.toggleShuffle()
            applyToggleTint(shuffleButton, enabled)
        }
        repeatButton?.setOnClickListener { cycleRepeatMode() }

        // The queue lives in the same space as the lyrics, so the button switches between
        // the two instead of opening another screen. The player stays in sight.
        queueAdapter = QueueAdapter(
            onPlayClick = { position -> playFromQueue(position) },
            onRemoveClick = { position -> removeFromQueue(position) }
        )
        queueView?.layoutManager = LinearLayoutManager(requireContext())
        queueView?.adapter = queueAdapter
        queueButton?.setOnClickListener { toggleQueue() }

        // One tap cycles 15 → 30 → 45 → 60 → off. The driver picks a duration without having
        // to read a dialog, and the toast confirms which one is now running.
        sleepButton?.setOnClickListener {
            sleepTimerManager.cycle()
            showSleepToast()
        }

        // Same distance as a swipe on the bar, but as a single big target
        view.findViewById<View>(R.id.pane_seek_back).setOnClickListener { seekBy(-SEEK_STEP_MS) }
        view.findViewById<View>(R.id.pane_seek_forward).setOnClickListener { seekBy(SEEK_STEP_MS) }

        seekBar?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsedView?.text = formatTime(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    seekBarTracking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    seekBarTracking = false
                    mediaPlayerManager.seekTo(seekBar.progress)
                }
            }
        )

        // The queue changes when a song is added or taken away, not only when the song
        // changes. Without this the list would keep showing a song that is gone.
        disposables += RxBus.playlistObservable.subscribe {
            if (showQueue) refreshQueue()
        }

        disposables += RxBus.networkRecoveryObservable.subscribe { state ->
            waitingForNetwork = state.isWaiting
            updateState()
        }

        updateState()
    }

    private fun decoratePane() {
        val ctx = context ?: return
        val palette = CarPalette.current()
        val accent = CarTheme.accent(ctx, palette)

        headerCard?.background = CarTheme.cardBackground(ctx, palette, cornerDp = 16f)
        stageCard?.background = CarTheme.roundedFill(
            ctx,
            CarTheme.withAlpha(CarTheme.surfaceColor(ctx, palette), 0xB8),
            CarTheme.withAlpha(accent, 0x26),
            16f
        )

        playButton?.let { play ->
            val playBg = CarTheme.accentButton(ctx, palette)
            play.background = RippleDrawable(
                ColorStateList.valueOf(CarTheme.withAlpha(Color.WHITE, 0x40)),
                playBg,
                playBg
            )
            ImageViewCompat.setImageTintList(
                play,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.pal_on_accent))
            )
        }

        seekBar?.progressTintList = ColorStateList.valueOf(accent)
        seekBar?.thumbTintList = ColorStateList.valueOf(accent)
    }

    override fun onResume() {
        super.onResume()
        decoratePane()
        handler.post(updateRunnable)
        sleepTimerManager.addListener(this)
        syncedLyrics?.setAccentColor(CarTheme.color(requireContext(), CarPalette.current().accent))
        applyLyricsAppearance()
    }

    /** Font size used on this pane; fullscreen mode enlarges it by its own setting. */
    private fun effectiveFontSp(): Int {
        val base = Settings.lyricsFontSizeSp.coerceIn(12, 36)
        return if (UiState.playerFullscreen) {
            (base * Settings.lyricsFullscreenFontScale / 100f).toInt().coerceIn(12, 48)
        } else {
            base
        }
    }

    /** Re-applies all lyric settings, called on resume and whenever their signature changes. */
    private fun applyLyricsAppearance() {
        lastAppearanceSignature = Settings.lyricsAppearanceSignature()
        lastFullscreen = UiState.playerFullscreen
        syncedLyrics?.applySettings(effectiveFontSp())
        syncFullscreenButton()
    }

    /** The button shows the action it will perform, not the state it is in. */
    private fun syncFullscreenButton() {
        val button = fullscreenButton ?: return
        button.visibility = if (Settings.showPlayerFullscreenButton) View.VISIBLE else View.GONE
        val full = UiState.playerFullscreen
        button.setImageResource(
            if (full) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen_enter
        )
        context?.let { ctx ->
            button.contentDescription = ctx.getString(
                if (full) R.string.player_fullscreen_exit else R.string.player_fullscreen_enter
            )
        }
        applyToggleTint(button, full)
    }

    /** Called by [NavigationActivity] the moment the pane expands or collapses. */
    fun onFullscreenChanged(fullscreen: Boolean) {
        lastFullscreen = fullscreen
        syncFullscreenButton()
        syncedLyrics?.applySettings(effectiveFontSp())
    }

    override fun onPause() {
        handler.removeCallbacks(updateRunnable)
        syncedLyrics?.stopAnimation()
        sleepTimerManager.removeListener(this)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        syncedLyrics?.stopAnimation()
        disposables.clear()
        headerCard = null
        stageCard = null
        coverView = null
        titleView = null
        artistView = null
        lastPlayingState = null
        lastQueueIndex = -2
        super.onDestroyView()
    }

    private fun updateState() {
        if (!isAdded) return

        val track = mediaPlayerManager.currentMediaItem?.toTrack()
        val shuffleIdx = mediaPlayerManager.getCurrentShuffleIndex()

        if (!stateInitialized || track?.id != currentTrack?.id) {
            stateInitialized = true
            currentTrack = track
            showTrack(track)
            if (showQueue) refreshQueue()
        } else if (showQueue && shuffleIdx != lastQueueIndex) {
            refreshQueue()
        }

        val duration = mediaPlayerManager.playerDuration
        val position = mediaPlayerManager.playerPosition

        // The view animates the wipe between these 500 ms reports, so the real playback
        // position (no calibration offset) and the playing state are all it needs.
        syncedLyrics?.update(position.toLong().coerceAtLeast(0L), mediaPlayerManager.isPlaying)
        renderHeader(position, duration)

        // "Search lyrics again" in settings bumps a token instead of putting a button over
        // the words. Every lyrics surface watches it on its normal refresh.
        val token = Settings.lyricsReloadToken
        if (token != lastReloadToken) {
            lastReloadToken = token
            loadLyrics(currentTrack, forceRefresh = true)
        }

        // A purely visual setting (mode, font, alignment, translation display, ...) must not
        // cost a new download. The signature changes on every such edit and the view rebuilds
        // its spannable in place. Fullscreen is polled too, in case a fragment transaction was
        // still pending when the activity announced the change.
        if (Settings.lyricsAppearanceSignature() != lastAppearanceSignature ||
            UiState.playerFullscreen != lastFullscreen
        ) {
            applyLyricsAppearance()
        }

        if (!seekBarTracking) {
            seekBar?.max = if (duration > 0) duration else 1
            seekBar?.progress = position.coerceAtLeast(0)
            elapsedView?.text = formatTime(position)
        }
        totalView?.text = formatTime(duration)

        val playing = mediaPlayerManager.isPlaying
        if (lastPlayingState != playing) {
            lastPlayingState = playing
            playButton?.setImageResource(
                if (playing) R.drawable.media_pause else R.drawable.media_start
            )
        }
        updateStatus()

        // The previous button restarts the current song, so it is only dead at the
        // beginning of the first song of the queue.
        val previousAction = CarTransport.previousAction(
            mediaPlayerManager.playerPosition.toLong(),
            mediaPlayerManager.canSeekToPrevious()
        )
        previousButton?.isEnabled = previousAction != CarTransport.PreviousAction.NONE
        previousButton?.alpha =
            if (previousAction == CarTransport.PreviousAction.NONE) ALPHA_DISABLED else ALPHA_ACTIVE
        nextButton?.isEnabled = mediaPlayerManager.canSeekToNext()
        nextButton?.alpha =
            if (mediaPlayerManager.canSeekToNext()) ALPHA_ACTIVE else ALPHA_DISABLED

        applyToggleTint(shuffleButton, mediaPlayerManager.isShufflePlayEnabled)
        applyToggleTint(queueButton, showQueue)

        updateRepeatButton()
    }

    private fun showTrack(track: Track?) {
        showingNextHint = false
        shownNextTrackId = null
        if (track == null) {
            coverView?.setImageResource(R.drawable.unknown_album)
        } else {
            coverView?.let { target ->
                imageLoaderProvider.executeOn { loader ->
                    if (isAdded) loader.loadImage(target, track, false, 0)
                }
            }
        }
        // A track change must start on the current song's name even if the player still
        // reports the last position of the previous track for one frame.
        renderHeader(0, mediaPlayerManager.playerDuration, true)
        loadLyrics(track)
    }

    /**
     * The title area shows the current song, except for two short windows where it announces
     * the next one: 10-20 s after the start and the last 15 s. Outside those windows the
     * current song comes back. Both are pure presentation; playback is untouched.
     */
    private fun renderHeader(positionMs: Int, durationMs: Int, force: Boolean = false) {
        val track = currentTrack
        val hint = if (track != null && Settings.showNextTrackHint &&
            NowPlayingHint.shouldShowNext(positionMs, durationMs)
        ) {
            runCatching { NowPlayingHint.nextTrack(mediaPlayerManager) }.getOrNull()
        } else {
            null
        }
        if (!force && hint?.id == shownNextTrackId && (hint != null) == showingNextHint) return
        showingNextHint = hint != null
        shownNextTrackId = hint?.id

        val context = context ?: return
        if (hint != null) {
            titleView?.text = NowPlayingHint.title(context, hint)
            artistView?.text = NowPlayingHint.subtitle(context, hint)
        } else {
            titleView?.text = track?.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.player_pane_idle)
            val detail = listOfNotNull(track?.artist, track?.album)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            artistView?.text = detail.ifBlank { context.getString(R.string.player_pane_idle_hint) }
        }
    }

    /** Loads lyrics from the server and, when needed, from the automatic online search. */
    private fun loadLyrics(track: Track?, forceRefresh: Boolean = false) {
        val synced = syncedLyrics ?: return
        lyricsTrack = track

        if (track == null) {
            synced.setSourceLabel(null)
            synced.showPlaceholder(getString(R.string.player_pane_idle_hint))
            currentLyricsTrackId = null
            return
        }

        if (!forceRefresh && currentLyricsTrackId == track.id) return
        currentLyricsTrackId = track.id
        synced.reset()
        synced.setSourceLabel(getString(R.string.player_pane_lyrics_loading))
        synced.showPlaceholder(getString(R.string.player_pane_lyrics_loading))

        val context = requireContext().applicationContext
        val durationMs = mediaPlayerManager.playerDuration.toLong()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LyricsLoader.load(context, track, durationMs, forceRefresh)
                }.getOrNull()
            }

            // Warm the next song in play order while this one is on screen. The playlist has
            // to be read on the main thread; LyricsLoader deduplicates this against the same
            // prefetch made by the floating window.
            if (isAdded && Settings.prefetchLyrics) {
                val next = runCatching {
                    val items = mediaPlayerManager.playlistInPlayOrder
                    val current = mediaPlayerManager.getCurrentShuffleIndex()
                    items.getOrNull(current + 1)?.toTrack()
                }.getOrNull()
                LyricsLoader.prefetch(context, next, (next?.duration ?: 0) * 1_000L)
            }

            if (!isAdded || currentLyricsTrackId != track.id) return@launch

            if (result == null || result.isEmpty) {
                synced.setSourceLabel(
                    if (result != null && result.labelRes != 0) getString(result.labelRes) else null
                )
                synced.showPlaceholder(getString(R.string.player_pane_lyrics_empty))
            } else {
                synced.setSourceLabel(
                    if (result.labelRes != 0) getString(result.labelRes) else null
                )
                synced.setLyrics(result.text, result.lines)
                synced.update(
                    mediaPlayerManager.playerPosition.toLong().coerceAtLeast(0L),
                    mediaPlayerManager.isPlaying
                )
            }
        }
    }

    /**
     * Switches between the lyrics and the queue.
     *
     * Both are shown in the same space of the panel, so the driver never leaves the
     * player and the song that is playing stays visible in the header.
     */
    private fun toggleQueue() {
        showQueue = !showQueue
        queueView?.visibility = if (showQueue) View.VISIBLE else View.GONE
        lyricsScroll?.visibility = if (showQueue) View.GONE else View.VISIBLE
        applyToggleTint(queueButton, showQueue)
        if (showQueue) refreshQueue()
    }

    /**
     * Reads the queue from the player and shows it.
     *
     * The list is in the order the songs are played. With the shuffle turned on that is
     * not the order they were added, and it is the play order the driver has to see.
     */
    private fun refreshQueue() {
        val tracks = mediaPlayerManager.playlistInPlayOrder.map { it.toTrack() }
        val current = mediaPlayerManager.getCurrentShuffleIndex()
        lastQueueIndex = current
        queueAdapter?.submit(tracks, current)
    }

    /** Starts a song of the queue. The position is the one in the play order. */
    private fun playFromQueue(position: Int) {
        val mediaIndex = mediaPlayerManager.getUnshuffledIndexOf(position)
        if (mediaIndex < 0) return
        mediaPlayerManager.play(mediaIndex)
        refreshQueue()
    }

    /**
     * Takes a song out of the queue.
     *
     * The player works with the index of the timeline, which is not the position in the
     * list when the shuffle is on, so the position has to be translated first.
     */
    private fun removeFromQueue(position: Int) {
        val mediaIndex = mediaPlayerManager.getUnshuffledIndexOf(position)
        if (mediaIndex < 0) return
        mediaPlayerManager.removeFromPlaylist(mediaIndex)
        refreshQueue()
    }

    /**
     * The previous button.
     *
     * In the middle of a song it goes back to the start of that song, at the very
     * beginning it changes to the song before. That is what a driver expects from every
     * known music player.
     */
    private fun onPreviousPressed() {
        val now = System.currentTimeMillis()
        if (CarTransport.isDebounced(lastPreviousClickAt, now)) return
        lastPreviousClickAt = now

        val position = mediaPlayerManager.playerPosition.toLong()
        val hasPrevious = mediaPlayerManager.canSeekToPrevious()
        when (CarTransport.previousAction(position, hasPrevious)) {
            CarTransport.PreviousAction.RESTART_CURRENT -> mediaPlayerManager.seekTo(0)
            CarTransport.PreviousAction.GO_TO_PREVIOUS -> mediaPlayerManager.seekToPrevious()
            CarTransport.PreviousAction.NONE -> return
        }
        updateState()
    }

    /**
     * Shows what the playback is doing right now.
     *
     * The panel has to tell "buffering" from "paused" apart. Without it the driver sees a
     * song that is loading as a song that is stopped, starts the music again, and the music
     * then comes back on its own.
     */
    private fun updateStatus() {
        val view = statusView ?: return
        val state = CarTransport.visualState(
            playbackState = mediaPlayerManager.playbackState,
            isPlaying = mediaPlayerManager.isPlaying,
            hasError = mediaPlayerManager.hasPlaybackError,
            waitingForNetwork = waitingForNetwork
        )
        val message = when (state) {
            CarTransport.VisualState.PLAYING -> null
            CarTransport.VisualState.BUFFERING -> getString(R.string.player_pane_status_buffering)
            CarTransport.VisualState.PAUSED -> getString(R.string.player_pane_status_paused)
            CarTransport.VisualState.ERROR -> getString(R.string.player_pane_status_error)
            CarTransport.VisualState.WAITING_FOR_NETWORK ->
                getString(R.string.player_pane_status_offline)
            CarTransport.VisualState.IDLE -> null
        }
        if (message == null) {
            view.visibility = View.GONE
            view.text = ""
            return
        }
        val statusColor = when (state) {
            CarTransport.VisualState.ERROR, CarTransport.VisualState.WAITING_FOR_NETWORK ->
                STATUS_COLOR_ERROR
            else -> STATUS_COLOR_WARNING
        }
        view.text = message
        view.visibility = View.VISIBLE
        view.setTextColor(statusColor)
        context?.let { ctx ->
            view.background = CarTheme.roundedFill(
                ctx,
                CarTheme.withAlpha(statusColor, 0x26),
                CarTheme.withAlpha(statusColor, 0x66),
                10f
            )
        }
    }

    private fun cycleRepeatMode() {
        var newRepeat = mediaPlayerManager.repeatMode + 1
        // Media3 semantics: OFF = 0, ALL = 1, ONE = 2. The cycle has to wrap after ONE,
        // comparing against ALL would skip the single track repeat completely.
        if (newRepeat > REPEAT_ONE) newRepeat = REPEAT_OFF
        mediaPlayerManager.repeatMode = newRepeat
        updateRepeatButton()
    }

    private fun updateRepeatButton() {
        val icon = when (mediaPlayerManager.repeatMode) {
            REPEAT_ONE -> R.drawable.media_repeat_one
            REPEAT_ALL -> R.drawable.media_repeat_all
            else -> R.drawable.media_repeat_off
        }
        repeatButton?.setImageResource(icon)
        applyToggleTint(repeatButton, mediaPlayerManager.repeatMode != REPEAT_OFF)
    }

    private fun formatTime(millis: Int): String {
        if (millis <= 0) return "0:00"
        val totalSeconds = millis / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** Moves the playback by a fixed step, clamped to the length of the track. */
    private fun seekBy(deltaMs: Int) {
        val duration = mediaPlayerManager.playerDuration
        if (duration <= 0) return
        val target = (mediaPlayerManager.playerPosition + deltaMs).coerceIn(0, duration)
        mediaPlayerManager.seekTo(target)
    }

    // region sleep timer

    /** Tells the driver which duration is now running, or that it is off. */
    private fun showSleepToast() {
        val message = if (sleepTimerManager.isActive) {
            getString(R.string.sleep_timer_minutes, Settings.sleepTimerMinutes)
        } else {
            getString(R.string.sleep_timer_off)
        }
        Util.toast(message, context = requireContext())
    }

    /** The accent colour marks a running timer; white means it is off. */
    private fun applySleepTint(active: Boolean) {
        val color = if (active) {
            CarTheme.accent(requireContext(), CarPalette.current())
        } else {
            Color.WHITE
        }
        sleepButton?.let { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(color)) }
    }

    /** The accent colour marks a toggle as on (shuffle/repeat); white means it is off. This is
     *  the same cue the sleep-timer uses, so an active mode is always readable at a glance. */
    private fun applyToggleTint(button: ImageButton?, active: Boolean) {
        val color = if (active) {
            CarTheme.accent(requireContext(), CarPalette.current())
        } else {
            Color.WHITE
        }
        button?.let { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(color)) }
    }

    override fun onSleepTimerActiveChanged(active: Boolean) {
        applySleepTint(active)
    }

    override fun onSleepTimerTick(remainingMillis: Long) {
        if (!isAdded) return
        val minutes = (remainingMillis / 60_000L).toInt().coerceAtLeast(1)
        sleepButton?.contentDescription = getString(R.string.sleep_timer_minutes, minutes)
    }

    override fun onSleepTimerFired() {
        mediaPlayerManager.pause()
    }

    // endregion

    companion object {
        private const val UPDATE_INTERVAL_MS = 500L
        private const val LYRICS_FETCH_TIMEOUT_MS = 8_000L
        private const val ALPHA_DISABLED = 0.35f
        private const val STATUS_COLOR_WARNING = 0xFFFFC107.toInt()
        private const val STATUS_COLOR_ERROR = 0xFFFF534D.toInt()
        private const val ALPHA_ACTIVE = 1.0f
        private const val SEEK_STEP_MS = 15_000
        private const val REPEAT_OFF = 0
        private const val REPEAT_ALL = 1
        private const val REPEAT_ONE = 2
    }
}
