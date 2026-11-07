/*
 * FloatingPlayerService.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.util.CarTransport
import org.moire.ultrasonic.util.FloatingAppearance
import org.moire.ultrasonic.util.LyricsParser
import org.moire.ultrasonic.util.ReverseSafetyManager
import org.moire.ultrasonic.activity.NavigationActivity
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.SleepTimerManager
import org.moire.ultrasonic.util.TimedLyric
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * Shows a small always on top player above other apps.
 *
 * The tiers are defined by their footprint, not by which widgets they show, because on a head
 * unit the only thing that matters while navigating is how much of the map the window covers:
 *
 * | tier | content                                  | size        | share of a 1280x720 screen |
 * |------|------------------------------------------|-------------|----------------------------|
 * | 0    | one line with the song title             | 240 x 44 dp | about 1 %                  |
 * | 1    | title + previous / play / next           | 300 x 96 dp | about 3 %                  |
 * | 2    | cover, title, artist, progress, buttons   | 340 x 148 dp| about 5 %                  |
 * | 3    | three lyric lines + buttons              | 380 x 216 dp| about 8 %                  |
 * | 4    | two lyric lines, no buttons              | 300 x 92 dp | about 3 %                  |
 *
 * The position, the docked side, the lock and the tier all survive a restart, so the window
 * comes back exactly where the driver put it.
 *
 * The playback state is read through a [MediaController] connection. A service cannot use the
 * injected MediaPlayerManager, because that one lives in the NavigationActivity scope and does
 * not exist for a background service.
 */
class FloatingPlayerService : Service(), KoinComponent, SleepTimerManager.Listener {

    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private val sleepTimerManager: SleepTimerManager by inject()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var headerView: View? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var lyricsView: TextView? = null
    private var lyricsMinView: TextView? = null
    private var progressView: ProgressBar? = null
    private var albumArt: ImageView? = null
    private var controlsView: View? = null
    private var closeView: View? = null
    private var textBlock: View? = null
    private var previousButton: View? = null
    private var nextButton: View? = null
    private var playButton: ImageButton? = null
    private var sleepButton: ImageButton? = null
    private var statusView: TextView? = null

    private var tier = TIER_COMPACT
    private var navMode = false
    private var locked = false
    private var passThrough = false

    /**
     * The reverse-camera / 360 safety gate. It watches the car and, when the gearbox is in R or
     * the panorama view is up, hides this window and pauses the music. It is wired here so it runs
     * exactly while the floating window exists, and never on a device without the car service.
     */
    private val reverseSafetyManager = ReverseSafetyManager()

    /** True while the detail block (cover, title, artist) is shown and waiting to collapse. */
    private var detailVisible = false

    /** The time the song changed; the detail block collapses this many seconds after it. */
    private var lastTrackChangeAt = 0L

    /**
     * Whether the view that is currently shown is the banner layout.
     *
     * The banner is a different layout file, so switching to or from it has to rebuild the window.
     * This flag lets [applyTier] detect the switch and recreate the overlay instead of trying to
     * repurpose a layout that does not contain the banner's views.
     */
    private var currentIsBanner = false

    /**
     * The time of the last tap on the previous and on the next button.
     *
     * The buttons of a head unit are large and the driver taps them with a finger that is not
     * exactly steady, so a double tap is common. Without this guard a single intention would
     * skip two songs.
     */
    private var lastPreviousClickAt = 0L
    private var lastNextClickAt = 0L

    /**
     * The time of the last error of the player.
     *
     * The error has to stay visible for a moment, because the window is only glanced at: an
     * error that is shown for a single frame is never seen.
     */
    private var errorSince = 0L

    /**
     * The colour the text of the window is painted in.
     *
     * The window is drawn over the navigation map, so the driver picks a colour that stays
     * readable over whatever the map shows. It is resolved once per appearance change and then
     * used for the title, the artist, the lyrics and the transport buttons.
     */
    private var textColor = Color.WHITE

    /** True when the current tier has a progress bar, which still depends on a loaded track. */
    private var progressAllowed = false

    /** True when the current tier has room for the state of the playback. */
    private var statusAllowed = false

    private var currentTrackId: String? = null

    /**
     * The song the window shows.
     *
     * It is kept, because the lyrics have to be fetched again when the network comes back,
     * and at that moment the song does not change.
     */
    private var currentTrack: Track? = null

    /**
     * A null "no track" state has to be drawn once as well, otherwise the window stays empty
     * until the first song starts, because the id of the missing track equals the id of the
     * missing track.
     */
    private var stateInitialized = false
    private var lyricsTrackId: String? = null
    private var timedLyrics: List<TimedLyric> = emptyList()
    private var lyricIndex = -1

    /** True while the playback waits for the network to come back. */
    private var waitingForNetwork = false

    /**
     * True while the window shows the "waiting for the network" message.
     *
     * The flag is separate from [waitingForNetwork], because the message has to be taken off
     * the window when the music continues, even when the song has no lyrics at all. Without
     * it the window would keep the message forever, since there is nothing to draw over it.
     */
    private var showingNetworkMessage = false

    private val handler = Handler(Looper.getMainLooper())

    /** The subscriptions of the service, released when it goes away. */
    private val disposables = CompositeDisposable()

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    /**
     * A 1x1 transparent window that only watches the drawing surface. When the head unit
     * brings up the 360 / reversing camera it reclaims the app's surface - the very same
     * zero-permission signal the fntv BYD fork uses to stop playback. It stays attached
     * for the whole life of this service, independently of the player window, so hiding
     * and rebuilding the player cannot destroy the sensor. No usage access is required.
     */
    private val idleFadeRunnable = Runnable { fadeToIdle() }

    /**
     * Manual fallback for a locked-down head unit: the notification action hides the
     * window for 30 seconds, then it comes back by itself. No permission is involved.
     */
    @Volatile private var tempHideActive = false
    private val tempHideRunnable = Runnable {
        tempHideActive = false
        applyOverlayVisibility()
    }

    /** Collapses the detail block into the lyric lines after the chosen number of seconds. */
    private val collapseDetailRunnable = Runnable {
        detailVisible = false
        if (rootView != null) {
            applyDetailVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        connectController()

        // The sleep timer owns its own countdown in a separate manager, so it survives even
        // when the floating window is rebuilt. A timer that was running when the process died
        // is re-armed here instead of silently switching itself off.
        sleepTimerManager.addListener(this)
        sleepTimerManager.resumeIfNeeded()

        // The window follows the palette of the app, so a change of the theme has to be applied
        // to the running overlay as well.
        disposables += RxBus.themeChangedEventObservable.subscribe {
            applyAppearance()
        }

        // The driver has to know that the music is waiting for the network. A window that
        // stands still without a message looks like a frozen app.
        disposables += RxBus.networkRecoveryObservable.subscribe { state ->
            val wasWaiting = waitingForNetwork
            waitingForNetwork = state.isWaiting
            if (state.gaveUp) {
                Util.toast(R.string.network_recovery_gave_up, context = this)
            }
            // The music is playing again: the message has to go away and the lyrics have to
            // be fetched again, because the download of the lyrics failed while the network
            // was down.
            if (wasWaiting && !waitingForNetwork) {
                showingNetworkMessage = false
                lyricsTrackId = null
                timedLyrics = emptyList()
                lyricIndex = -1
                ensureLyrics(currentTrack)
            }
            // The lyrics are replaced by the message, so they have to be drawn again.
            lyricIndex = -1
            updateState()
        }
    }

    /**
     * Connects to the playback service. This is asynchronous, so the first frames of the
     * overlay are drawn without state and get updated as soon as the controller is ready.
     */
    private fun connectController() {
        if (controller != null || controllerFuture != null) return

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                controller = try {
                    future.get()
                } catch (all: Exception) {
                    Timber.w(all, "Could not connect the floating window to the player")
                    null
                }
                updateState()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The lock is read from the settings on every start, so locking the window from the
        // settings takes effect on the window that is already open. It is read before the
        // branches above, because the navigation mode returns early.
        locked = Settings.floatingLocked

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_NAV_ON -> {
                navMode = true
                // Keep the style the driver picked (lyrics, standard, …) instead of forcing the
                // compact tier, so a chosen lyrics window does not silently collapse to minimal.
                tier = intent?.getIntExtra(EXTRA_TIER, TIER_COMPACT) ?: TIER_COMPACT
                if (rootView == null) showOverlay() else applyTier()
                return START_STICKY
            }

            ACTION_REFRESH -> {
                // The look of the window changed in the settings. The window itself may not
                // exist, in which case there is nothing to repaint.
                if (rootView != null) applyAppearance()
                return START_STICKY
            }

            ACTION_TEMP_HIDE -> {
                handler.removeCallbacks(tempHideRunnable)
                tempHideActive = true
                applyOverlayVisibility()
                handler.postDelayed(tempHideRunnable, TEMP_HIDE_MS)
                return START_STICKY
            }
        }

        tier = intent?.getIntExtra(EXTRA_TIER, tier) ?: tier
        navMode = intent?.getBooleanExtra(EXTRA_NAV, navMode) ?: navMode

        if (intent?.action == ACTION_SHOW) {
            showOverlay()
            // The window may already exist: switching the size in the settings has to apply
            // to the running window instead of requiring a restart.
            applyTier()
        }

        return START_STICKY
    }

