/*
 * NavigationActivity.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.activity

import android.app.SearchManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.SearchRecentSuggestions
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeActivity
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.ServerSettingDao
import org.moire.ultrasonic.model.ServerSettingsModel
import org.moire.ultrasonic.provider.SearchSuggestionProvider
import org.moire.ultrasonic.service.FloatingPlayerService
import org.moire.ultrasonic.service.MediaPlayerLifecycleSupport
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.service.plusAssign
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.InfoDialog
import org.moire.ultrasonic.util.LocaleHelper
import org.moire.ultrasonic.util.ServerColor
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.ShortcutUtil
import org.moire.ultrasonic.util.Storage
import org.moire.ultrasonic.util.UiState
import org.moire.ultrasonic.util.UncaughtExceptionHandler
import org.moire.ultrasonic.fragment.PlayerPaneFragment
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * The main (and only) Activity of Ultrasonic which loads all other screens as Fragments.
 * Because this is the only Activity we have to manage the apps lifecycle through this activity
 * onCreate/onResume/onDestroy methods...
 */
@Suppress("TooManyFunctions")
class NavigationActivity : ScopeActivity() {
    private var videoMenuItem: MenuItem? = null
    private var chatMenuItem: MenuItem? = null
    private var bookmarksMenuItem: MenuItem? = null
    private var sharesMenuItem: MenuItem? = null
    private var podcastsMenuItem: MenuItem? = null
    private var playlistsMenuItem: MenuItem? = null
    private var downloadsMenuItem: MenuItem? = null

    private var nowPlayingView: FragmentContainerView? = null
    private var playerPane: FrameLayout? = null
    private var nowPlayingHidden = false
    private var navigationView: NavigationView? = null
    private var drawerLayout: DrawerLayout? = null
    private var host: NavHostFragment? = null
    private var selectServerButton: MaterialButton? = null
    private var headerBackgroundImage: ImageView? = null

    // We store the last search string in this variable.
    // Seems a bit like a hack, is there a better way?
    var searchQuery: String? = null

    private lateinit var appBarConfiguration: AppBarConfiguration

    private var rxBusSubscription: CompositeDisposable = CompositeDisposable()

    private val serverSettingsModel: ServerSettingsModel by viewModel()
    private val lifecycleSupport: MediaPlayerLifecycleSupport by inject()
    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val activeServerProvider: ActiveServerProvider by inject()
    private val serverRepository: ServerSettingDao by inject()

    private var currentFragmentId: Int = 0

    private companion object {
        /** Below this width two panes would squeeze the list too much. */
        private const val TWO_PANE_MIN_WIDTH_DP = 720
        private const val PLAYER_PANE_TAG = "twoPanePlayer"

        /** How long a media key has to be held before it turns into a seek. */
        private const val LONG_PRESS_MS = 600L

        /** Window in which a second press of play/pause counts as a double press. */
        private const val DOUBLE_PRESS_MS = 260L

        /** Distance of the long press seek, as asked for by the driver. */
        private const val KEY_SEEK_MS = 15_000
    }

    private val keyHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false
    private var pendingPlayPause: Runnable? = null
    private var lastPlayPauseTap = 0L
    private var lastNightMode = false

    // Resolved once, so the UI never flips by 180 degrees while the app is running
    private var lockedLandscapeOrientation: Int? = null
    private var cachedServerCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.d("onCreate called")

        // First check if Koin has been started
        if (UApp.instance != null && !UApp.instance!!.initiated) {
            Timber.d("Starting Koin")
            UApp.instance!!.startKoin()
        } else {
            Timber.d("No need to start Koin")
        }

        setUncaughtExceptionHandler()
        Util.applyTheme(this)

