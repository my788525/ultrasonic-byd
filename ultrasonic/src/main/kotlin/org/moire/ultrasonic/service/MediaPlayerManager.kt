/*
 * MediaPlayerController.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.annotation.IntRange
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider.Companion.OFFLINE_DB_ID
import org.moire.ultrasonic.data.RatingUpdate
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.PlaylistResumeStore
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.UiState
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.Util.navigateToCurrent
import org.moire.ultrasonic.util.Util.toast
import org.moire.ultrasonic.util.launchWithToast
import org.moire.ultrasonic.util.toMediaItem
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

private const val CONTROLLER_SWITCH_DELAY = 500L
private const val VOLUME_DELTA = 0.05f

/** How many times a transient playback error is retried before it is shown as a hard failure. */
private const val MAX_PLAYBACK_ERROR_RETRIES = 3

/** Base backoff between auto-retries; it grows with the attempt number (1x, 2x, 3x). */
private const val PLAYBACK_ERROR_RETRY_DELAY_MS = 1500L

/** How often the active playlist's resume position is persisted while playing. */
private const val RESUME_SAVE_INTERVAL_MS = 5000L

/**
 * The Media Player Manager can forward commands to the Media3 controller as
 * well as switch between different player interfaces (local, remote, cast etc).
 * This class contains everything that is necessary for the Application UI
 * to control the Media Player implementation.
 */