    /**
     * Applies or lifts pass-through mode on the running window.
     *
     * In pass-through the window becomes pure display: every touch lands on the app below.
     * The plate is kept fully transparent so even the background does not grab a tap. It can
     * only be switched off in the settings, because no touch reaches the window any more.
     */
    fun applyPassThrough() {
        passThrough = Settings.floatingPassThrough
        if (rootView != null) {
            applyPassThroughToParams()
            applyLayout()
            // A pass-through window does not answer a tap, so the idle fade is the only thing
            // that can dim it; re-schedule so a settings change never leaves it stuck bright.
            scheduleIdleFade()
        }
    }

    /**
     * Sets the window flags according to [passThrough].
     *
     * A normal window keeps FLAG_NOT_TOUCH_MODAL so touches outside it reach the map. A
     * pass-through window adds FLAG_NOT_TOUCHABLE: the window stops receiving every touch and
     * the whole area becomes transparent to the screen, so a tap lands on the app below.
     */
    private fun applyPassThroughToParams() {
        val params = layoutParams ?: return
        // Pass-through only takes effect once the window is locked. A locked window already
        // ignores its own taps, and adding FLAG_NOT_TOUCHABLE forwards them to the app below
        // (the navigation map, a music app, …), so the screen underneath stays usable while
        // the lyrics keep showing. An unlocked window stays fully touchable, so the driver can
        // still drag and control it, and the long-press that locks/unlocks keeps working.
        val pass = passThrough && locked
        if (pass) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
    }

    override fun onDestroy() {
        sleepTimerManager.removeListener(this)
        reverseSafetyManager.stop()
        disposables.clear()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(idleFadeRunnable)
        handler.removeCallbacks(tempHideRunnable)
        handler.removeCallbacks(collapseDetailRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    // region window

    private fun showOverlay() {
        // Any of the gates can veto the window: the app's own screens and the reverse/360
        // safety gate (which now also carries the zero-permission camera signals). Skip
        // creation entirely; applyOverlayVisibility removes it again when a gate asks.
        if (shouldHideOverlay()) return
        if (rootView == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(this)
            ) {
                noteOverlayFailure("overlay permission missing")
                stopSelf()
                return
            }

            rootView = createView()
            if (rootView == null) {
                stopSelf()
                return
            }
            currentIsBanner = isBanner(tier)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                // Not focusable, so the navigation app keeps the keyboard and the steering
                // wheel focus while the window is on screen.
                // FLAG_NOT_TOUCH_MODAL is essential: without it the window is touch-modal and
                // swallows every touch on the screen, also the ones that land outside of the
                // window itself. The map below the window would then be completely dead.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(DEFAULT_MARGIN_DP)
            y = dp(DEFAULT_TOP_DP)
            applyPassThroughToParams()
        }

        layoutParams = params
            try {
                windowManager?.addView(rootView, params)
            } catch (all: Exception) {
                // A missing overlay permission may still be reported here on some ROMs
                noteOverlayFailure("addView failed", all)
                rootView = null
                stopSelf()
                return
            }

            restorePosition()
        }

        applyTier()
        updateState()
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
        scheduleIdleFade()
        // The window is up: start (or keep) the reverse/360 safety gate so the camera view
        // hides it and pauses the music. start() is idempotent, so re-shows are harmless.
        reverseSafetyManager.start()
    }

