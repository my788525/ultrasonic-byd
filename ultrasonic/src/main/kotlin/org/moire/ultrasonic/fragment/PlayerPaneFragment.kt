/*
 * PlayerPaneFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.adapters.QueueAdapter
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.service.plusAssign
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.ui.SyncedLyricsView
import org.moire.ultrasonic.util.CarTransport
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.DownloadedTracks
import org.moire.ultrasonic.util.HomePrefs
import org.moire.ultrasonic.util.LyricsLoader
import org.moire.ultrasonic.util.NowPlayingHint
import org.moire.ultrasonic.util.PinnedPlaylist
import org.moire.ultrasonic.util.PlaylistResumeStore
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
    private var exitFullscreenButton: ImageButton? = null
    private var upNextContainer: View? = null
    private var upNextFirst: TextView? = null
    private var upNextSecond: TextView? = null
    private var quickTools: View? = null
    private var quickTheme: View? = null
    private var quickPlaylist: View? = null
    private var quickQueue: View? = null
    private var quickFontMinus: View? = null
    private var quickFontPlus: View? = null
    private var lastUpNextKey: String? = null
    private var backdropView: ImageView? = null
    private var backdropScrim: View? = null
    private var lastBackdropTrackId: String? = null

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
        exitFullscreenButton = view.findViewById(R.id.pane_fullscreen_exit)
        upNextContainer = view.findViewById(R.id.pane_up_next)
        upNextFirst = view.findViewById(R.id.pane_up_next_first)
        upNextSecond = view.findViewById(R.id.pane_up_next_second)
        quickTools = view.findViewById(R.id.pane_quick_tools)
        quickTheme = view.findViewById(R.id.quick_theme)
        quickPlaylist = view.findViewById(R.id.quick_playlist)
        quickQueue = view.findViewById(R.id.quick_queue)
        quickFontMinus = view.findViewById(R.id.quick_font_minus)
        quickFontPlus = view.findViewById(R.id.quick_font_plus)
        backdropView = view.findViewById(R.id.pane_backdrop)
        backdropScrim = view.findViewById(R.id.pane_backdrop_scrim)

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
        exitFullscreenButton?.setOnClickListener {
            (activity as? NavigationActivity)?.togglePlayerFullscreen()
        }
        quickTheme?.setOnClickListener { cycleTheme() }
        quickPlaylist?.setOnClickListener { showPlaylistQuickPick() }
        quickQueue?.setOnClickListener { showQueueQuickPick() }
        quickFontMinus?.setOnClickListener { adjustFontSize(-FONT_STEP_SP) }
        quickFontPlus?.setOnClickListener { adjustFontSize(FONT_STEP_SP) }

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
        applyFullscreenSurfaces(ctx)
    }

    /**
     * Fullscreen makes the current cover the background of the whole pane.
     *
     * The scrim is rebuilt from the active palette instead of being a fixed black overlay:
     * its top and bottom use the theme background colours, the middle is the theme surface
     * pulled towards the theme accent. Ocean, Aurora, Violet, Bordeaux and Amber therefore
     * each cast their own colour over the art, so the photo and the controls read as one UI.
     * The cards in the middle go translucent and the lyrics get a dark shadow, which keeps
     * white text readable without hiding the artwork.
     */
    private fun applyFullscreenSurfaces(ctx: Context) {
        val palette = CarPalette.current()
        val accent = CarTheme.accent(ctx, palette)
        val surface = CarTheme.surfaceColor(ctx, palette)
        val full = UiState.playerFullscreen

        headerCard?.background = if (full) {
            CarTheme.roundedFill(
                ctx,
                CarTheme.withAlpha(surface, 0x9E),
                CarTheme.withAlpha(accent, 0x3D),
                16f
            )
        } else {
            CarTheme.cardBackground(ctx, palette, cornerDp = 16f)
        }

        stageCard?.background = if (full) {
            CarTheme.roundedFill(
                ctx,
                CarTheme.withAlpha(surface, 0x44),
                CarTheme.withAlpha(accent, 0x33),
                16f
            )
        } else {
            CarTheme.roundedFill(
                ctx,
                CarTheme.withAlpha(surface, 0xB8),
                CarTheme.withAlpha(accent, 0x26),
                16f
            )
        }

        lyricsView?.setShadowLayer(
            if (full) LYRICS_SHADOW_RADIUS else 0f,
            0f,
            if (full) LYRICS_SHADOW_DY else 0f,
            if (full) LYRICS_SHADOW_COLOR else Color.TRANSPARENT
        )

        backdropView?.visibility = if (full) View.VISIBLE else View.GONE
        backdropScrim?.visibility = if (full) View.VISIBLE else View.GONE
        if (full) {
            applyBackdropScrim(ctx, palette)
            loadBackdrop(currentTrack)
        }
    }

    /** Theme-tinted mask over the cover: dark at the header and controls, lighter in the middle. */
    private fun applyBackdropScrim(ctx: Context, palette: CarPalette) {
        val top = CarTheme.tone(ctx, ContextCompat.getColor(ctx, palette.bgTop))
        val bottom = CarTheme.tone(ctx, ContextCompat.getColor(ctx, palette.bgBottom))
        val surface = CarTheme.surfaceColor(ctx, palette)
        val accent = CarTheme.accent(ctx, palette)
        val tintedMiddle = CarTheme.mix(surface, accent, SCRIM_ACCENT_RATIO)
        backdropScrim?.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                CarTheme.withAlpha(top, 0xD8),
                CarTheme.withAlpha(tintedMiddle, 0x8C),
                CarTheme.withAlpha(bottom, 0xE8)
            )
        )
    }

    /** Loads the current cover at a size that still looks clean when cropped to the full pane. */
    private fun loadBackdrop(track: Track?, force: Boolean = false) {
        if (!UiState.playerFullscreen) return
        val id = track?.id
        if (!force && id == lastBackdropTrackId) return
        lastBackdropTrackId = id
        val target = backdropView ?: return
        if (track == null) {
            target.setImageResource(R.drawable.car_backdrop_placeholder)
            return
        }
        imageLoaderProvider.executeOn { loader ->
            if (isAdded) {
                loader.loadImage(
                    target, track, false, BACKDROP_SIZE_PX,
                    R.drawable.car_backdrop_placeholder
                )
            }
        }
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

    /**
     * Fullscreen chrome: enter on the right, exit on the left, the two upcoming tracks on the
     * right of the title and the quick tools row. Exactly one of the two buttons is visible.
     */
    private fun syncFullscreenButton() {
        val full = UiState.playerFullscreen
        fullscreenButton?.let { button ->
            button.visibility =
                if (!full && Settings.showPlayerFullscreenButton) View.VISIBLE else View.GONE
            button.setImageResource(R.drawable.ic_fullscreen_enter)
            context?.let { ctx ->
                button.contentDescription = ctx.getString(R.string.player_fullscreen_enter)
            }
            applyToggleTint(button, false)
        }
        exitFullscreenButton?.visibility = if (full) View.VISIBLE else View.GONE
        upNextContainer?.visibility = if (full) View.VISIBLE else View.GONE
        quickTools?.visibility = if (full) View.VISIBLE else View.GONE
        if (full) renderUpNext(force = true) else lastUpNextKey = null
        context?.let { applyFullscreenSurfaces(it) }
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
        exitFullscreenButton = null
        upNextContainer = null
        upNextFirst = null
        upNextSecond = null
        quickTools = null
        quickTheme = null
        quickPlaylist = null
        quickQueue = null
        quickFontMinus = null
        quickFontPlus = null
        backdropView = null
        backdropScrim = null
        lastBackdropTrackId = null
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
        renderUpNext()

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
        loadBackdrop(track, force = true)
    }

    /**
     * The title area shows the current song, except for two short windows where it announces
     * the next one: 10-20 s after the start and the last 15 s. Outside those windows the
     * current song comes back. Both are pure presentation; playback is untouched.
     */
    private fun renderHeader(positionMs: Int, durationMs: Int, force: Boolean = false) {
        val track = currentTrack
        // In fullscreen the two upcoming tracks are always visible on the right, so the title
        // itself stays on the current song instead of being replaced for a few seconds.
        val hint = if (!UiState.playerFullscreen && track != null && Settings.showNextTrackHint &&
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

    // region fullscreen quick tools

    /**
     * Fullscreen only: keeps the next two tracks in play order visible at the right of the
     * title. The identity key avoids resetting the TextViews twice a second.
     */
    private fun renderUpNext(force: Boolean = false) {
        if (!UiState.playerFullscreen) return
        val tracks = runCatching {
            NowPlayingHint.upNextTracks(mediaPlayerManager, UP_NEXT_COUNT)
        }.getOrNull().orEmpty()
        val key = tracks.joinToString("|") { it.id } + "#" + mediaPlayerManager.repeatMode
        if (!force && key == lastUpNextKey) return
        lastUpNextKey = key
        val ctx = context ?: return
        val first = tracks.getOrNull(0)
        val second = tracks.getOrNull(1)
        upNextFirst?.text = if (first != null) {
            ctx.getString(R.string.pane_up_next_first, upNextLabel(first))
        } else {
            ctx.getString(R.string.pane_up_next_empty)
        }
        upNextSecond?.text = if (second != null) {
            ctx.getString(R.string.pane_up_next_second, upNextLabel(second))
        } else {
            ""
        }
        upNextSecond?.visibility = if (second != null) View.VISIBLE else View.GONE
    }

    private fun upNextLabel(track: Track): String =
        listOfNotNull(track.title, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { track.title.orEmpty() }

    /** One tap walks through the six car color schemes; the activity rebuilds its window. */
    private fun cycleTheme() {
        val all = CarPalette.values()
        val next = all[(all.indexOf(CarPalette.current()) + 1) % all.size]
        Settings.palette = next.key
        // Repaint the scrim before the activity recreates, so the tint change is visible at once.
        context?.let { decoratePane() }
        Util.toast(
            getString(R.string.quick_theme_changed, getString(next.labelRes)),
            context = requireContext()
        )
        RxBus.themeChangedEventPublisher.onNext(Unit)
    }

    /** Steps the base lyrics font size; the appearance signature redraws without a download. */
    private fun adjustFontSize(delta: Int) {
        val current = Settings.lyricsFontSizeSp
        val next = (current + delta).coerceIn(FONT_MIN_SP, FONT_MAX_SP)
        if (next == current) {
            Util.toast(
                getString(R.string.quick_font_limit, effectiveFontSp()),
                context = requireContext()
            )
            return
        }
        Settings.lyricsFontSizeSp = next
        applyLyricsAppearance()
        Util.toast(
            getString(R.string.quick_font_changed, effectiveFontSp()),
            context = requireContext()
        )
    }

    /**
     * Server playlists as a single-tap dialog. A warm cache opens immediately; a background
     * refresh keeps the next opening current without making the driver wait for the network.
     */
    private fun showPlaylistQuickPick() {
        val cached = HomePrefs.getCachedPlaylists()
        if (cached.isNotEmpty()) {
            showPlaylistDialog(cached)
            refreshPlaylistCache()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = fetchPlaylists()
            if (!isAdded) return@launch
            if (loaded.isEmpty()) {
                Util.toast(getString(R.string.quick_playlist_empty), context = requireContext())
            } else {
                showPlaylistDialog(loaded)
            }
        }
    }

    /** Reads the playlists on IO with a hard deadline and caches a non-empty answer. */
    private suspend fun fetchPlaylists(): List<PinnedPlaylist> = withContext(Dispatchers.IO) {
        val loaded = withTimeoutOrNull(PLAYLIST_FETCH_TIMEOUT_MS) {
            runCatching {
                MusicServiceFactory.getMusicService().getPlaylists(false)
                    .map { PinnedPlaylist(it.id, it.name, it.coverArt) }
            }.getOrNull().orEmpty()
        }.orEmpty()
        if (loaded.isNotEmpty()) {
            HomePrefs.setCachedPlaylists(loaded)
            HomePrefs.setPlaylistFetchTime(System.currentTimeMillis())
        }
        loaded
    }

    private fun refreshPlaylistCache() {
        val age = System.currentTimeMillis() - HomePrefs.getPlaylistFetchTime()
        if (age < PLAYLIST_FETCH_TTL_MS) return
        viewLifecycleOwner.lifecycleScope.launch { fetchPlaylists() }
    }

    private fun showPlaylistDialog(list: List<PinnedPlaylist>) {
        val ctx = context ?: return
        val labels = list.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(ctx, R.layout.item_quick_pick, android.R.id.text1, labels)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.quick_playlist_title)
            .setSingleChoiceItems(adapter, -1) { dialog, which ->
                dialog.dismiss()
                list.getOrNull(which)?.let { playPickedPlaylist(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Starts the chosen playlist, continuing at its saved position when one exists. */
    @Suppress("TooGenericExceptionCaught")
    private fun playPickedPlaylist(playlist: PinnedPlaylist) {
        HomePrefs.incrementPlayCount(playlist.id)
        val progress = PlaylistResumeStore.get(playlist.id)
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                val remote = withTimeoutOrNull(PLAYLIST_FETCH_TIMEOUT_MS) {
                    try {
                        DownloadUtil.getTracksFromServerAsync(
                            isArtist = false,
                            id = playlist.id,
                            isDirectory = false,
                            name = playlist.name,
                            isShare = false
                        )
                    } catch (all: Exception) {
                        Timber.w(all, "Could not load playlist %s", playlist.id)
                        emptyList<Track>()
                    }
                }.orEmpty()
                remote.ifEmpty {
                    runCatching {
                        DownloadedTracks.scan()
                            .filter { it.state != DownloadedTracks.State.PARTIAL }
                            .map { it.track }
                    }.getOrNull().orEmpty()
                }
            }
            if (!isAdded) return@launch
            if (songs.isEmpty()) {
                Util.toast(getString(R.string.quick_playlist_empty), context = requireContext())
                return@launch
            }
            mediaPlayerManager.resumePlaylistAt(
                id = playlist.id,
                name = playlist.name,
                songs = songs,
                index = progress?.index ?: 0,
                position = progress?.position ?: 0
            )
            Util.toast(
                getString(R.string.home_playlist_playing, playlist.name),
                context = requireContext()
            )
        }
    }

    /**
     * The play queue as a dialog, with the currently playing song pre-selected and scrolled
     * into view. Picking a row jumps straight to it, including the shuffle index translation.
     */
    private fun showQueueQuickPick() {
        val ctx = context ?: return
        val tracks = runCatching {
            mediaPlayerManager.playlistInPlayOrder.map { it.toTrack() }
        }.getOrNull().orEmpty()
        if (tracks.isEmpty()) {
            Util.toast(getString(R.string.quick_queue_empty), context = requireContext())
            return
        }
        val current = runCatching {
            mediaPlayerManager.getCurrentShuffleIndex()
        }.getOrDefault(-1)
        val labels = tracks.mapIndexed { index, track ->
            val detail = upNextLabel(track)
            if (index == current) {
                getString(R.string.quick_queue_current, detail)
            } else {
                detail
            }
        }.toTypedArray()
        val adapter = ArrayAdapter(ctx, R.layout.item_quick_pick, android.R.id.text1, labels)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.quick_queue_title)
            .setSingleChoiceItems(adapter, current) { picker, which ->
                picker.dismiss()
                playFromQueue(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        if (current > 0) dialog.listView?.setSelection(current)
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
        private const val FONT_STEP_SP = 2
        private const val FONT_MIN_SP = 12
        private const val FONT_MAX_SP = 36
        private const val PLAYLIST_FETCH_TIMEOUT_MS = 8_000L
        private const val PLAYLIST_FETCH_TTL_MS = 10 * 60 * 1000L
        private const val UP_NEXT_COUNT = 2
        private const val BACKDROP_SIZE_PX = 800
        private const val SCRIM_ACCENT_RATIO = 0.22f
        private const val LYRICS_SHADOW_RADIUS = 9f
        private const val LYRICS_SHADOW_DY = 2f
        private const val LYRICS_SHADOW_COLOR = 0xCC000000.toInt()
    }
}
