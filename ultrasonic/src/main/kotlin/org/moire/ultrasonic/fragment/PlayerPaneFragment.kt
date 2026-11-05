/*
 * PlayerPaneFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.QueueAdapter
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.service.plusAssign
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.ui.SyncedLyricsView
import org.moire.ultrasonic.util.CarTransport
import org.moire.ultrasonic.util.LyricsParser
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.SleepTimerManager
import org.moire.ultrasonic.util.TimedLyric
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
class PlayerPaneFragment : ScopeFragment(), SleepTimerManager.Listener {

    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val sleepTimerManager: SleepTimerManager by inject()

    private var lyricsView: TextView? = null
    private var lyricsScroll: View? = null
    private var offsetBar: View? = null
    private var offsetLabel: TextView? = null
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

        offsetBar = view.findViewById(R.id.pane_lyric_offset)
        offsetLabel = view.findViewById(R.id.pane_offset_label)
        view.findViewById<View>(R.id.pane_offset_back).setOnClickListener {
            adjustLyricOffset(+LYRIC_OFFSET_STEP_MS)
        }
        view.findViewById<View>(R.id.pane_offset_forward).setOnClickListener {
            adjustLyricOffset(-LYRIC_OFFSET_STEP_MS)
        }

        lyricsView?.let { lyrics ->
            syncedLyrics = SyncedLyricsView(lyricsScroll, lyrics).apply {
                setAccentColor(CarTheme.color(requireContext(), CarPalette.current().accent))
                setFontSizeSp(Settings.lyricsFontSizeSp)
            }
        }

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

        // Sound enhancement panel. The pane is hosted outside the NavHost, so navigation has
        // to go through the activity's NavController.
        view.findViewById<ImageButton>(R.id.pane_effects)?.setOnClickListener {
            try {
                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
                    .navigate(R.id.action_global_soundEffects)
            } catch (all: Throwable) {
                Timber.w(all, "Could not open the sound-effects panel.")
            }
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

        // The driver has to know that the music waits for the server. A panel that just
        // sits there looks like a frozen app.
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

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
        sleepTimerManager.addListener(this)
        // The lyrics font size is a global setting; re-apply it so a change made in the settings
        // (while this pane was in the background) is reflected without restarting the player.
        syncedLyrics?.setFontSizeSp(Settings.lyricsFontSizeSp)
    }

    override fun onPause() {
        handler.removeCallbacks(updateRunnable)
        sleepTimerManager.removeListener(this)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        disposables.clear()
        super.onDestroyView()
    }

    private fun updateState() {
        if (!isAdded) return

        val track = mediaPlayerManager.currentMediaItem?.toTrack()

        if (!stateInitialized || track?.id != currentTrack?.id) {
            stateInitialized = true
            currentTrack = track
            showTrack(track)
        }

        val duration = mediaPlayerManager.playerDuration
        val position = mediaPlayerManager.playerPosition

        // Keep the lyrics in sync with the playback position, shifted by the calibration offset
        syncedLyrics?.update(lyricSearchPosition())

        // The calibration bar only makes sense when the lyrics carry a time axis
        offsetBar?.visibility = if (syncedLyrics?.hasTimedLyrics == true) View.VISIBLE else View.GONE
        updateOffsetLabel()

        if (!seekBarTracking) {
            seekBar?.max = if (duration > 0) duration else 1
            seekBar?.progress = position.coerceAtLeast(0)
            elapsedView?.text = formatTime(position)
        }
        totalView?.text = formatTime(duration)

        playButton?.setImageResource(
            if (mediaPlayerManager.isPlaying) R.drawable.media_pause else R.drawable.media_start
        )
        updateStatus()

        // The song that is playing moves through the queue, so the marker has to follow
        // it even when the queue itself did not change.
        if (showQueue) refreshQueue()

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

        updateRepeatButton()
    }

    private fun showTrack(track: Track?) {
        // The cover, title and artist are shown in the left list, so the panel only carries the
        // lyrics. An empty pane still says what to do, though.
        loadLyrics(track)
    }

    private fun loadLyrics(track: Track?) {
        val synced = syncedLyrics ?: return

        if (track == null) {
            synced.showPlaceholder(getString(R.string.player_pane_idle_hint))
            currentLyricsTrackId = null
            return
        }

        if (currentLyricsTrackId == track.id) return
        currentLyricsTrackId = track.id
        synced.reset()

        synced.showPlaceholder(getString(R.string.player_pane_lyrics_loading))

        val artist = track.artist.orEmpty()
        val title = track.title.orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val lyrics = withContext(Dispatchers.IO) {
                try {
                    // The endpoint with the time axis is asked first, because the plain one drops
                    // every timestamp and the lyrics would not move along with the song.
                    val service = MusicServiceFactory.getMusicService()
                    runCatching { service.getSyncedLyrics(track.id) }.getOrNull()
                        ?.takeIf { it.timedLines.isNotEmpty() }
                        ?: service.getLyrics(artist, title)
                } catch (all: Exception) {
                    Timber.w(all, "Could not load lyrics for %s", title)
                    null
                }
            }

            if (!isAdded || currentLyricsTrackId != track.id) return@launch

            val text = lyrics?.text
            val timed = lyrics?.timedLines?.map { TimedLyric(it.startMs, it.text) }
                .orEmpty()
            if (text.isNullOrBlank() && timed.isEmpty()) {
                synced.showPlaceholder(getString(R.string.player_pane_lyrics_empty))
            } else {
                // A song without a time axis still gets one: the lines are spread over
                // the length of the song, so the lyrics move along with the music.
                val effective = if (timed.isEmpty()) {
                    LyricsParser.spreadPlainLyrics(
                        text,
                        mediaPlayerManager.playerDuration.toLong()
                    )
                } else {
                    timed
                }
                synced.setLyrics(text, effective)
                synced.update(mediaPlayerManager.playerPosition.toLong())
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
        queueButton?.alpha = if (showQueue) ALPHA_ACTIVE else ALPHA_INACTIVE
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
        view.text = message
        view.visibility = View.VISIBLE
        view.setTextColor(
            when (state) {
                CarTransport.VisualState.ERROR, CarTransport.VisualState.WAITING_FOR_NETWORK ->
                    STATUS_COLOR_ERROR
                else -> STATUS_COLOR_WARNING
            }
        )
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

    // region lyric calibration

    /**
     * The position used to highlight the lyrics, shifted by the user calibration.
     *
     * A positive offset delays the lyrics (they appear later), a negative one shows them earlier.
     * Many servers return timestamps that run ahead of or behind the audio, so the driver nudges
     * the whole block until the words line up with what is heard.
     */
    private fun lyricSearchPosition(): Long {
        return (mediaPlayerManager.playerPosition.toLong() - Settings.lyricsOffsetMs).coerceAtLeast(0L)
    }

    /** Changes the calibration by [deltaMs] and refreshes the lyrics and the label right away. */
    private fun adjustLyricOffset(deltaMs: Int) {
        Settings.lyricsOffsetMs = (Settings.lyricsOffsetMs + deltaMs).coerceIn(
            -LYRIC_OFFSET_LIMIT_MS,
            LYRIC_OFFSET_LIMIT_MS
        )
        updateOffsetLabel()
        syncedLyrics?.update(lyricSearchPosition())
    }

    /** Shows the current calibration as a signed number of seconds on the bar. */
    private fun updateOffsetLabel() {
        val seconds = Settings.lyricsOffsetMs / 1000.0
        offsetLabel?.text = getString(R.string.lyric_offset_label, String.format("%.1f", seconds))
    }

    // endregion

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
        // The visible cue is the tint; the remaining time goes into the content description so
        // it is spoken and shown on long-press without adding a moving number to the bar.
        val minutes = (remainingMillis / 60_000L).toInt().coerceAtLeast(1)
        sleepButton?.contentDescription = getString(R.string.sleep_timer_minutes, minutes)
    }

    override fun onSleepTimerFired() {
        // Stop the music but keep the queue and the position, so the driver can resume in the
        // morning exactly where the car left off.
        mediaPlayerManager.pause()
    }

    // endregion

    companion object {
        private const val UPDATE_INTERVAL_MS = 500L
        private const val ALPHA_DISABLED = 0.35f
        private const val STATUS_COLOR_WARNING = 0xFFFFC107.toInt()
        private const val STATUS_COLOR_ERROR = 0xFFFF534D.toInt()
        private const val ALPHA_ACTIVE = 1.0f
        private const val ALPHA_INACTIVE = 0.4f
        private const val SEEK_STEP_MS = 15_000

        /** One tap on the calibration bar shifts the lyrics by this many milliseconds. */
        private const val LYRIC_OFFSET_STEP_MS = 500

        /** The calibration cannot drift further than this from the real position. */
        private const val LYRIC_OFFSET_LIMIT_MS = 20_000
        private const val REPEAT_OFF = 0
        private const val REPEAT_ALL = 1
        private const val REPEAT_ONE = 2
    }
}