    /**
     * Writes the reason the overlay could not be shown into a file.
     *
     * A floating window that silently never appears is impossible to diagnose from the driver
     * seat: the app looks fine, only the window is missing. The file is small and it is written
     * only on failure.
     *
     * The file goes into the **public external storage**, not into the private files directory:
     * on a head unit the file manager runs as a different user and has no access to
     * `/data/data/<package>/files`, so a log there could never be read. The public download
     * folder is reachable from the file manager and from an USB stick.
     */
    private fun noteOverlayFailure(reason: String, error: Throwable? = null) {
        val detail = error?.let { " ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val line = "${java.util.Date()} $reason$detail\n"

        // The private directory always works, it is only used as the fallback.
        try {
            java.io.File(filesDir, OVERLAY_FAILURE_FILE).appendText(line)
        } catch (all: Exception) {
            // Nothing else to do
        }

        try {
            @Suppress("DEPRECATION")
            val external = getExternalFilesDir(null)
            if (external != null) {
                java.io.File(external, OVERLAY_FAILURE_FILE).appendText(line)
            }
        } catch (all: Exception) {
            // The external storage may not be mounted, the private copy is enough
        }

        // The last resort: the logcat, which can be read with `adb logcat`.
        Timber.w("Overlay failure: %s%s", reason, detail)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    @SuppressLint("InflateParams")
    private fun createView(): View? {
        // A Service does not get the activity theme, and the layout uses theme attributes such as
        // selectableItemBackgroundBorderless. Without wrapping it in the palette theme, inflating
        // the window throws "Failed to resolve attribute" on Android 7.
        val themed = ContextThemeWrapper(this, CarPalette.current().styleRes)
        val inflater = LayoutInflater.from(themed)
        // The banner is a different, horizontal layout: a song/controls panel beside two lines of
        // lyrics. It shares the same view ids, so the same binding code drives both.
        val layoutRes = if (isBanner(tier)) R.layout.floating_player_banner else R.layout.floating_player
        val view = try {
            inflater.inflate(layoutRes, null)
        } catch (all: Exception) {
            // A broken overlay must never take the whole app down while the user is driving
            noteOverlayFailure("inflate failed", all)
            return null
        }

        headerView = view.findViewById(R.id.floating_header)
        titleView = view.findViewById(R.id.floating_title)
        artistView = view.findViewById(R.id.floating_artist)
        lyricsView = view.findViewById(R.id.floating_lyrics)
        lyricsMinView = view.findViewById(R.id.floating_lyrics_min)
        progressView = view.findViewById(R.id.floating_progress)
        controlsView = view.findViewById(R.id.floating_controls)
        closeView = view.findViewById(R.id.floating_close)
        albumArt = view.findViewById(R.id.floating_art)
        textBlock = view.findViewById(R.id.floating_text)
        previousButton = view.findViewById(R.id.floating_previous)
        nextButton = view.findViewById(R.id.floating_next)
        playButton = view.findViewById(R.id.floating_play)
        sleepButton = view.findViewById(R.id.floating_sleep)
        statusView = view.findViewById(R.id.floating_status)

        // The marquee of the title only moves when the view is marked as selected. The window
        // itself can never take the focus, because it must not steal the keys from the
        // navigation app, so the flag has to be set on the text view directly.
        titleView?.isSelected = true
        titleView?.marqueeRepeatLimit = Int.MAX_VALUE

        view.findViewById<View>(R.id.floating_previous).setOnClickListener {
            if (locked) return@setOnClickListener
            val now = System.currentTimeMillis()
            if (CarTransport.isDebounced(lastPreviousClickAt, now)) return@setOnClickListener
            lastPreviousClickAt = now
            onPreviousPressed()
            wake()
        }
        view.findViewById<View>(R.id.floating_play).setOnClickListener {
            if (locked) return@setOnClickListener
            val player = controller
            if (player == null) {
                connectController()
                return@setOnClickListener
            }

            if (player.isPlaying) player.pause() else player.play()
            updateState()
            wake()
        }
        view.findViewById<View>(R.id.floating_next).setOnClickListener {
            if (locked) return@setOnClickListener
            val now = System.currentTimeMillis()
            if (CarTransport.isDebounced(lastNextClickAt, now)) return@setOnClickListener
            lastNextClickAt = now
            seekNextAndKeepPlaying()
            wake()
        }
        view.findViewById<View>(R.id.floating_close).setOnClickListener {
            if (locked) return@setOnClickListener
            stopSelf()
        }
        sleepButton?.setOnClickListener {
            if (locked) return@setOnClickListener
            sleepTimerManager.cycle()
            showSleepToast()
            wake()
        }

        view.setOnTouchListener(DragListener())
        view.setOnClickListener { onWindowTap() }

        // A fresh view has no text yet, whatever the state was before the window was rebuilt
        stateInitialized = false
        return view
    }

    /**
     * A tap on the window body (not on a control button): bring the app to the foreground.
     *
     * When the window is not locked a tap means "I want the full player", so the app is pulled to
     * the front instead of toggling playback here. The control buttons keep their own handlers,
     * and a locked window ignores taps entirely.
     */
    private fun onWindowTap() {
        if (locked) return
        openApp()
    }

    /**
     * Brings the app's main activity to the front. The window is drawn over other apps, so a tap
     * on it is the driver's way of saying "open the player": the existing task is reordered to the
     * front rather than restarted, so the app keeps its place.
     */
    private fun openApp() {
        val intent = Intent(this, NavigationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        try {
            startActivity(intent)
        } catch (all: Exception) {
            Timber.w(all, "Could not open the app from the floating window")
        }
    }

    /**
     * The previous button.
     *
     * The button means two different things depending on the position: in the middle of a song
     * it goes back to the start of that song, at the very beginning it changes to the song
     * before. That is the behaviour of every known music player, and a driver expects it.
     *
     * The song before the current one only exists when the queue has more than one entry, so
     * the button is greyed out at the first song of the queue.
     */
    private fun onPreviousPressed() {
        val player = controller ?: return
        val position = player.currentPosition
        val hasPrevious = player.hasPreviousMediaItem()

        when (CarTransport.previousAction(position, hasPrevious)) {
            CarTransport.PreviousAction.RESTART_CURRENT -> {
                player.seekTo(0)
            }

            CarTransport.PreviousAction.GO_TO_PREVIOUS -> {
                player.seekToPreviousMediaItem()
                keepPlaying()
            }

            CarTransport.PreviousAction.NONE -> {
                // Nothing to do. The button is greyed out, so this is only a safety net.
                return
            }
        }
        updateState()
    }

    /**
     * Switches to the next song and makes sure it starts playing on its own.
     *
     * The window is the only control while navigating, so a skipped or errored track must never
     * leave the music silent: the driver taps next and the new song has to come up by itself
     * instead of waiting for a second tap on the play button.
     */
    private fun seekNextAndKeepPlaying() {
        val player = controller ?: return
        player.seekToNext()
        keepPlaying()
        updateState()
    }

    /**
     * Restores playback when a track switch left the player ready but stopped.
     *
     * When the previous track ended in an error, the player keeps the error and turns off
     * play-when-ready, so the new track sits there silent. Resetting both makes the new track
     * start immediately. A track that is already playing is not touched.
     */
    private fun keepPlaying() {
        val player = controller ?: return
        if (player.isPlaying) return
        if (player.playerError != null || !player.playWhenReady) {
            player.prepare()
            player.play()
        }
    }

    /**
     * Shows or hides the detail block (cover, title, artist) of the minimal and banner tiers.
     *
     * These tiers start a track switch with the song information on screen so the driver sees
     * what just changed, and then the information fades away leaving only the lyrics. The
     * collapse is timed by [collapseDetailRunnable]; while it is visible the lyric view is
     * pushed aside, and after the time the lyric view takes over the space.
     */
    private fun applyDetailVisibility() {
        // Only the minimal and the banner tiers have both a detail block and a lyric block.
        if (tier != TIER_LYRICS_MIN && !isBanner(tier)) return
        val root = rootView ?: return

        if (detailVisible && !passThrough) {
            // Show the song information and keep the lyric block out of the way while it is
            // there; the height grows to fit the detail block.
            headerView?.visibility = View.VISIBLE
            titleView?.visibility = View.VISIBLE
            artistView?.visibility = View.VISIBLE
            albumArt?.visibility = View.VISIBLE
            lyricsView?.visibility = View.GONE
            lyricsMinView?.visibility = View.GONE
        } else {
            // Settle onto the lyrics alone. The detail views are hidden so the window height
            // follows the number of lyric lines and the font size.
            headerView?.visibility = View.GONE
            titleView?.visibility = View.GONE
            artistView?.visibility = View.GONE
            albumArt?.visibility = View.GONE
            lyricsView?.visibility = if (tier == TIER_LYRICS) View.VISIBLE else View.GONE
            lyricsMinView?.visibility = if (tier == TIER_LYRICS_MIN || isBanner(tier)) View.VISIBLE else View.GONE
        }
        // The block that is shown now needs its layout re-measured.
        applyLayout()
    }

    /**
     * The number of lyric lines the window shows, 1 to 5, from the settings.
     *
     * The minimal and the banner tiers are wrapped in a vertical box that holds this many
     * lines; the height of the window follows the number and the font size, so a driver who
     * wants more lines simply gets a taller window.
     */
    private fun lyricLines(): Int = Settings.floatingLyricLines.coerceIn(1, 5)

    /**
     * Applies the lyric line count to the lyric views of the minimal and the banner tiers.
     */
    private fun applyLyricLineCount() {
        val lines = lyricLines()
        lyricsMinView?.maxLines = lines
        lyricsMinView?.minLines = lines
        lyricsView?.maxLines = lines + 1
        lyricsView?.minLines = lines
    }

    /**
     * Applies the geometry of the current tier. Only visibility and the width change, so the
     * window keeps its position and the transition is a single relayout.
     */
    private fun applyTier() {
        val root = rootView ?: return
        val params = layoutParams ?: return

        // Switching to or from the banner layout needs a fresh view, because the two layouts do
        // not share every view. Recreate instead of trying to repurpose a layout that lacks the
        // banner's views; the flag guards against looping because it is updated on rebuild.
        if (isBanner(tier) != currentIsBanner) {
            removeOverlay()
            rootView = null
            showOverlay()
            return
        }

        val isBannerTier = isBanner(tier)
        val showsCover = tier == TIER_STANDARD || tier == TIER_LYRICS
        val showsArtist = tier == TIER_STANDARD || tier == TIER_LYRICS || tier == TIER_COMPACT
        val showsHeader = tier != TIER_LYRICS_MIN && !isBannerTier
        val showsLyrics = tier == TIER_LYRICS
        val showsLyricsMin = tier == TIER_LYRICS_MIN || isBannerTier
        val showsProgress = tier == TIER_STANDARD || tier == TIER_LYRICS
        // A locked window ignores taps, so the buttons would be decoration that does nothing.
        // They are hidden instead, which leaves the window as a plain text plate.
        val showsControls = tier != TIER_TITLE && tier != TIER_LYRICS_MIN && !locked

        headerView?.visibility = if (showsHeader) View.VISIBLE else View.GONE
        albumArt?.visibility = if (showsCover) View.VISIBLE else View.GONE
        artistView?.visibility = if (showsArtist) View.VISIBLE else View.GONE
        lyricsView?.visibility = if (showsLyrics) View.VISIBLE else View.GONE
        lyricsMinView?.visibility = if (showsLyricsMin) View.VISIBLE else View.GONE
        progressAllowed = showsProgress
        // The smallest tier gives every pixel to the song title, so the state line would
        // push the title out of the window.
        statusAllowed = tier != TIER_TITLE
        // The bar is only drawn once a track with a length is loaded, see updateState()
        progressView?.visibility = View.GONE
        controlsView?.visibility = if (showsControls) View.VISIBLE else View.GONE
        // The close button is useless and dangerous while navigating: it is hidden there. It is
        // also hidden in the smallest tier, where every pixel goes to the song title; the window
        // can still be closed from the settings or from the player menu. The banner keeps it,
        // docked to the far edge of the strip.
        closeView?.visibility =
            if (navMode || tier == TIER_TITLE || locked) View.GONE else View.VISIBLE

        // The mini tiers have no cover, so the header would otherwise start with an empty gap
        if (!showsCover) {
            (albumArt?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.marginEnd = 0
            }
        }

        applyLyricLineCount()

        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.alpha = if (navMode) NAV_ALPHA else 1f

        // Nav mode always docks to the lower left, away from the junction preview in the top right.
        // The height is only known after the layout pass, so it is positioned twice: once with an
        // estimate, once with the real size.
        if (navMode && !isBannerTier) {
            params.x = dp(DEFAULT_MARGIN_DP)
            positionNavigationDock(root)
        } else if (isBannerTier) {
            // A banner is a horizontal strip: full screen width, or half of it, with the lyrics
            // filling the remaining width beside the song/controls panel.
            layoutBanner(root, dockBottom = navMode)
        } else {
            // A freely dragged window keeps its position; only the first appearance (no saved
            // position yet) docks to the side chosen in the settings.
            val screen = screenSize()
            val margin = dp(DEFAULT_MARGIN_DP)
            val width = dp(widthForTier())
            params.width = width
            val docked = if (Settings.floatingSide == "left") {
                margin
            } else {
                (screen.x - width - margin).coerceAtLeast(margin)
            }
            val x = if (hasSavedPosition()) Settings.floatingX else docked
            params.x = x.coerceIn(margin, (screen.x - width - margin).coerceAtLeast(margin))
        }

        applyLayout()

        if (isBannerTier) {
            // The banner reorders its own children (panel, lyrics, close) for the chosen side;
            // the vertical mirror logic needs the header, which the banner does not have.
            applyBannerLayout()
        } else {
            // The mirror follows the side the window is docked to, so it has to be reapplied
            // whenever the tier or the side changes.
            applyMirror()
        }

        // The background opacity and the text colour belong to the look of the window, so they
        // are applied with the geometry.
        applyAppearance()

        // The title is the only content of the smallest tier: let it use the whole width
        titleView?.isSingleLine = true
        root.isFocusable = false
        root.isFocusableInTouchMode = false

        // The minimal and banner tiers decide between the detail block and the lyrics alone
        // after every re-layout, so a change of the font size or the tier settles the block.
        applyDetailVisibility()
    }

    /**
     * Applies the look of the window: the opacity of its background and the colour of its text.
     *
     * The background is not a fixed drawable but a shape that is rebuilt here, because the
     * opacity is a setting and a drawable cannot read it. At an opacity of 0 the shape has no
     * fill and no border, so only the text is drawn over the map.
     *
     * The text carries a shadow that grows as the background fades, because without the plate
     * behind it the text would be unreadable over a bright part of the map.
     */
    private fun applyAppearance() {
        val root = rootView ?: return

        val opacity = Settings.floatingBgOpacity.coerceIn(
            FloatingAppearance.OPACITY_TRANSPARENT,
            FloatingAppearance.OPACITY_OPAQUE
        )
        val alpha = FloatingAppearance.backgroundAlpha(opacity)
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(BACKGROUND_CORNER_DP).toFloat()
            setColor(withAlpha(BACKGROUND_COLOR, alpha))
            // A fully transparent window has no border either, otherwise a thin frame would
            // still be visible over the map.
            if (alpha > 0) {
                setStroke(dp(1), withAlpha(BACKGROUND_BORDER, alpha))
            }
        }
        root.background = shape

        textColor = resolveTextColor()
        // The shadow is optional: with it off the text is painted flat, which some drivers find
        // sharper over a busy map. When it is on, it still grows as the background fades.
        val shadowEnabled = Settings.floatingTextShadow
        val shadow = if (shadowEnabled) FloatingAppearance.shadowAlpha(opacity) else 0
        val shadowColor = withAlpha(Color.BLACK, shadow)
        listOf(titleView, artistView, lyricsView, lyricsMinView).forEach { view ->
            view?.setTextColor(textColor)
            if (shadowEnabled && shadow > 0) {
                view?.setShadowLayer(dp(SHADOW_RADIUS_DP).toFloat(), 0f, dp(1).toFloat(), shadowColor)
            } else {
                // A zero-radius shadow layer clears any previously drawn one.
                view?.setShadowLayer(0f, 0f, 0f, 0)
            }
        }

        // The lyrics font size is a shared setting, so it is applied whenever the look is rebuilt.
        val lyricsSp = Settings.lyricsFontSizeSp.coerceIn(12, 30)
        lyricsView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, lyricsSp.toFloat())
        lyricsMinView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, lyricsSp.toFloat())

        // The transport buttons follow the text, otherwise they would be the only thing on the
        // window that the driver cannot read.
        val tint = ColorStateList.valueOf(textColor)
        listOf(previousButton, playButton, nextButton, closeView).forEach { view ->
            (view as? ImageView)?.let { image ->
                image.imageTintList = tint
            }
        }

        progressView?.progressTintList = tint

        // The sleep button shows its own state: accent while a timer runs, window text colour
        // when it is off, so the countdown is visible without reading the window.
        applySleepTint(sleepTimerManager.isActive)

        // The lyrics are built with the colour, and the block is only rebuilt when the line
        // changes. The cache has to be invalidated, or the old colours stay on screen.
        lyricIndex = -1
        updateFloatingLyrics(controller?.currentPosition ?: 0L)
        // Keep the pass-through flag in step with the setting and the lock state whenever the
        // window is repainted, so a change made while the window was closed applies on open.
        applyPassThrough()
    }