        super.onCreate(savedInstanceState)

        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.navigation_activity)

        // The window itself carries the palette gradient, so every screen sits on one surface
        CarTheme.applyWindow(this)
        lastNightMode = CarTheme.isNight(this)

        nowPlayingView = findViewById(R.id.now_playing_fragment)
        playerPane = findViewById(R.id.player_pane)
        navigationView = findViewById(R.id.nav_view)
        drawerLayout = findViewById(R.id.drawer_layout)

        setupDrawerLayout(drawerLayout!!)
        applyNavigationBarInset()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        host = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment? ?: return

        val navController = host!!.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.homeFragment,
                R.id.mainFragment,
                R.id.mediaLibraryFragment,
                R.id.searchFragment,
                R.id.playlistsFragment,
                R.id.downloadsFragment,
                R.id.sharesFragment,
                R.id.bookmarksFragment,
                R.id.chatFragment,
                R.id.podcastFragment,
                R.id.settingsFragment,
                R.id.aboutFragment,
                R.id.playerFragment
            ),
            drawerLayout
        )

        setupActionBar(navController, appBarConfiguration)

        setupNavigationMenu(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val dest: String = try {
                resources.getResourceName(destination.id)
            } catch (ignored: Resources.NotFoundException) {
                destination.id.toString()
            }
            Timber.d("Navigated to $dest")

            currentFragmentId = destination.id
            // Handle the hiding of the NowPlaying fragment when the Player is active
            if (currentFragmentId == R.id.playerFragment) {
                hideNowPlaying()
            } else {
                if (!nowPlayingHidden) showNowPlaying()
            }
        }

        // Determine if this is a first run
        val showWelcomeScreen = UApp.instance!!.isFirstRun

        // This is a first run with only the demo entry inside the database
        // We set the active server to the demo one and show the welcome dialog
        if (showWelcomeScreen) {
            showWelcomeDialog()
        }

        // Ask for permission to send notifications
        Util.ensurePermissionToPostNotification(this)

        rxBusSubscription += RxBus.dismissNowPlayingCommandObservable.subscribe {
            nowPlayingHidden = true
            hideNowPlaying()
        }

        rxBusSubscription += RxBus.playerStateObservable.subscribe {
            if (it.state == STATE_READY)
                showNowPlaying()
            else
                hideNowPlaying()
        }

        rxBusSubscription += RxBus.themeChangedEventObservable.subscribe {
            recreate()
        }

        rxBusSubscription += RxBus.activeServerChangedObservable.subscribe {
            updateNavigationHeaderForServer()
            setMenuForServerCapabilities()
        }

        serverRepository.liveServerCount().observe(this) { count ->
            cachedServerCount = count ?: 0
            updateNavigationHeaderForServer()
        }

        // Setup app shortcuts on supported devices, but not on first start, when the server
        // is not configured yet.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && !UApp.instance!!.isFirstRun) {
            ShortcutUtil.registerShortcuts(this)
        }

        // Register our options menu
        addMenuProvider(
            searchMenuProvider,
            this,
            Lifecycle.State.RESUMED
        )

        // Enforce the landscape orientation of the head unit, unless we are in split screen
        applyOrientationPolicy()

        // Show the permanent player on the right when the app owns the whole screen
        updateTwoPaneLayout()
    }

    /**
     * Enables the two pane layout (list on the left, player on the right) while the activity
     * owns the whole display. In split screen the window is too narrow and the system already
     * gives the user a way to see two apps side by side, so the layout falls back to a single
     * pane there.
     */
    private fun updateTwoPaneLayout() {
        val pane = playerPane ?: return

        val configuration = resources.configuration
        val wideEnough = configuration.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
        // Portrait means the car is parked: the home grid needs the full width for its single
        // column of large tiles, so the permanent player is dropped even on a wide display.
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val enabled = Settings.twoPaneLayout && wideEnough && landscape && !isInSplitScreen()

        UiState.twoPane = enabled
        pane.visibility = if (enabled) View.VISIBLE else View.GONE

        val fragmentManager = supportFragmentManager
        val existing = fragmentManager.findFragmentById(R.id.player_pane)

        if (enabled && existing == null) {
            fragmentManager.beginTransaction()
                .replace(R.id.player_pane, PlayerPaneFragment(), PLAYER_PANE_TAG)
                .commitAllowingStateLoss()
        } else if (!enabled && existing != null) {
            fragmentManager.beginTransaction()
                .remove(existing)
                .commitAllowingStateLoss()
        }

        if (enabled) {
            hideNowPlaying()
        } else if (!nowPlayingHidden) {
            showNowPlaying()
        }
    }

    /**
     * Keeps the UI in the landscape orientation the head unit is mounted in, so the display
     * of a rotating BYD screen (or any sensor event) never flips the app into portrait.
     *
     * The orientation is only pinned while the activity owns the whole screen: as soon as the
     * activity runs in a multi window (BYD split screen, Android 7+) the request is dropped,
     * because a fixed orientation prevents the system from resizing the window.
     */
    private fun applyOrientationPolicy() {
        val target = when {
            isInSplitScreen() -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            !Settings.forceLandscape -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> currentLandscapeOrientation()
        }

        if (requestedOrientation != target) {
            Timber.i("Applying orientation policy: %s", target)
            requestedOrientation = target
        }
    }

    private fun isInSplitScreen(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
    }

    /**
     * Returns the landscape constant which matches the way the display is mounted, so the UI is
     * never shown upside down on a reversed landscape head unit.
     */
    @Suppress("DEPRECATION")
    private fun currentLandscapeOrientation(): Int {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0

        return lockedLandscapeOrientation ?: run {
            val resolved = if (rotation == Surface.ROTATION_270) {
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            lockedLandscapeOrientation = resolved
            resolved
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        }
        Timber.i("Multi window mode changed: %s", isInMultiWindowMode)
        applyOrientationPolicy()
        // The right hand pane has to disappear as soon as the app shares the screen
        window.decorView.post { updateTwoPaneLayout() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotation of the head unit must not change the locked landscape direction
        applyOrientationPolicy()
        // The night dimming is drawn into the window background, so the UI is rebuilt on change.
        // The activity is **not** recreated: that would destroy and rebuild the whole
        // interface while the car is moving, which is exactly when nothing may break.
        // Only the window background is drawn again, the views read their colours from
        // the configuration on their own.
        val night = CarTheme.isNight(this)
        if (night != lastNightMode) {
            lastNightMode = night
            CarTheme.applyWindow(this)
        }
        window.decorView.post { updateTwoPaneLayout() }
    }

    /**
     * The steering wheel and hardware media buttons of the head unit are delivered to the
     * foreground activity as key events. They are translated here instead of being forwarded
     * blindly, because the wheel gets three gestures out of two keys:
     *
     * - short press on previous / next: previous or next track (the behaviour of the first
     *   version, verified on the car)
     * - long press on previous / next: seek back or forward 15 seconds
     * - double press on play / pause: next track
     *
     * When the head unit itself handles the key, the event never reaches this activity; the
     * media session of the playback service answers it instead, so skipping works while the
     * navigation app is in front as well.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = toMediaCommand(event.keyCode) ?: return super.dispatchKeyEvent(event)
        Timber.d(
            "Media key %d action=%d repeat=%d -> %s",
            event.keyCode,
            event.action,
            event.repeatCount,
            command
        )
        handleMediaCommand(event, command)
        return true
    }

    private enum class MediaCommand { PREVIOUS, NEXT, PLAY_PAUSE }

    private fun toMediaCommand(keyCode: Int): MediaCommand? {
        if (!Settings.dpadAsMediaKeys) {
            return when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaCommand.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaCommand.PREVIOUS
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaCommand.PLAY_PAUSE
                else -> null
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaCommand.PREVIOUS
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> MediaCommand.NEXT
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaCommand.PLAY_PAUSE
            else -> null
        }
    }

    private fun handleMediaCommand(event: KeyEvent, command: MediaCommand) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressFired = false
                    scheduleLongPress(command)
                }
            }

            KeyEvent.ACTION_UP -> {
                cancelLongPress()
                if (longPressFired) return

                when (command) {
                    MediaCommand.PREVIOUS -> mediaPlayerManager.seekToPrevious()
                    MediaCommand.NEXT -> mediaPlayerManager.seekToNext()
                    MediaCommand.PLAY_PAUSE -> handlePlayPauseTap()
                }
            }
        }
    }

    private fun scheduleLongPress(command: MediaCommand) {
        if (command == MediaCommand.PLAY_PAUSE) return

        val runnable = Runnable {
            longPressFired = true
            val delta = if (command == MediaCommand.NEXT) KEY_SEEK_MS else -KEY_SEEK_MS
            Timber.i("Media key long press: seek %d ms", delta)
            seekRelative(delta)
            Util.toast(
                if (delta > 0) R.string.seek_forward_15 else R.string.seek_backward_15,
                context = this
            )
        }
        longPressRunnable = runnable
        keyHandler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { keyHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun seekRelative(deltaMs: Int) {
        val duration = mediaPlayerManager.playerDuration
        if (duration <= 0) return
        val target = (mediaPlayerManager.playerPosition + deltaMs).coerceIn(0, duration)
        mediaPlayerManager.seekTo(target)
    }

    /**
     * A single press plays and pauses immediately, a second press within the double press window
     * skips to the next track. The single action is deferred by that window, because the driver
     * notices a pause that comes 250 ms late far less than a pause that never happens.
     */
    private fun handlePlayPauseTap() {
        val now = SystemClock.elapsedRealtime()
        val pending = pendingPlayPause

        if (pending != null && now - lastPlayPauseTap < DOUBLE_PRESS_MS) {
            keyHandler.removeCallbacks(pending)
            pendingPlayPause = null
            lastPlayPauseTap = 0L
            Timber.i("Media key double press: skip to next track")
            mediaPlayerManager.seekToNext()
            return
        }

        lastPlayPauseTap = now
        val runnable = Runnable {
            pendingPlayPause = null
            mediaPlayerManager.togglePlayPause()
        }
        pendingPlayPause = runnable
        keyHandler.postDelayed(runnable, DOUBLE_PRESS_MS)
    }

    private val searchMenuProvider: MenuProvider = object : MenuProvider {
        override fun onPrepareMenu(menu: Menu) {
            setupSearchField(menu)
        }

        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.search_view_menu, menu)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            return false
        }
    }

    fun setupSearchField(menu: Menu) {
        Timber.i("Recreating search field")
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        val searchableInfo = searchManager.getSearchableInfo(this.componentName)
        searchView.setSearchableInfo(searchableInfo)
        searchView.setIconifiedByDefault(false)

        if (searchQuery != null) {
            Timber.e("Found existing search query")
            searchItem.expandActionView()
            searchView.isIconified = false
            searchView.setQuery(searchQuery, false)
            searchView.clearFocus()
            // Restore search text only once!
            searchQuery = null
        }
    }

    /**
     * Keeps the content clear of the navigation bar of the head unit.
     *
     * The head unit draws its own bar at the bottom of the screen, over the app. Without this
     * the lowest row of the home screen sits under that bar and half of the tiles cannot be
     * reached without scrolling.
     *
     * The inset is taken from the window and not from the view, because the drawer layout
     * consumes the insets of its children and would report an empty bottom. It is applied to
     * the root, so the player bar at the bottom moves up as well and does not hide behind the
     * system buttons either.
     */
    private fun applyNavigationBarInset() {
        val root = findViewById<View>(R.id.content_root) ?: return
        val basePadding = root.paddingBottom

        val apply = {
            val insets = ViewCompat.getRootWindowInsets(root)
            val bottom = insets?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )?.bottom ?: 0
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                basePadding + bottom
            )
            Timber.d("Navigation bar inset: %d px", bottom)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            apply()
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
        root.post { apply() }
    }

    private fun setupDrawerLayout(drawerLayout: DrawerLayout) {
        // Set initial state passed on drawer state
        closeNavigationDrawerOnBack.isEnabled = drawerLayout.isOpen

        // Add the back press listener
        onBackPressedDispatcher.addCallback(this, closeNavigationDrawerOnBack)

        // Listen to changes in the drawer state and enable the back press listener accordingly.
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Nothing
            }

            override fun onDrawerOpened(drawerView: View) {
                closeNavigationDrawerOnBack.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                closeNavigationDrawerOnBack.isEnabled = false
            }

            override fun onDrawerStateChanged(newState: Int) {
                // Nothing
            }
        })
    }

    override fun onResume() {
        Timber.d("onResume called")
        super.onResume()

        Storage.reset()

        lifecycleScope.launch(Dispatchers.IO) {
            Storage.checkForErrorsWithCustomRoot()
        }

        setMenuForServerCapabilities()

        // Lifecycle support's constructor registers some event receivers so it should be created early
        lifecycleSupport.onCreate(Settings.resumeOnStart)

        // The floating window is a setting now, so it comes back with the app after a restart
        if (Settings.navFloating || FloatingPlayerService.tierForKey(Settings.floatingMode) != null) {
            FloatingPlayerService.applyFromSettings(this)
        }

        if (!nowPlayingHidden) showNowPlaying()
        else hideNowPlaying()
    }

    /*
     * Attention: onDestroy does not mean that the app is necessarily being killed.
     * Also rotating the screen will call onDestroy() and then onCreate()
     */
    override fun onDestroy() {
        Timber.d("onDestroy called")
        rxBusSubscription.dispose()
        super.onDestroy()
    }

    private fun updateNavigationHeaderForServer() {
        // Only show the vector graphic on Android 11 or earlier
        val showVectorBackground = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)

        val activeServer = activeServerProvider.getActiveServer()

        if (cachedServerCount == 0)
            selectServerButton?.text = getString(R.string.main_setup_server, activeServer.name)
        else selectServerButton?.text = activeServer.name

        val foregroundColor =
            ServerColor.getForegroundColor(this, activeServer.color, showVectorBackground)
        val backgroundColor =
            ServerColor.getBackgroundColor(this, activeServer.color)

        if (activeServer.index == 0)
            selectServerButton?.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_menu_screen_on_off)
        else
            selectServerButton?.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_menu_select_server)

        selectServerButton?.iconTint = ColorStateList.valueOf(foregroundColor)
        selectServerButton?.setTextColor(foregroundColor)
        headerBackgroundImage?.setBackgroundColor(backgroundColor)

        // Hide the vector graphic on Android 12 or later
        if (!showVectorBackground) {
            headerBackgroundImage?.setImageDrawable(null)
        }
    }

    private fun setupNavigationMenu(navController: NavController) {
        navigationView?.setupWithNavController(navController)

        // The fragments which expect SafeArgs need to be navigated to with SafeArgs (even when
        // they are empty)!
        navigationView?.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.mediaLibraryFragment -> {
                    navController.navigate(NavigationGraphDirections.toMediaLibrary())
                }
                R.id.bookmarksFragment -> {
                    navController.navigate(NavigationGraphDirections.toBookmarks())
                }
                R.id.trackCollectionFragment -> {
                    navController.navigate(
                        NavigationGraphDirections.toTrackCollection(
                            getVideos = true
                        )
                    )
                }
                R.id.menu_exit -> {
                    setResult(Constants.RESULT_CLOSE_ALL)
                    mediaPlayerManager.onDestroy()
                    finish()
                    exit()
                }
                else -> navController.navigate(it.itemId)
            }
            drawerLayout?.closeDrawer(GravityCompat.START)
            true
        }

        chatMenuItem = navigationView?.menu?.findItem(R.id.chatFragment)
        bookmarksMenuItem = navigationView?.menu?.findItem(R.id.bookmarksFragment)
        sharesMenuItem = navigationView?.menu?.findItem(R.id.sharesFragment)
        podcastsMenuItem = navigationView?.menu?.findItem(R.id.podcastFragment)
        playlistsMenuItem = navigationView?.menu?.findItem(R.id.playlistsFragment)
        downloadsMenuItem = navigationView?.menu?.findItem(R.id.downloadsFragment)
        videoMenuItem = navigationView?.menu?.findItem(R.id.trackCollectionFragment)

        selectServerButton =
            navigationView?.getHeaderView(0)?.findViewById(R.id.header_select_server)
        val dropDownButton: ImageView? =
            navigationView?.getHeaderView(0)?.findViewById(R.id.edit_server_button)

        val onClick: (View) -> Unit = {
            if (drawerLayout?.isDrawerVisible(GravityCompat.START) == true)
                this.drawerLayout?.closeDrawer(GravityCompat.START)
            navController.navigate(R.id.serverSelectorFragment)
        }

        selectServerButton?.setOnClickListener(onClick)
        dropDownButton?.setOnClickListener(onClick)

        headerBackgroundImage =
            navigationView?.getHeaderView(0)?.findViewById(R.id.img_header_bg)
    }

    private fun setupActionBar(navController: NavController, appBarConfig: AppBarConfiguration) {
        setupActionBarWithNavController(navController, appBarConfig)
    }

    private val closeNavigationDrawerOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            drawerLayout?.closeDrawer(GravityCompat.START)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val retValue = super.onCreateOptionsMenu(menu)
        if (navigationView == null) {
            menuInflater.inflate(R.menu.navigation_drawer, menu)
            return true
        }
        return retValue
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return item.onNavDestinationSelected(findNavController(R.id.nav_host_fragment)) ||
            super.onOptionsItemSelected(item)
    }

    // TODO: Why is this needed? Shouldn't it just work by default?
    override fun onSupportNavigateUp(): Boolean {
        return findNavController(R.id.nav_host_fragment).navigateUp(appBarConfiguration)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        when (intent?.action) {
            Constants.INTENT_PLAY_RANDOM_SONGS -> {
                playRandomSongs()
            }
            Intent.ACTION_MAIN -> {
                if (intent.getBooleanExtra(Constants.INTENT_SHOW_PLAYER, false)) {
                    findNavController(R.id.nav_host_fragment).navigate(R.id.playerFragment)
                }
            }
            Intent.ACTION_SEARCH -> {
                searchQuery = intent.getStringExtra(SearchManager.QUERY)
                handleSearchIntent(searchQuery, false)
            }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                searchQuery = intent.getStringExtra(SearchManager.QUERY)
                handleSearchIntent(searchQuery, true)
            }
        }
    }

    private fun handleSearchIntent(query: String?, autoPlay: Boolean) {
        val suggestions = SearchRecentSuggestions(
            this,
            SearchSuggestionProvider.AUTHORITY, SearchSuggestionProvider.MODE
        )
        suggestions.saveRecentQuery(query, null)

        val action = NavigationGraphDirections.toSearchFragment(query, autoPlay)
        findNavController(R.id.nav_host_fragment).navigate(action)
    }

    private fun playRandomSongs() {
        val currentFragment = host?.childFragmentManager?.fragments?.last() ?: return
        val service = MusicServiceFactory.getMusicService()
        val musicDirectory = service.getRandomSongs(Settings.maxSongs)

        mediaPlayerManager.addToPlaylist(
            songs = musicDirectory.getTracks(),
            autoPlay = true,
            shuffle = false,
            insertionMode = MediaPlayerManager.InsertionMode.CLEAR
        )

        if (Settings.shouldTransitionOnPlayback) {
            currentFragment.findNavController().popBackStack(R.id.playerFragment, true)
            currentFragment.findNavController().navigate(R.id.playerFragment)
        }

        return
    }

    /**
     * Apply the customized language settings if needed
     */
    override fun attachBaseContext(newBase: Context?) {
        val locale = Settings.overrideLanguage
        if (locale.isNotEmpty()) {
            val localeUpdatedContext: ContextWrapper = LocaleHelper.wrap(newBase, locale)
            super.attachBaseContext(localeUpdatedContext)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private fun exit() {
        Timber.d("User choose to exit the app")

        // Broadcast that the service is being stopped
        RxBus.stopServiceCommandPublisher.onNext(Unit)

        // Broadcast that the app is being shutdown
        RxBus.shutdownCommandPublisher.onNext(Unit)

        finishAndRemoveTask()
    }

    private fun showWelcomeDialog() {
        if (!UApp.instance!!.setupDialogDisplayed) {

            Settings.firstInstalledVersion = Util.getVersionCode(UApp.applicationContext())

            InfoDialog.Builder(this)
                .setTitle(R.string.main_welcome_title)
                .setMessage(R.string.main_welcome_text_demo)
                .setNegativeButton(R.string.main_welcome_cancel) { dialog, _ ->
                    UApp.instance!!.setupDialogDisplayed = true
                    // Go to the settings screen
                    dialog.dismiss()
                    findNavController(R.id.nav_host_fragment).navigate(R.id.serverSelectorFragment)
                }
                .setPositiveButton(R.string.common_ok) { dialog, _ ->
                    UApp.instance!!.setupDialogDisplayed = true
                    // Add the demo server
                    val activeServerProvider: ActiveServerProvider by inject()
                    val demoIndex = serverSettingsModel.addDemoServer()
                    activeServerProvider.setActiveServerByIndex(demoIndex)
                    findNavController(R.id.nav_host_fragment).navigate(R.id.homeFragment)
                    dialog.dismiss()
                }.show()
        }
    }

    private fun setUncaughtExceptionHandler() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !is UncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler(this))
        }
    }

    private fun showNowPlaying() {
        // The permanent player pane replaces the mini player completely
        if (UiState.twoPane) {
            hideNowPlaying()
            return
        }

        if (!Settings.showNowPlaying) {
            hideNowPlaying()
            return
        }

        // The logic for nowPlayingHidden is that the user can dismiss NowPlaying with a gesture,
        // and when the MediaPlayerService requests that it should be shown, it returns
        nowPlayingHidden = false
        // Do not show for Player fragment
        if (currentFragmentId == R.id.playerFragment) {
            hideNowPlaying()
            return
        }

        if (nowPlayingView != null) {
            val playerState: Int = mediaPlayerManager.playbackState
            if (playerState == STATE_BUFFERING || playerState == STATE_READY) {
                val item: MediaItem? = mediaPlayerManager.currentMediaItem
                if (item != null) {
                    nowPlayingView?.visibility = View.VISIBLE
                }
            } else {
                hideNowPlaying()
            }
        }
    }

    private fun hideNowPlaying() {
        nowPlayingView?.visibility = View.GONE
    }

    private fun setMenuForServerCapabilities() {
        val isOnline = !ActiveServerProvider.isOffline()
        val activeServer = activeServerProvider.getActiveServer()

        // Note: Offline capabilities are defined in ActiveServerProvider, OFFLINE_DB.
        // If you add Offline support for some of these features you need
        // to switch the boolean to true there.
        chatMenuItem?.isVisible = activeServer.chatSupport != false
        bookmarksMenuItem?.isVisible = activeServer.bookmarkSupport != false
        sharesMenuItem?.isVisible = activeServer.shareSupport != false
        podcastsMenuItem?.isVisible = activeServer.podcastSupport != false
        playlistsMenuItem?.isVisible = isOnline
        // The downloads page is the only place the offline library can be managed: it shows
        // the songs on the device, how much space they take, and which of them may be
        // thrown away. Hiding it while offline would leave the driver without any way to
        // look at what is on the car.
        downloadsMenuItem?.isVisible = true
        videoMenuItem?.isVisible = activeServer.videoSupport != false
    }
}