@Suppress("TooManyFunctions")
class MediaPlayerManager(
    private val playbackStateSerializer: PlaybackStateSerializer,
    private val externalStorageMonitor: ExternalStorageMonitor
) : KoinComponent {

    private var created = false
    var suggestedPlaylistName: String? = null
    var keepScreenOn = false
    private var autoPlayStart = false

    /**
     * The id of the server playlist that the current queue was built from, or null when the
     * queue is not a playlist (an album, a folder, random songs, ...). It lets us remember the
     * last played position of each playlist so a pinned "frequently used" playlist on the home
     * screen can be resumed where the driver left off.
     */
    var currentPlaylistId: String? = null
        private set
    private var currentPlaylistName: String? = null

    /**
     * True while the current player queue is the playlist identified by [currentPlaylistId].
     * It is cleared whenever the queue is replaced by something that is not that playlist
     * (shuffle-all, an album, a folder, ...), so the periodic resume saver never writes the
     * position of the wrong queue under a playlist's id.
     */
    private var playlistBinding = false

    /**
     * Counts the automatic retries of a failing song. It is reset as soon as the music plays
     * again or the track changes, so a permanently broken file is only retried a few times and
     * then left to the driver, while a song that recovers mid-buffer heals by itself.
     */
    private var playbackErrorRetries = 0

    /** Slow timer that keeps the active playlist's resume position fresh while playing. */
    private val resumeHandler = Handler(Looper.getMainLooper())
    private val resumeRunnable = object : Runnable {
        override fun run() {
            savePlaylistResume()
            resumeHandler.postDelayed(this, RESUME_SAVE_INTERVAL_MS)
        }
    }

    private val scrobbler = Scrobbler()

    private val rxBusSubscription: CompositeDisposable = CompositeDisposable()

    private var mainScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var sessionToken = SessionToken(
        UApp.applicationContext(),
        ComponentName(UApp.applicationContext(), PlaybackService::class.java)
    )

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null

    private var controller: Player? = null

    private var listeners: Player.Listener = object : Player.Listener {

        /*
         * Log all events
         */
        override fun onEvents(player: Player, events: Player.Events) {
            for (i in 0 until events.size()) {
                Timber.i("Media3 Event, event type: %s", events[i])
            }
        }

        /*
         * This will be called everytime the playlist has changed.
         * We run the event through RxBus in order to throttle them
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val start = timeline.getFirstWindowIndex(isShufflePlayEnabled)
            Timber.w("On timeline changed. First shuffle play at index: %s", start)
            deferredPlay?.let {
                Timber.w("Executing deferred shuffle play")
                it()
                deferredPlay = null
            }
            val playlist = Util.getPlayListFromTimeline(timeline, false).map(MediaItem::toTrack)
            RxBus.playlistPublisher.onNext(playlist)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            playerStateChangedHandler()
            publishPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A song that actually plays again means the retries (if any) worked, so the counter
            // must not keep growing on the next hiccup of a different track.
            if (isPlaying) playbackErrorRetries = 0
            playerStateChangedHandler()
            publishPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new song starts a fresh error budget; the previous one does not carry over.
            playbackErrorRetries = 0
            clearBookmark()
            // TRANSITION_REASON_AUTO means that the previous track finished playing and a new one has started.
            if (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO && cachedMediaItem != null) {
                scrobbler.scrobble(cachedMediaItem?.toTrack(), true)
            }
            cachedMediaItem = mediaItem
            // A playlist changes track: remember where we are so it can be resumed later.
            savePlaylistResume()
            publishPlaybackState()
        }

        /*
         * If the same item is contained in a playlist multiple times directly after each
         * other, Media3 on emits a PositionDiscontinuity event.
         * Can be removed if https://github.com/androidx/media/issues/68 is fixed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            playerStateChangedHandler()
            publishPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error.toString())
            // The error is published for every kind of playback. The playback service needs it
            // to start the music again once the network is back, and the interface needs it to
            // show that the playback is waiting.
            RxBus.playerErrorPublisher.onNext(error)

            // A local song that fails to buffer can often be restarted by hand, so it is retried
            // automatically. Re-preparing the current item after a short, growing backoff lets the
            // music resume by itself instead of getting stuck on the error banner. Only transient
            // network/IO failures are retried, and only a few times, so a truly broken file is
            // left to the driver.
            if (!isJukeboxEnabled && controller?.playWhenReady == true &&
                shouldAutoRetry(error)
            ) {
                if (playbackErrorRetries < MAX_PLAYBACK_ERROR_RETRIES) {
                    playbackErrorRetries++
                    val attempt = playbackErrorRetries
                    mainScope.launch {
                        delay(PLAYBACK_ERROR_RETRY_DELAY_MS * attempt)
                        // Bail out if the driver paused or the error was cleared while we waited.
                        if (controller?.playWhenReady == true && controller?.playerError != null) {
                            Timber.w(
                                "Auto-retrying playback after error (attempt %d/%d)",
                                attempt, MAX_PLAYBACK_ERROR_RETRIES
                            )
                            controller?.prepare()
                            controller?.play()
                        }
                    }
                } else {
                    // Retries exhausted on a poor network: try falling back to the next cached
                    // song in the current queue so the music keeps playing in tunnels.
                    skipToNextCachedTrackIfAvailable()
                }
            }

            if (!isJukeboxEnabled) return

            mainScope.launch {
                toast(
                    error.errorCode,
                    false,
                    UApp.applicationContext()
                )
            }
            isJukeboxEnabled = false
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val timeline: Timeline = controller?.currentTimeline ?: return
            var windowIndex = timeline.getFirstWindowIndex(true)
            var count = 0
            Timber.d("Shuffle: windowIndex: $windowIndex, at: $count")
            while (windowIndex != C.INDEX_UNSET) {
                count++
                windowIndex = timeline.getNextWindowIndex(
                    windowIndex, REPEAT_MODE_OFF, true
                )
                Timber.d("Shuffle: windowIndex: $windowIndex, at: $count")
            }
        }
    }

    /**
     * When a streaming track fails after all automatic retries during a network drop, checks if
     * another song in the current queue is already cached on disk and skips to it so playback
     * continues without driver intervention.
     */
    private fun skipToNextCachedTrackIfAvailable() {
        val currentController = controller ?: return
        val timeline = currentController.currentTimeline
        val startIndex = currentMediaItemIndex
        if (timeline.isEmpty || startIndex < 0) return

        val candidateIndices = mutableListOf< Pair<Int, Track> >()
        var cursor = startIndex
        val visited = HashSet<Int>()
        while (visited.add(cursor)) {
            val next = timeline.getNextWindowIndex(
                cursor,
                currentController.repeatMode,
                currentController.shuffleModeEnabled
            )
            if (next == C.INDEX_UNSET || next == startIndex || visited.contains(next)) break
            val item = currentController.getMediaItemAt(next)
            candidateIndices.add(next to item.toTrack())
            cursor = next
        }
        if (candidateIndices.isEmpty()) return

        mainScope.launch {
            val cachedIndex = withContext(Dispatchers.IO) {
                candidateIndices.firstOrNull { (_, track) ->
                    val state = DownloadService.getDownloadState(track)
                    state == DownloadState.DONE || state == DownloadState.PINNED
                }?.first
            }
            if (cachedIndex != null &&
                controller?.playWhenReady == true &&
                controller?.playerError != null
            ) {
                Timber.i("Network unreachable; skipping to cached track at index %d", cachedIndex)
                playbackErrorRetries = 0
                controller?.seekTo(cachedIndex, 0L)
                controller?.prepare()
                controller?.play()
                toast(R.string.car_fallback_playing_cached, true, UApp.applicationContext())
            }
        }
    }

    /**
     * True for the kind of playback errors that are worth retrying automatically: a dropped
     * network, a timeout while buffering, or a generic IO failure. Permanent problems such as an
     * unsupported format or a missing decoder are not retried, because a second attempt would fail
     * the same way and only delay the error the driver has to act on.
     */
    private fun shouldAutoRetry(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            else -> false
        }
    }

    private var deferredPlay: (() -> Unit)? = null

    private var cachedMediaItem: MediaItem? = null

    fun onCreate(onCreated: () -> Unit) {
        if (created) return
        externalStorageMonitor.onCreate { reset() }

        createMediaController(onCreated)

        rxBusSubscription += RxBus.activeServerChangingObservable
            // All interaction with the Media3 needs to happen on the main thread
            .subscribeOn(RxBus.mainThread())
            .subscribe { oldServer ->
                if (oldServer != OFFLINE_DB_ID) {
                    // When the server changes, the playlist can retain the downloaded songs.
                    // Incomplete songs should be removed as the new server won't recognise them.
                    removeIncompleteTracksFromPlaylist()
                    DownloadService.requestStop()
                }
                if (controller is JukeboxMediaPlayer) {
                    // When the server changes, the Jukebox should be released.
                    // The new server won't understand the jukebox requests of the old one.
                    switchToLocalPlayer()
                }
            }

        rxBusSubscription += RxBus.activeServerChangedObservable.subscribe {
            val jukebox = it.jukeboxByDefault
            // Remove all songs when changing servers before turning on Jukebox.
            // Jukebox wouldn't find the songs on the new server.
            if (jukebox) controller?.clearMediaItems()
            // Update the Jukebox state as the new server requires
            isJukeboxEnabled = jukebox
        }

        rxBusSubscription += RxBus.throttledPlaylistObservable
            // All interaction with the Media3 needs to happen on the main thread
            .subscribeOn(RxBus.mainThread())
            .subscribe {
                serializeCurrentSession()
            }

        rxBusSubscription += RxBus.throttledPlayerStateObservable
            // All interaction with the Media3 needs to happen on the main thread
            .subscribeOn(RxBus.mainThread())
            .subscribe {
                serializeCurrentSession()
            }

        rxBusSubscription += RxBus.shutdownCommandObservable.subscribe {
            clear(false)
            onDestroy()
        }

        rxBusSubscription += RxBus.stopServiceCommandObservable.subscribe {
            clear(false)
            onDestroy()
        }

        rxBusSubscription += RxBus.ratingSubmitterObservable.subscribe {
            // Ensure correct thread
            mainScope.launch {
                // This deals only with the current track!
                if (it.id != currentMediaItem?.toTrack()?.id) return@launch
                setRating(it.rating)
            }
        }

        created = true
        resumeHandler.postDelayed(resumeRunnable, RESUME_SAVE_INTERVAL_MS)
        Timber.i("MediaPlayerController started")
    }

    private fun createMediaController(onCreated: () -> Unit) {
        mediaControllerFuture = MediaController.Builder(
            UApp.applicationContext(),
            sessionToken
        )
            // Specify mainThread explicitly
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()

        mediaControllerFuture?.addListener({
            controller = mediaControllerFuture?.get()

            Timber.i("MediaController Instance received")
            controller?.addListener(listeners)
            onCreated()
            Timber.i("MediaPlayerController creation complete")
        }, MoreExecutors.directExecutor())
    }

    private fun playerStateChangedHandler() {
        val currentPlaying = controller?.currentMediaItem?.toTrack() ?: return

        when (playbackState) {
            Player.STATE_READY -> {
                if (isPlaying) {
                    scrobbler.scrobble(currentPlaying, false)
                }
            }
            // STATE_ENDED is only signaled if the whole playlist completes. Scrobble the last song.
            Player.STATE_ENDED -> {
                scrobbler.scrobble(currentPlaying, true)
            }
        }
    }

    fun addListener(listener: Player.Listener) {
        controller?.addListener(listener)
    }

    private fun clearBookmark() {
        // This method is called just before we update the cachedMediaItem,
        // so in fact cachedMediaItem will refer to the track that has just finished.
        val previousItem = cachedMediaItem ?: return
        val song = previousItem.toTrack()
        if (song.bookmarkPosition > 0 && Settings.shouldClearBookmark) {
            val songId = song.id
            ioScope.launch {
                try {
                    getMusicService().deleteBookmark(songId)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun publishPlaybackState() {
        val newState = RxBus.StateWithTrack(
            track = currentMediaItem?.toTrack(),
            index = if (isShufflePlayEnabled) getCurrentShuffleIndex() else currentMediaItemIndex,
            isPlaying = isPlaying,
            state = playbackState
        )
        RxBus.playerStatePublisher.onNext(newState)
        Timber.i("New PlaybackState: %s", newState)
    }

    fun onDestroy() {
        if (!created) return

        // First stop listening to events
        rxBusSubscription.dispose()
        releaseController()

        // Shutdown the rest
        externalStorageMonitor.onDestroy()
        DownloadService.requestStop()
        created = false
        Timber.i("MediaPlayerManager destroyed")
    }

    @Synchronized
    fun restore(
        state: PlaybackState,
        autoPlay: Boolean
    ) {
        val insertionMode = InsertionMode.APPEND

        addToPlaylist(
            state.songs,
            autoPlay = false,
            shuffle = false,
            insertionMode = insertionMode
        )

        repeatMode = state.repeatMode
        isShufflePlayEnabled = state.shufflePlay

        if (state.currentPlayingIndex != -1) {
            seekTo(state.currentPlayingIndex, state.currentPlayingPosition)
            prepare()

            if (autoPlay) {
                play()
            }

            autoPlayStart = false
        }
    }

    @Synchronized
    fun play(index: Int) {
        playbackErrorRetries = 0
        controller?.seekTo(index, 0L)
        controller?.prepare()
        controller?.play()
    }

    @Synchronized
    fun play() {
        playbackErrorRetries = 0
        controller?.prepare()
        controller?.play()
    }

    @Synchronized
    fun prepare() {
        controller?.prepare()
    }

    @Synchronized
    fun resumeOrPlay() {
        controller?.play()
    }

    @Synchronized
    fun togglePlayPause() {
        if (playbackState == Player.STATE_IDLE) autoPlayStart = true
        if (controller?.isPlaying == true) {
            controller?.pause()
        } else {
            controller?.play()
        }
    }

    @Synchronized
    fun seekTo(position: Int) {
        if (controller?.currentTimeline?.isEmpty != false) return
        Timber.i("SeekTo: %s", position)
        controller?.seekTo(position.toLong())
    }

    @Synchronized
    fun seekTo(index: Int, position: Int) {
        // This case would throw an exception in Media3. It can happen when an inconsistent state is saved.
        if (controller?.currentTimeline?.isEmpty != false ||
            index >= controller!!.currentTimeline.windowCount
        ) return

        Timber.i("SeekTo: %s %s", index, position)
        controller?.seekTo(index, position.toLong())
    }

    fun seekForward() {
        controller?.seekForward()
    }

    fun seekBack() {
        controller?.seekBack()
    }

    @Synchronized
    fun pause() {
        controller?.pause()
    }

    @Synchronized
    fun stop() {
        controller?.stop()
    }

    @Synchronized
    fun addToPlaylist(
        songs: List<Track>,
        autoPlay: Boolean,
        shuffle: Boolean,
        insertionMode: InsertionMode,
        playlistId: String? = null,
        playlistName: String? = null
    ) {
        var insertAt = 0

        when (insertionMode) {
            InsertionMode.CLEAR -> {
                // A wholesale queue replacement is no longer "the playlist" unless the caller
                // rebinds it below, so drop the binding first to avoid saving the wrong position.
                playlistBinding = false
                clear()
            }
            InsertionMode.APPEND -> insertAt = mediaItemCount
            InsertionMode.AFTER_CURRENT -> {
                // Must never be larger than the count of items (especially when empty)
                insertAt = (currentMediaItemIndex + 1).coerceAtMost(mediaItemCount)
            }
        }

        val mediaItems: List<MediaItem> = songs.map {
            val result = it.toMediaItem()
            result
        }

        Timber.w("Adding ${mediaItems.size} media items")
        controller?.addMediaItems(insertAt, mediaItems)

        // There is a bug in media3 ( https://github.com/androidx/media/issues/480 ),
        // so we must first add the tracks, and then enable shuffle
        if (shuffle) isShufflePlayEnabled = true

        prepare()

        // Playback doesn't start correctly when the player is in STATE_ENDED.
        // So we need to call seek before (this is what play(0,0)) does.
        // We can't just use play(0,0) then all random playlists will start with the first track.
        // Additionally the shuffle order becomes clear on after some time, so we need to wait for
        // the right event, and can start playback only then.
        if (autoPlay && controller?.isPlaying != true) {
            if (isShufflePlayEnabled) {
                deferredPlay = {
                    val start = controller?.currentTimeline
                        ?.getFirstWindowIndex(isShufflePlayEnabled) ?: 0
                    Timber.i("Deferred shuffle play starting now at index: %s", start)
                    play(start)
                }
            } else {
                play(0)
            }
        }

        // If this add was for a server playlist, bind the queue to it so its resume position
        // is tracked. CLEAR above already cleared any previous binding, so this sticks.
        if (playlistId != null) {
            currentPlaylistId = playlistId
            currentPlaylistName = playlistName
            playlistBinding = true
            // A playlist plays straight through and then loops back to its first track, so the
            // driver is never left with a dead "next" once the last song ends. Only flip the
            // mode when it is OFF, leaving an explicit Repeat-One choice untouched.
            if (insertionMode == InsertionMode.CLEAR &&
                (controller?.repeatMode ?: REPEAT_MODE_OFF) == REPEAT_MODE_OFF
            ) {
                controller?.repeatMode = REPEAT_MODE_ALL
            }
        }
    }

    private suspend fun addToPlaylistAsync(
        songs: List<Track>,
        autoPlay: Boolean,
        shuffle: Boolean,
        insertionMode: InsertionMode,
        playlistId: String? = null,
        playlistName: String? = null
    ) {
        withContext(Dispatchers.Main) {
            addToPlaylist(
                songs = songs,
                autoPlay = autoPlay,
                shuffle = shuffle,
                insertionMode = insertionMode,
                playlistId = playlistId,
                playlistName = playlistName
            )
        }
    }

    @Suppress("LongParameterList")
    fun playTracksAndToast(
        fragment: Fragment,
        insertionMode: InsertionMode,
        tracks: List<Track> = listOf(),
        id: String? = null,
        name: String? = "",
        isShare: Boolean = false,
        isDirectory: Boolean = true,
        shuffle: Boolean = false,
        isArtist: Boolean = false,
        playlistId: String? = null,
        playlistName: String? = null
    ) {

        fragment.launchWithToast {

            val list: List<Track> =
                tracks.ifEmpty {
                    requireNotNull(id)
                    DownloadUtil.getTracksFromServerAsync(isArtist, id, isDirectory, name, isShare)
                }

            addToPlaylistAsync(
                songs = list,
                insertionMode = insertionMode,
                autoPlay = (insertionMode == InsertionMode.CLEAR),
                shuffle = shuffle,
                playlistId = playlistId,
                playlistName = playlistName
            )

            // In the two pane layout the player is already visible next to the list,
            // so navigating to the player screen would only hide the list.
            if (insertionMode == InsertionMode.CLEAR && !UiState.twoPane) {
                fragment.navigateToCurrent()
            }

            when (insertionMode) {
                InsertionMode.AFTER_CURRENT ->
                    quantize(R.plurals.n_songs_added_after_current, list)

                InsertionMode.APPEND ->
                    quantize(R.plurals.n_songs_added_to_end, list)

                InsertionMode.CLEAR -> {
                    if (Settings.shouldTransitionOnPlayback)
                        null
                    else
                        quantize(R.plurals.n_songs_added_play_now, list)
                }
            }
        }
    }

    private fun quantize(resId: Int, tracks: List<Track>): String {
        return UApp.applicationContext().resources.getQuantityString(
            resId,
            tracks.size,
            tracks.size
        )
    }

    @set:Synchronized
    var isShufflePlayEnabled: Boolean
        get() = controller?.shuffleModeEnabled == true
        set(enabled) {
            Timber.i("Shuffle is now enabled: %s", enabled)
            RxBus.shufflePlayPublisher.onNext(enabled)
            controller?.shuffleModeEnabled = enabled
        }

    @Synchronized
    fun toggleShuffle(): Boolean {
        isShufflePlayEnabled = !isShufflePlayEnabled
        return isShufflePlayEnabled
    }

    /**
     * Returns an estimate of the percentage in the current content up to which data is
     * buffered, or 0 if no estimate is available.
     */
    @get:IntRange(from = 0, to = 100)
    val bufferedPercentage: Int
        get() = controller?.bufferedPercentage ?: 0

    @Synchronized
    fun moveItemInPlaylist(oldPos: Int, newPos: Int) {
        // TODO: This currently does not care about shuffle position.
        controller?.moveMediaItem(oldPos, newPos)
    }

    @set:Synchronized
    var repeatMode: Int
        get() = controller?.repeatMode ?: 0
        set(newMode) {
            controller?.repeatMode = newMode
        }

    @Synchronized
    @JvmOverloads
    fun clear(serialize: Boolean = true) {

        controller?.clearMediaItems()

        if (controller != null && serialize) {
            playbackStateSerializer.serializeAsync(
                listOf(), -1, 0, isShufflePlayEnabled, repeatMode
            )
        }
    }

    @Synchronized
    fun removeIncompleteTracksFromPlaylist() {
        val list = playlist.toList()
        var removed = 0
        for ((index, item) in list.withIndex()) {
            val state = DownloadService.getDownloadState(item.toTrack())

            // The track is not downloaded, remove it
            if (state != DownloadState.DONE && state != DownloadState.PINNED) {
                removeFromPlaylist(index - removed)
                removed++
            }
        }
    }

    @Synchronized
    fun removeFromPlaylist(position: Int) {
        controller?.removeMediaItem(position)
    }

    @Synchronized
    private fun serializeCurrentSession() {
        // Don't serialize invalid sessions
        if (currentMediaItemIndex == -1) return

        playbackStateSerializer.serializeAsync(
            songs = playlist.map { it.toTrack() },
            currentPlayingIndex = currentMediaItemIndex,
            currentPlayingPosition = playerPosition,
            isShufflePlayEnabled,
            repeatMode
        )
    }

    @Synchronized
    fun seekToPrevious() {
        val controller = controller ?: return
        val timeline = controller.currentTimeline
        val index = currentMediaItemIndex
        if (timeline.isEmpty || index < 0) {
            Timber.w("seekToPrevious: no previous track available (index=$index)")
            return
        }
        val prevIndex = timeline.getPreviousWindowIndex(
            index,
            controller.repeatMode,
            controller.shuffleModeEnabled
        )
        when {
            prevIndex != C.INDEX_UNSET -> {
                controller.seekTo(prevIndex, 0L)
                ensurePlaying()
            }
            index > 0 && !controller.shuffleModeEnabled -> {
                controller.seekTo(index - 1, 0L)
                ensurePlaying()
            }
            else -> {
                controller.seekTo(index, 0L)
                ensurePlaying()
            }
        }
    }

    @Synchronized
    fun canSeekToPrevious(): Boolean {
        val controller = controller ?: return false
        if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) return true
        return !controller.currentTimeline.isEmpty && currentMediaItemIndex >= 0
    }

    /**
     * Go to the next track using timeline window resolution instead of [Player.seekToNext].
     *
     * After a playback error the internal "current item" reference is unreliable and Media3
     * disables [Player.COMMAND_SEEK_TO_NEXT], so a plain [Player.seekToNext] silently no-ops —
     * the steering-wheel / on-screen next button then does nothing while the player is stuck on
     * the error banner. Resolving the next window index from the timeline respects both shuffle
     * and repeat modes while still working in error/idle states, and [ensurePlaying] clears the
     * old error and starts the new track on its own.
     */
    @Synchronized
    fun seekToNext() {
        val controller = controller ?: return
        val timeline = controller.currentTimeline
        val index = currentMediaItemIndex
        val count = controller.mediaItemCount

        if (timeline.isEmpty || index < 0 || count <= 0) {
            Timber.w("seekToNext: no playlist available (index=$index, count=$count)")
            ensurePlaying()
            return
        }

        val nextIndex = timeline.getNextWindowIndex(
            index,
            controller.repeatMode,
            controller.shuffleModeEnabled
        )
        if (nextIndex != C.INDEX_UNSET) {
            controller.seekTo(nextIndex, 0L)
        } else if (index < count - 1 && !controller.shuffleModeEnabled) {
            controller.seekTo(index + 1, 0L)
        }
        ensurePlaying()
    }

    /**
     * Makes sure the music keeps playing after a switch of the current track.
     *
     * The switch can leave the player with the error of the old track still set and
     * play-when-ready off, in which case the new track would sit there ready but silent. The
     * driver has to see the music come back on its own instead of having to press play again.
     * The audio focus of the car is re-requested through the same path a play button takes,
     * so a lost focus is recovered as well.
     */
    private fun ensurePlaying() {
        val controller = controller ?: return
        if (isJukeboxEnabled) return
        if (controller.playerError != null || !controller.playWhenReady) {
            controller.prepare()
            controller.play()
        }
    }

    /**
     * Marks the playlist that the queue was built from. Called by the fragments that start a
     * playlist (home pinned card, playlist detail). Knowing the active playlist lets the resume
     * store remember the position of each playlist independently.
     */
    @Synchronized
    fun setActivePlaylist(id: String?, name: String?) {
        currentPlaylistId = id
        currentPlaylistName = name
        playlistBinding = id != null
        if (id != null) {
            // Remember that this playlist exists, starting at the beginning.
            PlaylistResumeStore.save(id, name ?: "", 0, 0)
        }
    }

    /**
     * Continues a pinned "frequently used" playlist from the progress that was last saved for it.
     *
     * The whole queue is replaced by the playlist's tracks, the player seeks to the saved track
     * and position, then starts playing. Unlike [setActivePlaylist] this does NOT reset the saved
     * progress to zero — it restores exactly where the driver left off.
     */
    @Synchronized
    fun resumePlaylistAt(
        id: String,
        name: String,
        songs: List<Track>,
        index: Int,
        position: Int
    ) {
        if (songs.isEmpty()) {
            Timber.w("resumePlaylistAt: no tracks for playlist %s", id)
            return
        }
        addToPlaylist(
            songs = songs,
            autoPlay = false,
            shuffle = false,
            insertionMode = InsertionMode.CLEAR
        )
        // Bind AFTER the clear above, otherwise the binding would be dropped again.
        currentPlaylistId = id
        currentPlaylistName = name
        playlistBinding = true

        val safeIndex = index.coerceIn(0, songs.lastIndex)
        seekTo(safeIndex, position)
        prepare()
        play()
        PlaylistResumeStore.save(id, name, safeIndex, position)
    }

    /**
     * Persists the current track index and position for the active playlist. Called on every
     * track change and on a slow timer while playing, so a pinned playlist can be resumed.
     * Does nothing unless the queue is currently bound to a playlist.
     */
    @Synchronized
    private fun savePlaylistResume() {
        if (!playlistBinding) return
        val id = currentPlaylistId ?: return
        val controller = controller ?: return
        if (controller.currentMediaItem == null) return
        PlaylistResumeStore.save(
            id,
            currentPlaylistName ?: "",
            currentMediaItemIndex,
            playerPosition
        )
    }

    @Synchronized
    fun canSeekToNext(): Boolean {
        val controller = controller ?: return false
        if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) return true
        val timeline = controller.currentTimeline
        if (timeline.isEmpty || currentMediaItemIndex < 0) return false
        val nextIndex = timeline.getNextWindowIndex(
            currentMediaItemIndex,
            controller.repeatMode,
            controller.shuffleModeEnabled
        )
        return nextIndex != C.INDEX_UNSET
    }

    @Synchronized
    fun reset() {
        controller?.clearMediaItems()
    }

    @get:Synchronized
    val playerPosition: Int
        get() {
            return controller?.currentPosition?.toInt() ?: 0
        }

    @get:Synchronized
    val playerDuration: Int
        get() {
            // Media3 will only report a duration when the file is prepared
            val reportedDuration = controller?.duration ?: C.TIME_UNSET
            if (reportedDuration != C.TIME_UNSET) return reportedDuration.toInt()
            // If Media3 doesn't know the duration yet, use the duration in the metadata
            return (currentMediaItem?.mediaMetadata?.extras?.getInt("duration") ?: 0) * 1000
        }

    val playbackState: Int
        get() = controller?.playbackState ?: 0

    /**
     * True while the last attempt to play a song failed.
     *
     * The interfaces have to tell a broken song from a stopped one, otherwise the driver
     * sees a song that failed as a song that is paused.
     */
    val hasPlaybackError: Boolean
        get() = controller?.playerError != null

    val isPlaying: Boolean
        get() = controller?.isPlaying ?: false

    @set:Synchronized
    var isJukeboxEnabled: Boolean
        get() = PlaybackService.actualBackend == PlayerBackend.JUKEBOX
        set(shouldEnable) {
            if (shouldEnable) {
                switchToJukebox()
            } else {
                switchToLocalPlayer()
            }
        }

    private fun switchToJukebox() {
        if (isJukeboxEnabled) return
        scheduleSwitchTo(PlayerBackend.JUKEBOX)
        DownloadService.requestStop()
        controller?.pause()
        controller?.stop()
    }

    private fun switchToLocalPlayer() {
        if (!isJukeboxEnabled) return
        scheduleSwitchTo(PlayerBackend.LOCAL)
        controller?.stop()
    }

    private fun scheduleSwitchTo(newBackend: PlayerBackend) {
        val currentPlaylist = playlist.toList()
        val currentIndex = controller?.currentMediaItemIndex ?: 0
        val currentPosition = controller?.currentPosition ?: 0

        Handler(Looper.getMainLooper()).postDelayed({
            // Change the backend
            PlaybackService.setBackend(newBackend)
            // Restore the media items
            controller?.setMediaItems(currentPlaylist, currentIndex, currentPosition)
        }, CONTROLLER_SWITCH_DELAY)
    }

    private fun releaseController() {
        controller?.removeListener(listeners)
        controller?.release()
        if (mediaControllerFuture != null) MediaController.releaseFuture(mediaControllerFuture!!)
        Timber.i("MediaPlayerController released")
    }

    fun adjustVolume(up: Boolean) {
        val delta = if (up) VOLUME_DELTA else -VOLUME_DELTA
        var gain = controller?.volume ?: return
        gain += delta
        gain = gain.coerceAtLeast(0.0f)
        gain = gain.coerceAtMost(1.0f)
        controller?.volume = gain
    }

    /*
    * Sets the rating of the current track
    */
    private fun setRating(rating: Rating) {
        if (controller is MediaController) {
            (controller as MediaController).setRating(rating)
        }
    }

    /*
    * This legacy function simply emits a rating update,
    * which will then be processed by both the RatingManager as well as the controller
    */
    fun legacyToggleStar() {
        if (currentMediaItem == null) return
        val track = currentMediaItem!!.toTrack()
        track.starred = !track.starred
        val rating = HeartRating(track.starred)

        RxBus.ratingSubmitter.onNext(
            RatingUpdate(
                track.id,
                rating
            )
        )
    }

    /*
     * This legacy function simply emits a rating update,
     * which will then be processed by both the RatingManager as well as the controller
     */
    fun legacySetRating(num: Int) {
        if (currentMediaItem == null) return
        val track = currentMediaItem!!.toTrack()
        track.userRating = num
        val rating = StarRating(5, num.toFloat())

        RxBus.ratingSubmitter.onNext(
            RatingUpdate(
                track.id,
                rating
            )
        )
    }

    val currentMediaItem: MediaItem?
        get() = controller?.currentMediaItem

    val currentMediaItemIndex: Int
        get() = controller?.currentMediaItemIndex ?: -1

    /**
     * The position of the current song in the order the songs are played.
     *
     * With the shuffle turned on the timeline keeps the order of the playlist, so the
     * index of the player is not the index that the queue shows. This is the index that
     * matches the list the driver sees.
     */
    fun getCurrentShuffleIndex(): Int {
        val currentMediaItemIndex = controller?.currentMediaItemIndex ?: return -1
        return getShuffledIndexOf(currentMediaItemIndex)
    }

    /**
     * Loops over the timeline windows to find the entry which matches the given closure.
     *
     * @param returnWindow Determines which of the two indexes of a match to return:
     * True for the position in the playlist, False for position in the play order.
     * @param searchClosure Determines the condition which the searched for window needs to match.
     * @return the index of the window that satisfies the search condition,
     * or [C.INDEX_UNSET] if not found.
     */
    @Suppress("KotlinConstantConditions")
    private fun getWindowIndexWhere(
        returnWindow: Boolean,
        searchClosure: (Int, Int) -> Boolean
    ): Int {
        val timeline = controller?.currentTimeline ?: return C.INDEX_UNSET
        if (timeline.isEmpty) return C.INDEX_UNSET
        var windowIndex = timeline.getFirstWindowIndex(true)
        var count = 0

        while (windowIndex != C.INDEX_UNSET) {
            val match = searchClosure(count, windowIndex)
            if (match && returnWindow) return windowIndex
            if (match && !returnWindow) return count
            count++
            windowIndex = timeline.getNextWindowIndex(
                windowIndex, REPEAT_MODE_OFF, true
            )
        }

        return C.INDEX_UNSET
    }

    /**
     * Returns the index of the shuffled position of the current playback item given its original
     * position in the unshuffled timeline.
     *
     * @param searchPosition The index of the item in the unshuffled timeline to search for
     * in the shuffled timeline.
     * @return The index of the item in the shuffled timeline, or [C.INDEX_UNSET] if not found.
     */
    private fun getShuffledIndexOf(searchPosition: Int): Int {
        return getWindowIndexWhere(false) { _, windowIndex ->
            windowIndex == searchPosition
        }
    }

    /**
     * Returns the index of the unshuffled position of the current playback item given its shuffled
     * position in the shuffled timeline.
     *
     * @param shufflePosition the index of the item in the shuffled timeline to search for in the
     * unshuffled timeline.
     * @return the index of the item in the unshuffled timeline, or [C.INDEX_UNSET] if not found.
     */
    fun getUnshuffledIndexOf(shufflePosition: Int): Int {
        return getWindowIndexWhere(true) { count, _ ->
            count == shufflePosition
        }
    }

    val mediaItemCount: Int
        get() = controller?.mediaItemCount ?: 0

    fun getMediaItemAt(index: Int): MediaItem? {
        return controller?.getMediaItemAt(index)
    }

    val playlistSize: Int
        get() = controller?.currentTimeline?.windowCount ?: 0

    val playlist: List<MediaItem>
        get() {
            return Util.getPlayListFromTimeline(controller?.currentTimeline, false)
        }

    val playlistInPlayOrder: List<MediaItem>
        get() {
            return Util.getPlayListFromTimeline(
                controller?.currentTimeline,
                controller?.shuffleModeEnabled ?: false
            )
        }

    val playListDuration: Long
        get() = playlist.fold(0) { i, file ->
            i + (file.mediaMetadata.extras?.getInt("duration") ?: 0)
        }

    init {
        Timber.i("MediaPlayerController instance initiated")
    }

    enum class InsertionMode {
        CLEAR, APPEND, AFTER_CURRENT
    }

    enum class PlayerBackend { JUKEBOX, LOCAL }
}