    /**
     * The colour the text is painted in, resolved from the setting.
     *
     * The theme colour follows the palette and the day and night mode of the head unit, the
     * other ones are fixed.
     */
    private fun resolveTextColor(): Int {
        return when (FloatingAppearance.colorFor(Settings.floatingTextColor)) {
            FloatingAppearance.COLOR_WHITE -> Color.WHITE
            FloatingAppearance.COLOR_AMBER -> AMBER_COLOR
            FloatingAppearance.COLOR_NEON_GREEN -> NEON_GREEN_COLOR
            FloatingAppearance.COLOR_CYAN -> CYAN_COLOR
            FloatingAppearance.COLOR_MAGENTA -> MAGENTA_COLOR
            FloatingAppearance.COLOR_ORANGE -> ORANGE_COLOR
            FloatingAppearance.COLOR_RED -> RED_COLOR
            FloatingAppearance.COLOR_YELLOW -> YELLOW_COLOR
            // A dark grey that reads well over the pale day map, once the background plate is
            // transparent enough to let the map show through.
            FloatingAppearance.COLOR_DARK_GRAY -> DARK_GRAY_COLOR
            // A bright blue, distinct from the amber route lines and from the deep night-map blue.
            FloatingAppearance.COLOR_BLUE -> BLUE_COLOR
            // A vivid violet, the colour that stands out most against the yellow/amber map palette.
            FloatingAppearance.COLOR_VIOLET -> VIOLET_COLOR
            // The accent colour is brightened here. The palette accent is a rather dark
            // blue, and as the colour of a text over the map it has too little contrast.
            // The window can also be fully transparent, so the text has to carry itself.
            FloatingAppearance.COLOR_ACCENT -> CarTheme.readableAccent(this)
            else -> CarTheme.onSurface(this)
        }
    }

    /** The same colour with a different alpha, used for the background and the shadow. */
    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    /** Docks the window to the lower left edge, clear of the road preview of the map. */
    private fun positionNavigationDock(root: View) {
        root.post {
            val params = layoutParams ?: return@post
            val screen = screenSize()
            val height = if (root.height > 0) root.height else dp(NAV_ESTIMATED_HEIGHT_DP)
            params.x = dp(DEFAULT_MARGIN_DP)
            params.y = (screen.y - height - dp(NAV_BOTTOM_DP))
                .coerceAtLeast(dp(DEFAULT_MARGIN_DP))
            applyLayout()
        }
    }

    private fun widthForTier(): Int = when (tier) {
        TIER_TITLE -> WIDTH_TITLE_DP
        TIER_COMPACT -> WIDTH_COMPACT_DP
        TIER_STANDARD -> WIDTH_STANDARD_DP
        TIER_LYRICS -> WIDTH_LYRICS_DP
        else -> WIDTH_LYRICS_MIN_DP
    }

    /** True for the horizontal banner tiers, which use a different layout file. */
    private fun isBanner(tier: Int): Boolean =
        tier == TIER_BANNER_FULL || tier == TIER_BANNER_HALF

    /** Width of the banner on screen: the full width minus margins, or half of it. */
    private fun computeBannerWidth(): Int {
        val screen = screenSize()
        val margin = dp(DEFAULT_MARGIN_DP)
        val full = (screen.x - 2 * margin).coerceAtLeast(margin)
        return if (tier == TIER_BANNER_FULL) {
            full
        } else {
            ((screen.x / 2) - margin).coerceAtLeast(margin)
        }
    }

    /**
     * Positions the horizontal banner: it spans the chosen width (full or half screen), sits at the
     * chosen screen side for the half-width variant, and docks to the bottom when in navigation
     * mode. A full-width banner ignores the screen side, because it already reaches both edges.
     */
    private fun layoutBanner(root: View, dockBottom: Boolean) {
        val params = layoutParams ?: return
        val screen = screenSize()
        val margin = dp(DEFAULT_MARGIN_DP)
        val width = computeBannerWidth()
        params.width = width

        if (tier == TIER_BANNER_FULL) {
            // A full-width banner spans the screen, so it stays pinned to the left margin; only
            // its height is free to drag.
            params.x = margin
        } else if (hasSavedPosition()) {
            // Keep the freely dragged horizontal position.
            params.x = Settings.floatingX
        } else {
            params.x = if (Settings.floatingSide == "left") {
                margin
            } else {
                (screen.x - width - margin).coerceAtLeast(margin)
            }
        }
        params.x = params.x.coerceIn(margin, (screen.x - width - margin).coerceAtLeast(margin))

        if (dockBottom) {
            val height = if (root.height > 0) root.height else dp(NAV_ESTIMATED_HEIGHT_DP)
            params.y = (screen.y - height - dp(NAV_BOTTOM_DP)).coerceAtLeast(margin)
        } else if (params.y < margin) {
            // A banner that has never been placed is dropped near the top, like a real banner.
            params.y = margin
        }

        // Keep the strip on the screen after a rotation or a resize.
        val viewHeight = if (root.height > 0) root.height else dp(NAV_ESTIMATED_HEIGHT_DP)
        params.y = params.y.coerceIn(margin, (screen.y - viewHeight - margin).coerceAtLeast(margin))
        applyLayout()
    }

    /**
     * Reorders the three children of the banner (panel, lyrics, close) so the song/controls panel
     * sits on the chosen side and the close button on the far edge. The lyrics always fill the
     * middle and keep their weight, so only the ends swap.
     */
    private fun applyBannerLayout() {
        val root = rootView as? ViewGroup ?: return
        val panel = root.findViewById<View>(R.id.floating_banner_panel) ?: return
        val lyrics = root.findViewById<View>(R.id.floating_lyrics_min) ?: return
        val close = root.findViewById<View>(R.id.floating_close) ?: return

        val right = Settings.floatingBannerPanelSide == "right"
        val order = if (right) listOf(close, lyrics, panel) else listOf(panel, lyrics, close)
        order.forEach { root.removeView(it) }
        order.forEach { root.addView(it) }
    }

