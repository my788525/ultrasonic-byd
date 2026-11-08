/*
 * PlaybackService.kt
 * Copyright (C) 2009-2023 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_MEDIA
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.audiofx.EqualizerController
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.CachedDataSource
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.imageloader.ArtworkBitmapLoader
import org.moire.ultrasonic.provider.UltrasonicAppWidgetProvider
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.NetworkMonitor
import org.moire.ultrasonic.util.PreloadPolicy
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.Util.stopForegroundRemoveNotification
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class PlaybackService :
    MediaLibraryService(),
    KoinComponent,
    CoroutineScope by CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            // A crash in the background takes the whole app down, and on a car that means the
            // music stops while driving. Every background job reports here instead.
            Timber.e(error, "PlaybackService background job failed")
        }
    ) {
    private lateinit var player: Player
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private var equalizer: EqualizerController? = null
    private val activeServerProvider: ActiveServerProvider by inject()

    private lateinit var librarySessionCallback: MediaLibrarySessionCallback

    private var rxBusSubscription = CompositeDisposable()

    private var isStarted = false

    private var preloadJob: kotlinx.coroutines.Job? = null

    /**
     * Handles the audio focus so that the music can be ducked to an audible level while the
     * navigation app is talking. See [CarAudioFocusManager] for why this cannot be left to
     * ExoPlayer.
     */
    private lateinit var carAudioFocusManager: CarAudioFocusManager

    /** Watches the network so that the music can continue after a tunnel. */
    private lateinit var networkMonitor: NetworkMonitor

    /**
     * Keeps the state of the automatic resume: the position, the time window and the backoff
     * between the attempts.
     */
    private val recoveryState = NetworkRecoveryState()

    /**
     * Guards the recovery state, which is written by the player error callback and read by
     * the network poll. Both run on different threads.
     */
    private val recoveryLock = Any()

    /** Polls the network for the ROMs that do not deliver the network callback. */
    private var networkPollJob: kotlinx.coroutines.Job? = null

    /** Runs the player calls that have to happen on the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        Timber.i("onCreate called")
        super.onCreate()
        initializeSessionAndPlayer()
        setListener(MediaSessionServiceListener())
        instance = this

        networkMonitor = NetworkMonitor(this)
        networkMonitor.register { available ->
            if (available) {
                resumeAfterNetworkRecovery()
            } else {
                Timber.i("Network lost")
            }
        }
        startNetworkPoll()
    }

    private fun getWakeModeFlag(): Int {
        return if (ActiveServerProvider.isOffline()) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK
    }

    override fun onDestroy() {
        Timber.i("onDestroy called")
        stopPreloadLoop()
        networkPollJob?.cancel()
        networkPollJob = null
        if (this::networkMonitor.isInitialized) {
            networkMonitor.unregisterAll()
        }
        releasePlayerAndSession()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Stopping the playback because we were swiped away")
        releasePlayerAndSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun releasePlayerAndSession() {
        Timber.i("Releasing player and session")
        // Broadcast that the service is being shutdown
        RxBus.stopServiceCommandPublisher.onNext(Unit)

        // TODO Save the player state before shutdown
        // The focus has to be released before the player, because the release restores the
        // volume that was lowered for a duck.
        if (this::carAudioFocusManager.isInitialized) {
            carAudioFocusManager.abandonFocus()
        }
        // The equalizer holds a native audio effect that belongs to the audio session of
        // the player. It has to be released before the player goes away.
        equalizer?.let {
            EqualizerController.release()
            equalizer = null
        }
        player.removeListener(listener)
        player.release()
        mediaLibrarySession.release()
        rxBusSubscription.dispose()
        isStarted = false
        stopForegroundRemoveNotification()
        stopSelf()
        instance = null
    }

    @Suppress("TooGenericExceptionCaught")
    private val resolver: ResolvingDataSource.Resolver = ResolvingDataSource.Resolver {
        val components = it.uri.toString().split('|')
        val id = components[0]
        val bitrate = components.getOrNull(1)?.toIntOrNull()
        val uri = try {
            getMusicService().getStreamUrl(id, bitrate, null)
        } catch (e: Exception) {
            throw java.io.IOException("Failed to resolve stream URL for $id", e)
        } ?: throw java.io.IOException("Stream URL is null for $id")
        // AirSonic doesn't seem to stream correctly with the default
        // icy-metadata headers set by media3, so remove them.
        it.buildUpon().setUri(uri).setHttpRequestHeaders(emptyMap()).build()
    }

    private fun initializeSessionAndPlayer() {
        if (isStarted) return

        val desiredBackend = if (activeServerProvider.getActiveServer().jukeboxByDefault) {
            Timber.i("Jukebox enabled by default")
            MediaPlayerManager.PlayerBackend.JUKEBOX
        } else {
            MediaPlayerManager.PlayerBackend.LOCAL
        }

        player = createNewBackend(desiredBackend)

        actualBackend = desiredBackend

        // The focus manager gets the player through a provider, because the backend switch in
        // updateBackend() replaces the player instance.
        carAudioFocusManager = CarAudioFocusManager(
            PlatformAudioFocusRequester(this)
        ) { player }

        // Create browser interface
        librarySessionCallback = MediaLibrarySessionCallback()

        // This will need to use the AutoCalls
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
            .setSessionActivity(getPendingIntentForContent())
            .setBitmapLoader(ArtworkBitmapLoader())
            .build()

        // Send custom layout to legacy session.
        mediaLibrarySession.setCustomLayout(librarySessionCallback.defaultCustomCommands)

        // Set a listener to update the API client when the active server has changed
        rxBusSubscription += RxBus.activeServerChangedObservable.subscribe {
            // Set the player wake mode
            (player as? ExoPlayer)?.setWakeMode(getWakeModeFlag())
        }

        // Set a listener to reset the ShuffleOrder
        rxBusSubscription += RxBus.shufflePlayObservable.subscribe { shuffle ->
            // This only applies for local playback
            val exo = if (player is ExoPlayer) {
                player as ExoPlayer
            } else {
                return@subscribe
            }
            val len = player.currentTimeline.windowCount

            Timber.i("Resetting shuffle order, isShuffled: %s", shuffle)

            // If disabling Shuffle return early
            if (!shuffle) {
                return@subscribe exo.setShuffleOrder(
                    ShuffleOrder.UnshuffledShuffleOrder(len)
                )
            }

            // Get the position of the current track in the unshuffled order
            val cur = player.currentMediaItemIndex
            val seed = System.currentTimeMillis()
            val random = Random(seed)

            val list = createShuffleListFromCurrentIndex(cur, len, random)
            Timber.i("New Shuffle order: %s", list.joinToString { it.toString() })
            exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(list, seed))
        }

        // Listen to the shutdown command
        rxBusSubscription += RxBus.shutdownCommandObservable.subscribe {
            Timber.i("Received destroy command via Rx")
            onDestroy()
        }

        player.addListener(listener)
        isStarted = true
    }

    private fun updateBackend(newBackend: MediaPlayerManager.PlayerBackend) {
        Timber.i("Switching player backends")
        // Remove old listeners
        player.removeListener(listener)
        player.release()

        player = createNewBackend(newBackend)

        // Add fresh listeners
        player.addListener(listener)

        mediaLibrarySession.player = player

        actualBackend = newBackend
    }

    private fun createNewBackend(newBackend: MediaPlayerManager.PlayerBackend): Player {
        return if (newBackend == MediaPlayerManager.PlayerBackend.JUKEBOX) {
            getJukeboxPlayer()
        } else {
            getLocalPlayer()
        }
    }

    private fun getJukeboxPlayer(): Player {
        return JukeboxMediaPlayer()
    }

    private fun getLocalPlayer(): Player {
        // Create an OkHttpClient tuned for car streaming with bounded timeouts
        val client = OkHttpClient.Builder()
            .connectTimeout(STREAM_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(STREAM_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // Create the wrapped data sources:
        // CachedDataSource is the first. If it cannot find a file,
        // it will forward to ResolvingDataSource, which will create a URL through the resolver
        // and pass it onto the OkHttpDataSource.
        val okHttpDataSource = OkHttpDataSource.Factory(client)
        val resolvingDataSource = ResolvingDataSource.Factory(okHttpDataSource, resolver)
        val cacheDataSourceFactory: DataSource.Factory =
            CachedDataSource.Factory(resolvingDataSource)

        // Create a renderer with HW rendering support
        val renderer = DefaultRenderersFactory(this)

        if (Settings.useHwOffload)
            renderer.setEnableAudioOffload(true)

        // Create the player
        // The audio focus is handled by CarAudioFocusManager instead of ExoPlayer: ExoPlayer ducks
        // to 20 % with an internal multiplier that cannot be lifted from the outside.
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(getAudioAttributes(), false)
            .setWakeMode(getWakeModeFlag())
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setRenderersFactory(renderer)
            .setSeekBackIncrementMs(Settings.seekInterval.toLong())
            .setSeekForwardIncrementMs(Settings.seekInterval.toLong())
            .build()

        // The equalizer cannot be created here: the audio session only exists once the
        // audio track was created, which happens when the first sound is rendered. Reading
        // the session id right after the build would give the unset id, and the equalizer
        // would fail silently and never be available. It is created in
        // onAudioSessionIdChanged instead.

        // Enable audio offload
        if (Settings.useHwOffload)
            player.experimentalSetOffloadSchedulingEnabled(true)

        return player
    }

    private fun createShuffleListFromCurrentIndex(
        currentIndex: Int,
        length: Int,
        random: Random
    ): IntArray {
        val list = IntArray(length) { it }

        // Shuffle the remaining items using a swapping algorithm
        for (i in currentIndex + 1 until length) {
            val swapIndex = (currentIndex + 1) + random.nextInt(i - currentIndex)
            val swapItem = list[i]
            list[i] = list[swapIndex]
            list[swapIndex] = swapItem
        }

        return list
    }

    private val listener: Player.Listener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            cacheNextSongs()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Since we cannot update the metadata of the media item after creation,
            // we cannot set change the rating on it
            // Therefore the track must be our source of truth
            val track = mediaItem?.toTrack()
            if (track != null) {
                updateCustomHeartButton(track.starred)
            }
            updateWidgetTrack(track)
            cacheNextSongs()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateWidgetPlayerState(isPlaying)
            cacheNextSongs()
            if (isPlaying) startPreloadLoop() else stopPreloadLoop()
            // The focus is only held while the music is playing, so that the navigation app can
            // talk even when the music is paused.
            if (isPlaying) {
                // The music may only play when the focus could be obtained. When another app
                // holds the audio, the playback is stopped again right away, so that the
                // music does not talk over a phone call or the navigation voice.
                if (!carAudioFocusManager.onPlaybackStarted()) {
                    runOnMain { player.pause() }
                }
                // The music is running again: the outage is over and the recovery budget
                // has to be released, so that a later outage gets the full window again.
                synchronized(recoveryLock) {
                    recoveryState.onSuccess()
                }
                publishRecoveryState(false)
            } else {
                carAudioFocusManager.onPlaybackStopped()
            }
        }

        /**
         * Only logs the volume. The ducking while the navigation app speaks is not visible here:
         * it is applied by [CarAudioFocusManager] as a plain player volume change.
         */
        override fun onVolumeChanged(volume: Float) {
            Timber.d("Player volume changed to %f", volume)
        }

        /**
         * The audio session only exists once the audio track was created. The equalizer
         * has to wait for it, otherwise it would be built with the unset session id and
         * would fail silently, so the equalizer menu would never appear.
         */
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            createEqualizer(audioSessionId)
        }

        /**
         * The second chance to create the equalizer.
         *
         * The audio session id is only reported when it changes, and the player reports
         * the id of the session it is going to use, which is not always the one the
         * renderer ends up with. When the playback starts, the session that is really in
         * use can be read, so the equalizer is built from that one.
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_READY) return
            if (equalizer != null) return
            // The audio session id is not part of the Player interface, only the player
            // that is built here has it.
            val exoPlayer = player as? ExoPlayer ?: return
            createEqualizer(exoPlayer.audioSessionId)
        }

        /**
         * Builds the equalizer for the given audio session, if it does not exist yet.
         */
        private fun createEqualizer(audioSessionId: Int) {
            if (equalizer != null) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
            equalizer = EqualizerController.create(audioSessionId)
            if (equalizer != null) {
                Timber.i("Equalizer is now available, audio session %d", audioSessionId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    /**
     * Reacts to a playback error.
     *
     * A network problem is not fatal: the player keeps its playlist and its position, it only
     * stops loading data. So the position is remembered and the music is picked up again as
     * soon as the network is back. On a car that matters, because the alternative is that the
     * driver has to reach for the phone to restart the music.
     */
    private fun handlePlayerError(error: PlaybackException) {
        Timber.w(error, "Player error")

        if (!isNetworkError(error)) {
            RxBus.playerErrorPublisher.onNext(error)
            return
        }

        // The position has to be read on the main thread, like every other player access.
        val position = runOnMain { player.currentPosition }
        val shouldResume = runOnMain { player.playWhenReady }
        // The track the position belongs to. The driver can change the song while the
        // network is down, and a position without a track would seek into the wrong song.
        val trackId = runOnMain { player.currentMediaItem?.mediaId }

        val accepted = synchronized(recoveryLock) {
            recoveryState.onFailure(position, trackId, shouldResume, System.currentTimeMillis())
        }

        if (!accepted) {
            Timber.w("Giving up on the automatic resume, the recovery window has passed")
            RxBus.playerErrorPublisher.onNext(error)
            RxBus.networkRecoveryPublisher.onNext(NetworkRecoveryStatus(gaveUp = true))
            return
        }

        Timber.i(
            "Network problem while playing, waiting for the network to come back at %d ms",
            position
        )
        RxBus.playerErrorPublisher.onNext(error)
        publishRecoveryState(true)
    }

    /**
     * Tells the interface whether the music is waiting for the network.
     */
    private fun publishRecoveryState(waiting: Boolean) {
        RxBus.networkRecoveryPublisher.onNext(
            NetworkRecoveryStatus(
                isWaiting = waiting,
                attempts = synchronized(recoveryLock) { recoveryState.attempts }
            )
        )
    }

    /**
     * True when the error was caused by the network rather than by the media itself.
     */
    private fun isNetworkError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) {
            return true
        }
        // A stream that dies halfway through reports a generic IO error. The cause tells the
        // two apart: a dropped connection is an IOException, a broken file is not.
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is java.io.IOException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Starts the music again after the network came back.
     *
     * The player is in the idle state after an error, and playing alone would not load any
     * data, so it has to be prepared again. The position is restored first, otherwise the
     * music would start from the beginning of the song.
     */
    private fun resumeAfterNetworkRecovery() {
        // The network callback and the poll can both see the network come back, so the
        // decision to resume is taken once and only once.
        var attemptNumber = 0
        val resume = synchronized(recoveryLock) {
            val now = System.currentTimeMillis()
            // The window can also pass while the player is playing a cached file and no
            // failure is reported, so it has to be checked here as well.
            if (recoveryState.isWindowExpired(now)) {
                recoveryState.reset()
                Timber.w("Giving up on the automatic resume, the recovery window has passed")
                RxBus.networkRecoveryPublisher.onNext(NetworkRecoveryStatus(gaveUp = true))
                return
            }
            // The resume was already taken for this failure. The network callback and the
            // poll both see the network come back, so only one of them may act on it.
            if (!recoveryState.hasPendingResume) return
            if (!recoveryState.mayAttempt(now)) {
                // The backoff of this outage has not passed yet. The attempt is not lost,
                // the poll comes back later.
                Timber.i(
                    "Waiting for the backoff of the recovery, attempt %d is not due yet",
                    recoveryState.attempts
                )
                return
            }
            recoveryState.onAttempt(now)
            attemptNumber = recoveryState.attempts
            recoveryState.takeResume()
        } ?: return

        Timber.i(
            "Network is back, resuming playback at %d ms (attempt %d)",
            resume.positionMs,
            attemptNumber
        )
        runOnMain {
            // The driver may have paused the music on purpose while the network was down.
            // playWhenReady is the intent of the user, so it is the right thing to look at
            // here: the sample that was taken when the error happened is too old.
            if (!player.playWhenReady) {
                Timber.i("The playback was paused on purpose during the outage, not resuming")
                synchronized(recoveryLock) {
                    recoveryState.clearPending()
                }
                return@runOnMain
            }
            // The driver may have changed the song while the network was down. Seeking to
            // the old position would then jump into the middle of a different song.
            val sameTrack = resume.trackId == null ||
                resume.trackId == player.currentMediaItem?.mediaId
            if (sameTrack && resume.positionMs >= 0) {
                player.seekTo(resume.positionMs)
            } else if (!sameTrack) {
                Timber.i("The song changed during the outage, starting the new one from the top")
            }
            player.prepare()
            if (!mayStartPlayback()) {
                Timber.i("The resume was skipped, another app has the audio")
                return@runOnMain
            }
            player.play()
        }
    }

    /**
     * True when the music is allowed to start.
     *
     * After another app took the audio for good, the music must not come back on its own:
     * the driver has to press play again.
     */
    private fun mayStartPlayback(): Boolean {
        if (!this::carAudioFocusManager.isInitialized) return true
        return carAudioFocusManager.mayStartPlayback()
    }

    /**
     * Runs [block] on the main thread and returns its result.
     *
     * The player may only be read on the thread its application looper runs on. This is used
     * from the background, where a direct call would make ExoPlayer throw.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (e: Exception) {
                Timber.w(e, "Main thread call failed")
            } finally {
                latch.countDown()
            }
        }
        latch.await(MAIN_THREAD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun updateCustomHeartButton(isHeart: Boolean) {
        librarySessionCallback.updateCustomHeartButton(mediaLibrarySession, isHeart)
    }

    /**
     * Asks the download service to keep the next songs on disk.
     *
     * The player may only be read on the thread its application looper runs on, which is the
     * main thread. Reading it from anywhere else makes ExoPlayer throw, so the snapshot is
     * taken on the main thread and only the resulting tracks travel to the background.
     */
    private fun cacheNextSongs() {
        if (actualBackend == MediaPlayerManager.PlayerBackend.JUKEBOX) return

        // The car setting caps the queue: keeping one or two songs on disk is what a tunnel
        // needs, downloading the whole queue would fill the storage of the head unit.
        val count = PreloadPolicy.count(Settings.carPreload, Settings.preloadCount)
        if (count <= 0) return

        Timber.d("PlaybackService caching the next %d songs", count)
        launch {
            val nextSongs = withContext(Dispatchers.Main.immediate) {
                snapshotNextSongs(count)
            }
            if (nextSongs.isEmpty()) return@launch
            DownloadService.download(nextSongs, isHighPriority = true)
        }
    }

    /**
     * The songs that come after the current one.
     *
     * This has to run on the main thread, because it reads the player. The timeline and the
     * index are read together so that they cannot disagree with each other.
     */
    private fun snapshotNextSongs(count: Int): List<Track> {
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        val shuffled = player.shuffleModeEnabled
        if (index == C.INDEX_UNSET) return emptyList()

        // The preload is for the songs that come *after* the one that is playing. The song
        // that is playing is already being streamed, so caching it again would waste the
        // bandwidth that the next song needs.
        val nextIndex = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffled)
        if (nextIndex == C.INDEX_UNSET) return emptyList()

        return Util.getPlayListFromTimeline(
            timeline,
            shuffled,
            nextIndex,
            count
        ).map { it.toTrack() }
    }

    /**
     * Re-checks the preload while a song plays.
     *
     * Downloading only when the track changes is not enough: a download that fails on a weak
     * connection is never retried, and the next song then stalls in the tunnel. The loop is
     * cheap when everything is already cached, because [cacheNextSongs] only asks for the
     * songs that are still missing.
     */
    private fun startPreloadLoop() {
        if (preloadJob?.isActive == true) return
        preloadJob = launch {
            while (isActive) {
                delay(PRELOAD_INTERVAL_MS)
                // Reading the player needs the main thread, see snapshotNextSongs.
                val playing = withContext(Dispatchers.Main.immediate) { player.isPlaying }
                if (playing) cacheNextSongs()
            }
        }
    }

    private fun stopPreloadLoop() {
        preloadJob?.cancel()
        preloadJob = null
    }

    /**
     * Polls the network while the playback is waiting for it.
     *
     * The network callback is the fast path, but some head unit ROMs do not deliver it
     * reliably. The poll is the fallback that makes the music continue anyway.
     */
    private fun startNetworkPoll() {
        networkPollJob?.cancel()
        networkPollJob = launch {
            while (isActive) {
                delay(NETWORK_POLL_INTERVAL_MS)
                if (!networkMonitor.isNetworkAvailable()) continue
                resumeAfterNetworkRecovery()
            }
        }
    }

    private fun getPendingIntentForContent(): PendingIntent {
        val intent = Intent(this, NavigationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // needed starting Android 12 (S = 31)
            flags = flags or FLAG_IMMUTABLE
        }
        intent.action = Intent.ACTION_MAIN
        intent.putExtra(Constants.INTENT_SHOW_PLAYER, true)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun getAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun updateWidgetTrack(song: Track?) {
        val context = UApp.applicationContext()
        UltrasonicAppWidgetProvider.notifyTrackChange(context, song)
    }

    private fun updateWidgetPlayerState(isPlaying: Boolean) {
        val context = UApp.applicationContext()
        UltrasonicAppWidgetProvider.notifyPlayerStateChange(context, isPlaying)
    }

    private inner class MediaSessionServiceListener : Listener {

        /**
         * This method is only required to be implemented on Android 12 or above when an attempt is made
         * by a media controller to resume playback when the {@link MediaSessionService} is in the
         * background.
         */
        override fun onForegroundServiceStartNotAllowedException() {
            val notificationManagerCompat = NotificationManagerCompat.from(this@PlaybackService)
            Util.ensureNotificationChannel(
                id = NOTIFICATION_CHANNEL_ID,
                name = NOTIFICATION_CHANNEL_NAME,
                notificationManager = notificationManagerCompat
            )
            val pendingIntent =
                TaskStackBuilder.create(this@PlaybackService).run {
                    addNextIntent(Intent(this@PlaybackService, NavigationActivity::class.java))

                    val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        FLAG_IMMUTABLE
                    } else {
                        0
                    }
                    getPendingIntent(0, immutableFlag or FLAG_UPDATE_CURRENT)
                }
            val builder =
                NotificationCompat.Builder(this@PlaybackService, NOTIFICATION_CHANNEL_ID)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.media3_notification_small_icon)
                    .setContentTitle(getString(R.string.foreground_exception_title))
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            getString(R.string.foreground_exception_text)
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)

            Util.postNotificationIfPermitted(
                notificationManagerCompat,
                NOTIFICATION_ID,
                builder.build()
            )
        }
    }

    companion object {
        var actualBackend: MediaPlayerManager.PlayerBackend? = null

        private var desiredBackend: MediaPlayerManager.PlayerBackend? = null
        fun setBackend(playerBackend: MediaPlayerManager.PlayerBackend) {
            desiredBackend = playerBackend
            instance?.updateBackend(playerBackend)
        }

        var instance: PlaybackService? = null

        private const val NOTIFICATION_CHANNEL_ID = "org.moire.ultrasonic.error"
        private const val NOTIFICATION_CHANNEL_NAME = "Ultrasonic error messages"
        const val CUSTOM_COMMAND_TOGGLE_HEART_ON =
            "org.moire.ultrasonic.HEART_ON"
        const val CUSTOM_COMMAND_TOGGLE_HEART_OFF =
            "org.moire.ultrasonic.HEART_OFF"
        const val CUSTOM_COMMAND_SHUFFLE =
            "org.moire.ultrasonic.SHUFFLE"
        const val CUSTOM_COMMAND_PLACEHOLDER =
            "org.moire.ultrasonic.PLACEHOLDER"
        const val CUSTOM_COMMAND_REPEAT_MODE =
            "org.moire.ultrasonic.REPEAT_MODE"
        private const val NOTIFICATION_ID = 3009

        /** How often the preload is re-checked while a song plays. */
        private const val PRELOAD_INTERVAL_MS = 45_000L

        /** How often the network is checked while the playback waits for it. */
        private const val NETWORK_POLL_INTERVAL_MS = 5_000L

        /** How long a main thread call may take before it is given up on. */
        private const val MAIN_THREAD_CALL_TIMEOUT_MS = 2_000L

        /** Bounds for the streaming OkHttpClient so a dead tunnel connection fails fast. */
        private const val STREAM_CONNECT_TIMEOUT_SEC = 8L
        private const val STREAM_READ_TIMEOUT_SEC = 15L
    }
}