    /**
     * Mirrors the window when it is docked to the left edge of the screen.
     *
     * The driver reaches over the centre console, so the buttons that are used while driving
     * have to sit on the edge of the window that is closest to the hand. Docked on the left,
     * that is the right edge of the window, so the transport buttons are reversed and the
     * close button moves to the far side. The text is only aligned to the other edge, never
     * mirrored: mirrored letters cannot be read.
     */
    private fun applyMirror() {
        val header = headerView as? ViewGroup ?: return
        val controls = controlsView as? ViewGroup ?: return
        val close = closeView ?: return
        val art = albumArt ?: return
        val text = textBlock ?: return
        val previous = previousButton ?: return
        val next = nextButton ?: return

        val mirrored = !navMode && Settings.floatingSide == "left"

        // Transport buttons: the play button stays in the middle, the two others swap sides
        controls.removeView(previous)
        controls.removeView(next)
        if (mirrored) {
            controls.addView(next, 0)
            controls.addView(previous, controls.childCount)
        } else {
            controls.addView(previous, 0)
            controls.addView(next, controls.childCount)
        }

        // Header: cover art and close button swap sides, the text keeps its place
        header.removeView(art)
        header.removeView(close)
        if (mirrored) {
            header.addView(close, 0)
            header.addView(art, header.childCount)
        } else {
            header.addView(art, 0)
            header.addView(close, header.childCount)
        }

        // The default text alignment follows the gravity, so the title lines up with the edge
        // the window is docked to. Mirrored letters would be unreadable, so the text itself
        // is never mirrored.
        val alignment = if (mirrored) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
        }
        titleView?.gravity = alignment
        artistView?.gravity = alignment

        // The cover sits on the other side now, so the gap has to move with it
        (text.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = if (mirrored) 0 else dp(10)
            it.marginEnd = if (mirrored) dp(10) else 0
        }
    }

    private fun applyLayout() {
        val params = layoutParams ?: return
        try {
            windowManager?.updateViewLayout(rootView, params)
        } catch (all: Exception) {
            Timber.w(all, "Could not update the floating window")
        }
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (all: Exception) {
            Timber.w(all, "Could not remove the floating window")
        }
        rootView = null
    }

    /**
     * Shows or removes the overlay view according to [FloatingPlayerService.hostWantsHide].
     *
     * Called from the static [FloatingPlayerService.setHostHidden] whenever the host activity
     * changes the page it is on. The window is not hidden with [View.GONE], because a gone view
     * still owns a touch hit-region over the map; it is removed from the window manager entirely
     * and rebuilt on demand, which is cheap and leaves the map fully interactive.
     */
    /**
     * The gates that can veto the window, combined with a logical OR:
     *  - the host app is on one of its own screens;
     *  - the reverse / 360 safety gate is engaged, either by the BYD car API or by a
     *    zero-permission camera signal (surface reclaim, window hidden, camera process).
     */
    private fun shouldHideOverlay(): Boolean =
        FloatingPlayerService.hostWantsHide ||
            FloatingPlayerService._reverseSafetyActive ||
            tempHideActive

    /** The single place that shows or removes the overlay view. */
    private fun applyOverlayVisibility() {
        if (shouldHideOverlay()) {
            if (rootView != null) removeOverlay()
        } else if (rootView == null) {
            showOverlay()
        }
    }

    /** Called by the static reverse-safety accessors; the gates are evaluated together. */
    fun applyReverseSafetyVisibility() {
        applyOverlayVisibility()
    }


    // endregion

    // region position

    /**
     * The screen the window lives on.
     *
     * The deprecated `defaultDisplay.getRealSize` is only used on old Android versions. On
     * Android 11 and later the current window metrics are used, because they follow the
     * display the app is really on, also on a head unit with more than one screen.
     */
    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val point = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                point.set(bounds.width(), bounds.height())
                return point
            }
        }
        windowManager?.defaultDisplay?.getRealSize(point)
        if (point.x <= 0 || point.y <= 0) {
            // The size could not be read. The window is then placed relative to the left
            // top corner, which works on every screen, instead of guessing a size that
            // would put the window off the screen on a different head unit.
            Timber.w("Could not read the screen size")
            point.set(MIN_SCREEN_WIDTH_PX, MIN_SCREEN_HEIGHT_PX)
        }
        return point
    }

    /**
     * The screen of a head unit can be rotated, and the density can differ per model.
     * The window has to be placed again, because the pixel size of the window and the
     * absolute position of the docked edge were calculated for the old geometry.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val params = layoutParams ?: return
        val root = rootView ?: return

        // The width is a dp value, so it has to be recalculated for the new density. The banner
        // width follows the screen size, so it is recomputed and repositioned as a whole.
        if (isBanner(tier)) {
            layoutBanner(root, dockBottom = navMode)
            savePosition()
            return
        }

        params.width = dp(widthForTier())

        val screen = screenSize()
        val viewWidth = if (root.width > 0) root.width else dp(widthForTier())
        val viewHeight = if (root.height > 0) root.height else dp(NAV_ESTIMATED_HEIGHT_DP)
        val margin = dp(DEFAULT_MARGIN_DP)

        // The window must stay on the screen. Without the upper bound it would hang over
        // the edge, and the window is drawn outside of the screen because of
        // FLAG_LAYOUT_NO_LIMITS.
        params.x = params.x.coerceIn(margin, (screen.x - viewWidth - margin).coerceAtLeast(margin))
        params.y = params.y.coerceIn(margin, (screen.y - viewHeight - margin).coerceAtLeast(margin))

        applyLayout()
        savePosition()
    }

    /** Restores the last saved position, or docks the window when it is shown for the first time. */
    private fun restorePosition() {
        val params = layoutParams ?: return
        val screen = screenSize()
        val margin = dp(DEFAULT_MARGIN_DP)

        if (isBanner(tier)) {
            // A banner is dropped near the top of the screen (or wherever it was last dragged to).
            // The full-width variant reaches both edges, so it is always pinned to the left margin.
            val bannerWidth = computeBannerWidth()
            if (Settings.floatingX < 0 || Settings.floatingY < 0) {
                params.x = if (tier == TIER_BANNER_FULL || Settings.floatingSide == "left") {
                    margin
                } else {
                    (screen.x - bannerWidth - margin).coerceAtLeast(margin)
                }
                params.y = margin
                applyLayout()
                savePosition()
                return
            }
            val viewHeight = rootView?.height?.takeIf { it > 0 } ?: dp(NAV_ESTIMATED_HEIGHT_DP)
            params.x = Settings.floatingX.coerceIn(
                margin, (screen.x - bannerWidth - margin).coerceAtLeast(margin)
            )
            params.y = Settings.floatingY.coerceIn(
                margin, (screen.y - viewHeight - margin).coerceAtLeast(margin)
            )
            applyLayout()
            return
        }

        if (navMode) {
            params.x = dp(DEFAULT_MARGIN_DP)
            // The bottom of the navigation app is unknown, so the height is estimated.
            // The position must not become negative on a short screen.
            params.y = (screen.y - dp(NAV_ESTIMATED_HEIGHT_DP) - dp(NAV_BOTTOM_DP))
                .coerceAtLeast(margin)
            applyLayout()
            return
        }

        val savedX = Settings.floatingX
        val savedY = Settings.floatingY
        if (savedX < 0 || savedY < 0) {
            // First run: dock to the right edge, halfway down. The home screen puts its tile
            // grid on the left and the transport controls along the bottom, so the right flank
            // is the only area where an untouched window does not cover a control.
            val viewWidth = rootView?.width?.takeIf { it > 0 } ?: dp(widthForTier())
            params.x = (screen.x - viewWidth - margin).coerceAtLeast(margin)
            params.y = ((screen.y - dp(NAV_ESTIMATED_HEIGHT_DP)) / 2).coerceAtLeast(margin)
            Settings.floatingSide = "right"
            applyLayout()
            savePosition()
            return
        }

        // The window must stay completely on the screen. The upper bound is the position
        // at which the right edge of the window touches the right edge of the screen,
        // otherwise the window would hang over it.
        val viewWidth = rootView?.width?.takeIf { it > 0 } ?: dp(widthForTier())
        val viewHeight = rootView?.height?.takeIf { it > 0 } ?: dp(NAV_ESTIMATED_HEIGHT_DP)
        params.x = savedX.coerceIn(margin, (screen.x - viewWidth - margin).coerceAtLeast(margin))
        params.y = savedY.coerceIn(margin, (screen.y - viewHeight - margin).coerceAtLeast(margin))
        applyLayout()
    }

    private fun savePosition() {
        val params = layoutParams ?: return
        Settings.floatingX = params.x
        Settings.floatingY = params.y
    }

    /**
     * After a drag the window docks to the nearest side. A window half hanging over the edge of
     * a touch screen is hard to grab again, and a docked window never covers the road preview
     * that the navigation app draws on the other side.
     */
    /**
     * Ends a drag by keeping the window exactly where it was dropped. The only correction is a clamp
     * to the screen, so a freely placed window stays put instead of jumping back to a docked edge.
     */
    private fun finishDrag() {
        val params = layoutParams ?: return
        val root = rootView ?: return
        val screen = screenSize()
        val margin = dp(DEFAULT_MARGIN_DP)
        val viewWidth = if (root.width > 0) root.width else dp(widthForTier())
        val viewHeight = if (root.height > 0) root.height else dp(NAV_ESTIMATED_HEIGHT_DP)
        params.x = params.x.coerceIn(margin, (screen.x - viewWidth - margin).coerceAtLeast(margin))
        params.y = params.y.coerceIn(margin, (screen.y - viewHeight - margin).coerceAtLeast(margin))
        applyLayout()
        savePosition()
    }

    /** True once the window has been placed by hand, so re-shows keep that free position. */
    private fun hasSavedPosition(): Boolean = Settings.floatingX >= 0 && Settings.floatingY >= 0

    // endregion

    // region idle fade and lock

    /** Dims the window after a few seconds without a touch, so it stops drawing the eye. */
    private fun scheduleIdleFade() {
        handler.removeCallbacks(idleFadeRunnable)
        handler.postDelayed(idleFadeRunnable, IDLE_TIMEOUT_MS)
    }

    private fun fadeToIdle() {
        val root = rootView ?: return
        if (navMode) return
        // The driver can choose how dim the window goes when it fades after a few idle
        // seconds. 100 keeps it at full brightness, lower values fade it more.
        val base = Settings.floatingIdleBrightness.coerceIn(0, 100)
        // Lyrics cannot be read when the window is very dim: the tiers that show them get an
        // automatic boost so the words stay readable no matter how far the driver drops it.
        val lyricsTier = tier == TIER_LYRICS || tier == TIER_LYRICS_MIN || isBanner(tier)
        val idle = if (lyricsTier) {
            ((base + IDLE_LYRIC_BOOST) / 100f).coerceAtMost(1f)
        } else {
            base / 100f
        }
        root.animate().alpha(idle).setDuration(FADE_MS).start()
    }

    /** Wakes the window up after a touch and restarts the idle countdown. */
    private fun wake() {
        val root = rootView ?: return
        if (root.alpha < 1f) {
            root.animate().alpha(if (navMode) NAV_ALPHA else 1f).setDuration(FADE_MS).start()
        }
        scheduleIdleFade()
    }

    private fun toggleLock() {
        locked = !locked
        Settings.floatingLocked = locked
        Util.toast(
            if (locked) R.string.floating_locked_toast else R.string.floating_unlocked_toast,
            context = this
        )
        // The buttons of a locked window are hidden, so the window has to be laid out again.
        // Without this the lock would only take effect after the window was restarted.
        applyTier()
        wake()
        // The pass-through flag follows the lock state, so locking or unlocking the window
        // immediately starts or stops forwarding taps to the app below.
        applyPassThrough()
    }

    // endregion

    private fun updateState() {
        val player = controller
        val track = player?.currentMediaItem?.toTrack()

        if (!stateInitialized || track?.id != currentTrackId) {
            stateInitialized = true
            currentTrackId = track?.id
            currentTrack = track
            titleView?.text = track?.title ?: getString(R.string.player_pane_idle)
            artistView?.text = track?.artist
                ?: if (track == null) getString(R.string.player_pane_idle_hint) else ""
            imageLoaderProvider.executeOn { loader ->
                val view = albumArt
                if (view != null) loader.loadImage(view, track, true, 0)
            }
            // A new song: show the detail block again, then let it collapse after the chosen
            // seconds. This is what makes the half-width / minimal window start with the
            // cover + title + artist and then settle onto the lyrics alone.
            if (track != null && (tier == TIER_LYRICS_MIN || isBanner(tier))) {
                detailVisible = true
                lastTrackChangeAt = System.currentTimeMillis()
                handler.removeCallbacks(collapseDetailRunnable)
                val seconds = Settings.floatingDetailSeconds
                if (seconds > 0) {
                    handler.postDelayed(collapseDetailRunnable, seconds * 1000L)
                }
            }
            applyDetailVisibility()
            ensureLyrics(track)
        }

        playButton?.setImageResource(
            if (player?.isPlaying == true) R.drawable.media_pause else R.drawable.media_start
        )

        updateStatus(player)
        updateTransportAvailability(player)

        val duration = player?.duration ?: 0L
        // An empty progress track reads as a broken window, so it only appears with a song
        progressView?.visibility =
            if (progressAllowed && duration > 0) View.VISIBLE else View.GONE
        if (duration > 0) {
            val position = player?.currentPosition ?: 0L
            progressView?.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
            updateFloatingLyrics(position)
        } else if (waitingForNetwork) {
            // The song has no length, so the position cannot be read, but the driver still
            // has to know that the music is waiting for the network.
            updateFloatingLyrics(0L)
        }
    }

    /**
     * Greys out the transport buttons that cannot do anything.
     *
     * A button that does nothing looks like a broken button. The driver taps it again, and
     * again, and concludes that the whole window is dead. The previous button stays alive as
     * long as the current song can be restarted, so it is only greyed out at the very
     * beginning of the very first song of the queue.
     */
    private fun updateTransportAvailability(player: MediaController?) {
        if (player == null) return

        val position = player.currentPosition
        val previousAction =
            CarTransport.previousAction(position, player.hasPreviousMediaItem())

        previousButton?.isEnabled = previousAction != CarTransport.PreviousAction.NONE
        previousButton?.alpha =
            if (previousAction == CarTransport.PreviousAction.NONE) ALPHA_DISABLED else ALPHA_FULL
        nextButton?.isEnabled = player.hasNextMediaItem()
        nextButton?.alpha =
            if (player.hasNextMediaItem()) ALPHA_FULL else ALPHA_DISABLED
    }

    /**
     * Shows what the playback is doing right now.
     *
     * Without this the window only tells the driver whether the music is running. A song that
     * is being buffered looks exactly like a paused one, and a failed song looks like a song
     * that is playing. Both lead to the same wrong action: the driver starts the music again
     * and the music then comes back on its own.
     */
    private fun updateStatus(player: MediaController?) {
        val view = statusView ?: return
        if (!statusAllowed) {
            view.visibility = View.GONE
            return
        }

        // The error of the player is cleared when the next song is prepared, so it is read
        // from the player itself and not from an own flag.
        val hasError = player?.playerError != null
        if (hasError && errorSince == 0L) errorSince = System.currentTimeMillis()
        if (!hasError) errorSince = 0L

        val state = CarTransport.visualState(
            playbackState = player?.playbackState ?: Player.STATE_IDLE,
            isPlaying = player?.isPlaying == true,
            hasError = hasError,
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
        // The colours are the ones that mean something on a car: amber for a state that
        // resolves itself, red for a state that needs the driver.
        view.setTextColor(
            when (state) {
                CarTransport.VisualState.ERROR -> STATUS_COLOR_ERROR
                CarTransport.VisualState.WAITING_FOR_NETWORK -> STATUS_COLOR_ERROR
                else -> STATUS_COLOR_WARNING
            }
        )
    }

    private fun ensureLyrics(track: Track?) {
        val id = track?.id
        if (id == lyricsTrackId) return
        lyricsTrackId = id
        timedLyrics = emptyList()
        lyricIndex = -1

        if (track == null) {
            lyricsView?.text = ""
            lyricsMinView?.text = ""
            return
        }

        lyricsView?.setText(R.string.player_pane_lyrics_loading)

        val artist = track.artist.orEmpty()
        val title = track.title.orEmpty()
        val targetId = track.id

        Thread {
            val lyrics = try {
                // The endpoint with the time axis is asked first, because the plain one drops
                // every timestamp and the lyrics would not move along with the song.
                val service = MusicServiceFactory.getMusicService()
                runCatching { service.getSyncedLyrics(targetId) }.getOrNull()
                    ?.takeIf { it.timedLines.isNotEmpty() }
                    ?: service.getLyrics(artist, title)
            } catch (all: Exception) {
                Timber.w(all, "Could not load lyrics for the floating window")
                null
            }

            handler.post {
                if (lyricsTrackId != targetId) return@post
                val text = lyrics?.text
                val timed = lyrics?.timedLines?.map { TimedLyric(it.startMs, it.text) }
                    ?: emptyList()
                if (timed.isNotEmpty()) {
                    timedLyrics = timed
                    lyricIndex = -1
                    updateFloatingLyrics(controller?.currentPosition ?: 0L)
                } else {
                    // The server has no time axis for this song. The lines are spread over
                    // the length of the song, so the lyrics still move along with the music
                    // instead of freezing on the first three lines.
                    val duration = controller?.duration ?: 0L
                    val spread = LyricsParser.spreadPlainLyrics(text, duration)
                    if (spread.isEmpty()) {
                        val fallback = if (text.isNullOrBlank()) {
                            getString(R.string.player_pane_lyrics_empty)
                        } else {
                            text
                        }
                        lyricsView?.text = fallback
                        lyricsMinView?.text = fallback
                    } else {
                        timedLyrics = spread
                        lyricIndex = -1
                        updateFloatingLyrics(controller?.currentPosition ?: 0L)
                    }
                }
            }
        }.start()
    }

    /**
     * Shows the previous, current and next line of the lyrics, with the current one highlighted.
     * Three lines is the smallest block that still tells the driver where the song is, which is
     * what the floating window is for. The minimal tier shows only the current and the next line.
     */
    private fun updateFloatingLyrics(positionMs: Long) {
        // While the music waits for the network the lyrics would stand still anyway, so the
        // window shows the reason instead of a frozen line.
        if (waitingForNetwork) {
            if (lyricIndex != LYRIC_INDEX_WAITING) {
                lyricIndex = LYRIC_INDEX_WAITING
                showingNetworkMessage = true
                val message = getString(R.string.network_recovery_waiting)
                lyricsView?.text = message
                lyricsMinView?.text = message
            }
            return
        }

        // The music is playing again, but the song has no lyrics. The message of the outage
        // has to be taken off the window, otherwise it would stay there for the whole song.
        if (timedLyrics.isEmpty()) {
            if (showingNetworkMessage) {
                showingNetworkMessage = false
                lyricsView?.text = getString(R.string.player_pane_lyrics_empty)
                lyricsMinView?.text = getString(R.string.player_pane_lyrics_empty)
            }
            return
        }

        // Shift the position by the driver calibration so the highlighted line lines up with the
        // audio. Positive offset delays the lyrics, negative ones show them earlier.
        val index = LyricsParser.lineIndexAt(timedLyrics, positionMs - Settings.lyricsOffsetMs)
        if (index == lyricIndex) return
        lyricIndex = index

        if (index < 0) {
            lyricsView?.text = ""
            lyricsMinView?.text = ""
            return
        }

        // The highlighted line uses the colour the driver picked, the other lines the same
        // colour with a lower alpha, so the block stays one colour instead of two.
        val accent = textColor
        val dim = withAlpha(textColor, LYRIC_DIM_ALPHA)

        lyricsView?.text = buildLyricBlock(
            listOfNotNull(
                timedLyrics.getOrNull(index - 1),
                timedLyrics[index],
                timedLyrics.getOrNull(index + 1)
            ),
            highlightIndex = if (timedLyrics.getOrNull(index - 1) != null) 1 else 0,
            accent = accent,
            dim = dim
        )

        // Two lines only: the current one and what comes next
        lyricsMinView?.text = buildLyricBlock(
            listOfNotNull(timedLyrics[index], timedLyrics.getOrNull(index + 1)),
            highlightIndex = 0,
            accent = accent,
            dim = dim
        )
    }

    private fun buildLyricBlock(
        lines: List<TimedLyric>,
        highlightIndex: Int,
        accent: Int,
        dim: Int
    ): CharSequence {
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line.text)
            val highlight = index == highlightIndex
            builder.setSpan(
                ForegroundColorSpan(if (highlight) accent else dim),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (highlight) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (index != lines.lastIndex) builder.append('\n')
        }
        return builder
    }

    private fun buildNotification(): Notification {
        Util.ensureNotificationChannel(
            id = CHANNEL_ID,
            name = getString(R.string.floating_notification_channel),
            importance = androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW,
            notificationManager = androidx.core.app.NotificationManagerCompat.from(this)
        )

        // Manual fallback: one tap on the ongoing notification hides the window for a
        // short while. It works even when the head unit allows no accessibility service,
        // no usage access and no car API at all.
        val hideIntent = Intent(this, FloatingPlayerService::class.java).apply {
            action = ACTION_TEMP_HIDE
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            })
        val hidePending = PendingIntent.getService(this, NOTIFICATION_ID + 1, hideIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stat_play,
                getString(R.string.floating_temp_hide_action),
                hidePending
            )
            .build()
    }

    // region sleep timer

    /**
     * Confirms to the driver which duration is now running, or that the timer is off.
     *
     * The floating window is glanced at, not read, so the confirmation is a short toast and the
     * running state is shown by the button colour, not by a sentence on the window.
     */
    private fun showSleepToast() {
        val message = if (sleepTimerManager.isActive) {
            getString(R.string.sleep_timer_minutes, Settings.sleepTimerMinutes)
        } else {
            getString(R.string.sleep_timer_off)
        }
        Util.toast(message, context = this)
    }

    /**
     * Paints the sleep button in the accent colour while a timer runs and in the window text
     * colour otherwise. A running timer has to be visible at a glance, because the driver is
     * not looking at the window.
     */
    private fun applySleepTint(active: Boolean) {
        val color = if (active) CarTheme.readableAccent(this) else textColor
        sleepButton?.imageTintList = ColorStateList.valueOf(color)
    }

    override fun onSleepTimerActiveChanged(active: Boolean) {
        applySleepTint(active)
    }

    override fun onSleepTimerTick(remainingMillis: Long) {
        // The visible cue is the tint; the remaining time goes into the content description so it
        // is spoken and shown on long-press without adding a moving number to the window.
        val minutes = (remainingMillis / 60_000L).toInt().coerceAtLeast(1)
        sleepButton?.contentDescription = getString(R.string.sleep_timer_minutes, minutes)
    }

    override fun onSleepTimerFired() {
        // Pause but keep the queue and the position, so the driver resumes in the morning exactly
        // where the car left off. The window itself only shows the paused state.
        controller?.pause()
    }

    // endregion

    /** Moves the window while the driver drags it, and locks it on a long press. */
    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var longPressed = false

        private val longPressRunnable = Runnable {
            longPressed = true
            toggleLock()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            wake()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (locked) return true
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (Math.abs(dx) > dp(DRAG_SLOP_DP) || Math.abs(dy) > dp(DRAG_SLOP_DP))) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (!moved) return true

                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    // A full-width banner spans the screen, so only the vertical position is free;
                    // pin it back to the left margin instead of letting it drift sideways.
                    if (isBanner(tier) && tier == TIER_BANNER_FULL) {
                        params.x = dp(DEFAULT_MARGIN_DP)
                    }
                    // LAYOUT_NO_LIMITS lets the window leave the screen, clamp it back in
                    applyLayout()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (moved) {
                        finishDrag()
                    } else if (!longPressed) {
                        view.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * True when the host app is on a screen that should not show the floating window, the
         * home page for example. The activity sets this; the service only reads it to decide
         * whether the overlay view exists. A static flag avoids the timing problem of the
         * service's [onCreate] and the activity's [androidx.appcompat.app.AppCompatActivity.onResume]
         * racing each other.
         */
        @JvmStatic
        var hostWantsHide = false

        /** The live service instance, so a static helper can reach the running overlay. */
        @JvmStatic
        private var instance: FloatingPlayerService? = null

        /** The backing field of the reverse-safety gate; the public accessors live on the setter. */
        @JvmStatic
        @Volatile
        private var _reverseSafetyActive = false

        /**
         * The MediaController of the running service, or null when no service instance is up.
         *
         * The reverse-safety gate reaches the player through this static seam so it does not
         * have to hold a service reference. The read is null-safe: a missing controller simply
         * makes the gate skip the audio half, and the window half still works.
         */
        @JvmStatic
        fun controller(): MediaController? = instance?.controller

        /**
         * Re-reads the pass-through setting and updates the running window's touch flags.
         *
         * Called from the settings screen when the pass-through switch changes. The flag only
         * takes effect while the window is locked, so it is applied together with the lock.
         */
        @JvmStatic
        fun applyPassThroughFromSettings() {
            instance?.applyPassThrough()
        }

        /**
         * Flips the reverse-safety gate on or off for the overlay.
         *
         * Called by the [ReverseSafetyManager] on the main thread: when the gate engages the
         * overlay marks itself hidden-for-reverse, and when it releases the overlay clears the
         * mark and re-shows. The static flag survives a service recreate, so a brief service
         * restart during the reverse view keeps the window off.
         */
        @JvmStatic
        fun setReverseSafetyActive(active: Boolean) {
            _reverseSafetyActive = active
            instance?.applyReverseSafetyVisibility()
        }

        /**
         * Hides the overlay for the duration of the reverse view.
         *
         * The overlay reads the static flag and its own state; the call only forces an
         * immediate visibility update. When the safety gate is off the overlay keeps showing.
         */
        @JvmStatic
        fun hideForReverse() {
            _reverseSafetyActive = true
            instance?.applyReverseSafetyVisibility()
        }

        /**
         * Brings the overlay back after the reverse view is gone.
         *
         * Only when the safety gate is still on, so a driver who switched the gate off while
         * reversing keeps the overlay hidden until they reopen it on purpose.
         */
        @JvmStatic
        fun showForReverse() {
            _reverseSafetyActive = false
            instance?.applyReverseSafetyVisibility()
        }

        /** Tier order, from the smallest footprint to the largest. */
        const val TIER_TITLE = 0
        const val TIER_COMPACT = 1
        const val TIER_STANDARD = 2
        const val TIER_LYRICS = 3
        const val TIER_LYRICS_MIN = 4
        const val TIER_BANNER_FULL = 5
        const val TIER_BANNER_HALF = 6

        /** Stored values, also used by the settings list. */
        private const val KEY_OFF = "off"
        private const val KEY_TITLE = "title"
        private const val KEY_COMPACT = "compact"
        private const val KEY_STANDARD = "standard"
        private const val KEY_LYRICS = "lyrics"
        private const val KEY_LYRICS_MIN = "lyrics_min"
        private const val KEY_BANNER_FULL = "banner_full"
        private const val KEY_BANNER_HALF = "banner_half"

        private const val ACTION_SHOW = "org.moire.ultrasonic.FLOATING_SHOW"
        private const val ACTION_STOP = "org.moire.ultrasonic.FLOATING_STOP"
        private const val ACTION_NAV_ON = "org.moire.ultrasonic.FLOATING_NAV_ON"
        private const val ACTION_REFRESH = "org.moire.ultrasonic.FLOATING_REFRESH"
        private const val ACTION_TEMP_HIDE = "org.moire.ultrasonic.FLOATING_TEMP_HIDE"
        private const val EXTRA_TIER = "tier"
        private const val EXTRA_NAV = "nav"

        private const val CHANNEL_ID = "org.moire.ultrasonic.floating"
        private const val NOTIFICATION_ID = 4711
        private const val OVERLAY_FAILURE_FILE = "floating-failure.txt"
        private const val UPDATE_INTERVAL_MS = 500L
        private const val IDLE_TIMEOUT_MS = 3000L
        private const val TEMP_HIDE_MS = 30_000L
        private const val FADE_MS = 220L
        private const val LONG_PRESS_MS = 700L
        private const val DRAG_SLOP_DP = 6

        /** The colour of the plate behind the text, before the opacity is applied. */
        private val BACKGROUND_COLOR = Color.rgb(0x20, 0x20, 0x24)

        /** The colour of the thin border around the plate. */
        private val BACKGROUND_BORDER = Color.WHITE

        /** The amber colour, the most readable one over a dark map. */
        private val AMBER_COLOR = Color.rgb(0xFF, 0xC1, 0x07)
        private val NEON_GREEN_COLOR = Color.rgb(0x39, 0xFF, 0x14)
        private val CYAN_COLOR = Color.rgb(0x00, 0xE5, 0xFF)
        private val MAGENTA_COLOR = Color.rgb(0xFF, 0x3D, 0xF5)
        private val ORANGE_COLOR = Color.rgb(0xFF, 0x8A, 0x00)
        private val RED_COLOR = Color.rgb(0xFF, 0x4D, 0x4D)
        private val YELLOW_COLOR = Color.rgb(0xFF, 0xEA, 0x00)
        // A dark grey for the pale day map; readable only with a transparent background.
        private val DARK_GRAY_COLOR = Color.rgb(0x33, 0x33, 0x33)
        // A bright blue, the opposite of the amber map lines and the night-map blue.
        private val BLUE_COLOR = Color.rgb(0x33, 0xAA, 0xFF)
        // A vivid violet, the most distinct choice from the yellow/amber map colours.
        private val VIOLET_COLOR = Color.rgb(0xA0, 0x40, 0xFF)
        private val STATUS_COLOR_WARNING = Color.rgb(0xFF, 0xC1, 0x07)
        private val STATUS_COLOR_ERROR = Color.rgb(0xFF, 0x53, 0x4D)
        private const val ALPHA_FULL = 1.0f
        private const val ALPHA_DISABLED = 0.35f

        /** The alpha of the lyric lines that are not the current one. */
        private const val LYRIC_DIM_ALPHA = 160

        private const val BACKGROUND_CORNER_DP = 16
        private const val SHADOW_RADIUS_DP = 3


        /** How many percent extra the lyric tiers get on top of the chosen idle brightness. */
        private const val IDLE_LYRIC_BOOST = 27

        private const val NAV_ALPHA = 0.92f

        private const val WIDTH_TITLE_DP = 240
        private const val WIDTH_COMPACT_DP = 300
        private const val WIDTH_STANDARD_DP = 340
        private const val WIDTH_LYRICS_DP = 380
        private const val WIDTH_LYRICS_MIN_DP = 300

        private const val DEFAULT_MARGIN_DP = 16
        private const val DEFAULT_TOP_DP = 150
        private const val NAV_ESTIMATED_HEIGHT_DP = 96
        private const val NAV_BOTTOM_DP = 96

        /**
         * The smallest screen the window is placed on, used when the real size of the
         * screen could not be read. It is only a lower bound for the clamping, not an
         * assumption about the size of the head unit screen.
         */
        private const val MIN_SCREEN_WIDTH_PX = 480
        private const val MIN_SCREEN_HEIGHT_PX = 320

        /**
         * The pseudo index of the lyric line that shows the "waiting for the network"
         * message. It is out of the range of the real lines, so it cannot be mixed up with
         * one of them.
         */
        private const val LYRIC_INDEX_WAITING = -2

        /** Maps the value stored in the settings to a tier, null means "off". */
        fun tierForKey(key: String?): Int? = when (key?.trim()?.lowercase()) {
            KEY_TITLE -> TIER_TITLE
            KEY_COMPACT, "mini" -> TIER_COMPACT
            KEY_STANDARD -> TIER_STANDARD
            KEY_LYRICS -> TIER_LYRICS
            KEY_LYRICS_MIN -> TIER_LYRICS_MIN
            KEY_BANNER_FULL -> TIER_BANNER_FULL
            KEY_BANNER_HALF -> TIER_BANNER_HALF
            else -> null
        }

        /** The settings value of a tier, used when the tier is picked from the player menu. */
        fun keyForTier(tier: Int): String = when (tier) {
            TIER_TITLE -> KEY_TITLE
            TIER_COMPACT -> KEY_COMPACT
            TIER_STANDARD -> KEY_STANDARD
            TIER_LYRICS -> KEY_LYRICS
            TIER_LYRICS_MIN -> KEY_LYRICS_MIN
            TIER_BANNER_FULL -> KEY_BANNER_FULL
            TIER_BANNER_HALF -> KEY_BANNER_HALF
            else -> KEY_OFF
        }

        fun show(context: Context, tier: Int, navMode: Boolean = Settings.navFloating) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_TIER, tier)
                putExtra(EXTRA_NAV, navMode)
            }
            start(context, intent)
        }

        /** Starts the window with the tier chosen in the player menu and remembers the choice. */
        fun showFromUser(context: Context, tier: Int) {
            Settings.floatingMode = keyForTier(tier)
            show(context, tier)
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, FloatingPlayerService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        /** Manual fallback used by the settings screen and the notification action. */
        @JvmStatic
        fun hideTemporarily(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_TEMP_HIDE
            }
            try {
                context.startService(intent)
            } catch (all: Exception) {
                Timber.w(all, "Could not hide the floating window temporarily")
            }
        }

        /**
         * Tells the running service whether the host wants the window hidden (the home page, for
         * example). Safe to call before the service exists: it only records the wish, and the next
         * [applyFromSettings] honours it when the window is built.
         */
        @JvmStatic
        fun setHostHidden(context: Context, hidden: Boolean) {
            hostWantsHide = hidden
            // The camera gate is event driven now, so a host state change only has to
            // re-evaluate the combined visibility of the remaining gates.
            instance?.applyOverlayVisibility()
        }

        /**
         * One switch for driving: the compact tier, docked to the lower left and slightly
         * transparent, without the close button.
         */
        fun setNavigationMode(context: Context, enabled: Boolean) {
            Settings.navFloating = enabled
            if (!enabled) {
                applyFromSettings(context)
                return
            }

            // Nav mode keeps the style the driver picked (lyrics, standard, …); it only docks the
            // window to the lower left and dims it, it does not downgrade the chosen tier.
            val tier = tierForKey(Settings.floatingMode) ?: TIER_COMPACT
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_NAV_ON
                putExtra(EXTRA_TIER, tier)
                putExtra(EXTRA_NAV, true)
            }
            start(context, intent)
        }

        /**
         * Repaints the window that is already open, without touching its size or position.
         *
         * This is used when the background opacity or the text colour is changed in the
         * settings: the window is not restarted, it only gets a new look.
         */
        fun refreshAppearance(context: Context) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                action = ACTION_REFRESH
            }
            // The service may not be running at all, and starting it just to repaint a window
            // that does not exist would show an overlay the driver did not ask for.
            try {
                context.startService(intent)
            } catch (all: Exception) {
                Timber.w(all, "Could not refresh the floating window")
            }
        }

        /** Applies the mode stored in the settings, hiding the window when it is off. */
        fun applyFromSettings(context: Context) {
            val tier = tierForKey(Settings.floatingMode) ?: return hide(context)
            show(context, tier, navMode = Settings.navFloating)
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
