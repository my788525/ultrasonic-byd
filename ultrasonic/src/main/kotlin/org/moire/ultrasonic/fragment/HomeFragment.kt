/*
 * HomeFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.service.FloatingPlayerService
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.util.CarTransport
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.DownloadedTracks
import org.moire.ultrasonic.util.HomePrefs
import org.moire.ultrasonic.util.PinnedPlaylist
import org.moire.ultrasonic.util.PlaylistResumeStore
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * The start screen of the car UI.
 *
 * A head unit is operated while driving, so the home screen is not a list but a grid of large
 * tiles, plus one very large "continue playing" card on top. Nothing here is smaller than
 * a 60 dp touch target, and every label is set in a size that can be read from the driver seat.
 *
 * Above the music library sits the "my playlists" row: the driver's playlists from the server,
 * shown straight on the home screen so a playlist is only ever one tap away. Tapping one starts
 * it immediately and continues where it was left off when a position is saved. Pinned playlists
 * are floated to the front. The music-library tiles can be dragged to reorder them, and that
 * order is remembered.
 *
 * It has to be a [ScopeFragment]: the media player manager lives in the NavigationActivity scope.
 */
@Suppress("TooManyFunctions")
class HomeFragment : ScopeFragment() {

    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private val activeServerProvider: ActiveServerProvider by inject()

    private var cover: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var prevButton: ImageButton? = null
    private var playButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var progressView: ProgressBar? = null
    private var homePlaylistAdapter: HomePlaylistAdapter? = null
    private var playlistRowsCached = 0
    private var lastPlayingState: Boolean? = null

    private var currentCoverId: String? = null
    private var lastPrevClickAt = 0L
    private var lastNextClickAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateNowPlaying()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private data class Tile(
        val key: String,
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
        val open: (HomeFragment) -> Unit
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        FragmentTitle.setTitle(this, R.string.home_title)

        cover = view.findViewById(R.id.home_cover)
        titleView = view.findViewById(R.id.home_title)
        artistView = view.findViewById(R.id.home_artist)
        prevButton = view.findViewById(R.id.home_prev)
        playButton = view.findViewById(R.id.home_play)
        nextButton = view.findViewById(R.id.home_next)
        progressView = view.findViewById(R.id.home_progress)

        decorateContinueCard(view)
        setupPlaylistGrid(view)
        setupLibraryGrid(view)

        prevButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (CarTransport.isDebounced(lastPrevClickAt, now)) return@setOnClickListener
            lastPrevClickAt = now
            val pos = mediaPlayerManager.playerPosition.toLong()
            if (pos > CarTransport.PREVIOUS_RESTART_THRESHOLD_MS) {
                mediaPlayerManager.seekTo(0)
            } else {
                mediaPlayerManager.seekToPrevious()
            }
            updateNowPlaying()
        }

        playButton?.setOnClickListener {
            mediaPlayerManager.togglePlayPause()
            updateNowPlaying()
        }

        nextButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (CarTransport.isDebounced(lastNextClickAt, now)) return@setOnClickListener
            lastNextClickAt = now
            mediaPlayerManager.seekToNext()
            updateNowPlaying()
        }

        view.findViewById<View>(R.id.home_shuffle).setOnClickListener { shuffleAll() }
        view.findViewById<View>(R.id.home_nav_mode).setOnClickListener { toggleNavigationOverlay() }
        view.findViewById<View?>(R.id.home_playlists_more)?.setOnClickListener {
            findNavController().navigate(NavigationGraphDirections.toPlaylistFragment())
        }

        // The card body opens the big player page, the buttons on it keep their own action.
        view.findViewById<View>(R.id.home_continue_card).setOnClickListener {
            findNavController().navigate(R.id.playerFragment)
        }

        updateNowPlaying()
    }

    /**
     * One tap to put a small player above the navigation screen, or to take it away again.
     * This is the gesture the driver uses before starting a route, so it lives on the home
     * screen and not three levels deep in the settings.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun toggleNavigationOverlay() {
        val ctx = context ?: return
        val enabled = !Settings.navFloating

        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(ctx)
        ) {
            Util.toast(R.string.floating_permission_needed, context = ctx)
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.packageName)
                    )
                )
            } catch (all: Exception) {
                Timber.w(all, "Could not open the overlay permission screen")
            }
            return
        }

        FloatingPlayerService.setNavigationMode(ctx, enabled)
        updateNavButtonTint()
        Util.toast(
            if (enabled) R.string.nav_floating_on else R.string.nav_floating_off,
            context = ctx
        )
    }

    override fun onResume() {
        super.onResume()
        currentCoverId = null
        updateNowPlaying()
        updateNavButtonTint()
        // The playlist row may have changed while we were on the playlist screen.
        view?.let { setupPlaylistGrid(it) }
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
    }

    /** The navigation switch can be changed in the settings while the home screen is paused. */
    private fun updateNavButtonTint() {
        val button = view?.findViewById<ImageButton>(R.id.home_nav_mode) ?: return
        val color = if (Settings.navFloating) {
            CarTheme.accent(requireContext())
        } else {
            CarTheme.onSurfaceSecondary(requireContext())
        }
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color))
    }

    override fun onPause() {
        handler.removeCallbacks(updateRunnable)
        super.onPause()
    }

    override fun onDestroyView() {
        // The next onViewCreated binds brand new views. Clearing the change guards makes the
        // first tick repaint the play button and the cover instead of assuming the old views
        // still show the right state.
        cover = null
        titleView = null
        artistView = null
        prevButton = null
        playButton = null
        nextButton = null
        progressView = null
        lastPlayingState = null
        currentCoverId = null
        super.onDestroyView()
    }

    // region palette

    private fun decorateContinueCard(root: View) {
        val context = requireContext()
        val palette = CarPalette.current()
        val accent = CarTheme.color(context, palette.accent)
        val primaryText = CarTheme.onSurface(context)

        val card = root.findViewById<View>(R.id.home_continue_card)
        val cardBackground = CarTheme.cardBackground(context)
        card.background = RippleDrawable(
            ColorStateList.valueOf(CarTheme.withAlpha(accent, PRESS_ALPHA)),
            cardBackground,
            null
        )

        val play = root.findViewById<ImageButton>(R.id.home_play)
        val playBackground = CarTheme.accentButton(context, palette)
        play.background = RippleDrawable(
            ColorStateList.valueOf(CarTheme.withAlpha(OPAQUE_WHITE, 0x40)),
            playBackground,
            playBackground
        )
        ImageViewCompat.setImageTintList(
            play,
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.pal_on_accent))
        )

        root.findViewById<ImageButton?>(R.id.home_prev)?.let {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(primaryText))
        }
        root.findViewById<ImageButton?>(R.id.home_next)?.let {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(primaryText))
        }

        val shuffle = root.findViewById<ImageButton>(R.id.home_shuffle)
        ImageViewCompat.setImageTintList(
            shuffle,
            ColorStateList.valueOf(accent)
        )

        val navMode = root.findViewById<ImageButton>(R.id.home_nav_mode)
        ImageViewCompat.setImageTintList(
            navMode,
            ColorStateList.valueOf(
                if (Settings.navFloating) accent else CarTheme.onSurfaceSecondary(context)
            )
        )

        root.findViewById<TextView>(R.id.home_continue_label).setTextColor(accent)

        root.findViewById<ProgressBar?>(R.id.home_progress)
            ?.progressTintList = ColorStateList.valueOf(accent)

        applyOrientationLayout(root)
    }

    /**
     * Portrait on a rotating DiLink screen means the car is parked: the cover gets much bigger
     * and the tiles stack into one column, which is easier to hit from the centre console.
     */
    private fun applyOrientationLayout(root: View) {
        if (columns() != 1) return

        val context = requireContext()
        val card = root.findViewById<View>(R.id.home_continue_card)
        card.layoutParams = card.layoutParams.apply {
            height = CarTheme.dp(context, PORTRAIT_CARD_HEIGHT_DP).toInt()
        }

        val coverCard = root.findViewById<View>(R.id.home_cover_card)
        coverCard.layoutParams = coverCard.layoutParams.apply {
            width = CarTheme.dp(context, PORTRAIT_COVER_DP).toInt()
            height = CarTheme.dp(context, PORTRAIT_COVER_DP).toInt()
        }
    }

    // endregion

    // region grid

    /** One column when the screen is taller than it is wide, four otherwise. */
    private fun columns(): Int {
        val config = resources.configuration
        return when {
            config.screenHeightDp > config.screenWidthDp -> PORTRAIT_COLUMNS
            config.screenWidthDp >= WIDE_GRID_MIN_DP -> WIDE_COLUMNS
            else -> NARROW_COLUMNS
        }
    }

    /**
     * Shows the driver's playlists as a horizontally scrollable grid. The number chosen in
     * settings controls how many rows are visible on the first screen (4 playlists = one row,
     * 8 = two rows, ...). It never truncates the list: every playlist stays reachable by
     * swiping sideways. The cached list renders instantly, then a throttled background refresh
     * keeps it current (skipped while the cache is still fresh).
     */
    private fun setupPlaylistGrid(root: View) {
        val recycler = root.findViewById<RecyclerView>(R.id.home_playlists_grid)
        val rows = playlistRows()

        // Leaving for the playlist screen destroys the Fragment view but keeps this Fragment
        // (and its adapter). The RecyclerView we get back is a brand new one, so the layout
        // manager, adapter and scroll guard must be re-attached on every view rebuild.
        // Checking only "adapter == null" used to leave the new grid empty on return.
        if (recycler.layoutManager == null || playlistRowsCached != rows) {
            recycler.layoutManager = GridLayoutManager(
                requireContext(), rows, RecyclerView.HORIZONTAL, false
            )
            playlistRowsCached = rows
        }
        recycler.isNestedScrollingEnabled = false
        // A RecyclerView inside a ScrollView only measures the first screenful.
        // An exact height makes every row and column measurable before it is scrolled into view.
        val gridHeight = playlistGridHeightPx(rows)
        if (recycler.layoutParams.height != gridHeight) {
            recycler.layoutParams = recycler.layoutParams.apply { this.height = gridHeight }
        }

        if (homePlaylistAdapter == null) {
            homePlaylistAdapter = HomePlaylistAdapter { playHomePlaylist(it) }
        }
        if (recycler.adapter !== homePlaylistAdapter) {
            recycler.adapter = homePlaylistAdapter
            attachHorizontalScrollGuard(recycler)
        }

        // Instant render from cache, then a throttled background refresh from the server.
        renderPlaylists(HomePrefs.getCachedPlaylists())
        loadHomePlaylists(::renderPlaylists)
    }

    private fun renderPlaylists(list: List<PinnedPlaylist>) {
        val container = view?.findViewById<View?>(R.id.home_playlists_container) ?: return
        val ranked = rankedTopPlaylists(list)
        if (ranked.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        homePlaylistAdapter?.submitList(ranked)
    }

    /** Rows visible on the first screen: 4 playlists = one row, 8 = two rows, and so on. */
    private fun playlistRows(): Int {
        val count = Settings.homePlaylistCount
        val visible = if (count <= 0) 16 else count // "All": four rows, still fully scrollable
        val maxRows = if (columns() == 1) 2 else 4
        return (visible / columns().coerceAtLeast(1)).coerceIn(1, maxRows)
    }

    /** Exact height for the horizontal playlist grid so every row is measured and drawn. */
    private fun playlistGridHeightPx(rows: Int): Int {
        val tileH = resources.getDimensionPixelSize(R.dimen.home_playlist_tile_height)
        val gap = resources.getDimensionPixelSize(R.dimen.home_grid_tile_gap)
        return rows * (tileH + 2 * gap)
    }

    /**
     * Keeps horizontal drags inside the playlist grid: the outer ScrollView must not steal a
     * sideways gesture, while a mostly vertical drag still scrolls the whole page.
     */
    private fun attachHorizontalScrollGuard(recycler: RecyclerView) {
        val slop = ViewConfiguration.get(recycler.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        recycler.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dy > dx && dy > slop) {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // let the RecyclerView handle the gesture itself
        }
    }

    /**
     * Orders the home playlists without ever truncating: playlists pinned in Settings come
     * first, then the most-played ones, then the rest in server order. The count setting only
     * controls how many rows are visible at once; sideways scrolling always reaches every
     * playlist, regardless of that setting or the pin selection.
     */
    private fun rankedTopPlaylists(list: List<PinnedPlaylist>): List<PinnedPlaylist> {
        val pinned = HomePrefs.getVisiblePlaylistIds().orEmpty()
        val counts = HomePrefs.getAllPlayCounts()
        // Build the arrival-order index once instead of an O(n^2) indexOf per comparison.
        val order = HashMap<String, Int>(list.size)
        list.forEachIndexed { index, playlist -> order[playlist.id] = index }
        val byPlays = list.sortedWith(
            compareByDescending<PinnedPlaylist> { counts[it.id] ?: 0 }
                .thenBy { order[it.id] ?: Int.MAX_VALUE }
        )
        if (pinned.isEmpty()) return byPlays
        // Pinned playlists keep the play-count order among themselves. Otherwise a custom
        // selection would freeze the first tiles forever and tapping one would never reorder
        // the home screen, which is exactly what the driver notices.
        val front = byPlays.filter { pinned.contains(it.id) }
        return front + byPlays.filterNot { pinned.contains(it.id) }
    }

    private fun setupLibraryGrid(root: View) {
        val recycler = root.findViewById<RecyclerView>(R.id.home_grid)
        val cols = columns()
        recycler.layoutManager = GridLayoutManager(requireContext(), cols)
        recycler.isNestedScrollingEnabled = false
        val ordered = orderedTiles().toMutableList()
        recycler.layoutParams = recycler.layoutParams.apply {
            height = gridHeightPx(ordered.size, cols)
        }
        val adapter = HomeTileAdapter(ordered, this)
        recycler.adapter = adapter

        // Drag to reorder the music-library tiles; the new order is persisted on drop.
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                HomePrefs.setTileOrder(adapter.keys())
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    private fun gridHeightPx(itemCount: Int, columns: Int): Int {
        if (itemCount <= 0) return 0
        val tileH = resources.getDimensionPixelSize(R.dimen.home_grid_tile_height)
        val gap = resources.getDimensionPixelSize(R.dimen.home_grid_tile_gap)
        val rows = (itemCount + columns - 1) / columns
        return rows * (tileH + 2 * gap)
    }

    /**
     * Loads the driver's playlists from the server (unless the local cache is still fresh), caches
     * them, and reports them ordered. Skipping a warm cache avoids a redundant network call every
     * time the home screen is opened.
     *
     * When the network fails or times out, keeps the already-rendered cached playlists instead of
     * wiping the row with an empty list.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadHomePlaylists(onResult: (List<PinnedPlaylist>) -> Unit) {
        val cached = HomePrefs.getCachedPlaylists()
        val cacheAgeMs = System.currentTimeMillis() - HomePrefs.getPlaylistFetchTime()
        if (cached.isNotEmpty() && cacheAgeMs < PLAYLIST_REFRESH_TTL_MS) {
            // Cache is fresh: the instant render already shows it, so skip the network round-trip.
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                withTimeoutOrNull(NETWORK_ACTION_TIMEOUT_MS) {
                    try {
                        MusicServiceFactory.getMusicService().getPlaylists(false)
                    } catch (all: Exception) {
                        Timber.w(all, "Could not load the home playlists")
                        null
                    }
                }
            }
            if (!isAdded || loaded == null) return@launch

            val mapped = loaded.map { PinnedPlaylist(it.id, it.name, it.coverArt) }
            if (mapped.isNotEmpty()) {
                HomePrefs.setCachedPlaylists(mapped)
                HomePrefs.setPlaylistFetchTime(System.currentTimeMillis())
                onResult(mapped)
            } else if (cached.isEmpty()) {
                onResult(emptyList())
            }
        }
    }

    /** Starts a playlist immediately, continuing from the saved position when one exists. */
    @Suppress("TooGenericExceptionCaught")
    private fun playHomePlaylist(playlist: PinnedPlaylist) {
        HomePrefs.incrementPlayCount(playlist.id)
        // Re-rank the home grid immediately so the just-played playlist moves up without waiting
        // for the next screen open.
        homePlaylistAdapter?.submitList(rankedTopPlaylists(HomePrefs.getCachedPlaylists()))
        val progress = PlaylistResumeStore.get(playlist.id)
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                val remote = withTimeoutOrNull(NETWORK_ACTION_TIMEOUT_MS) {
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
                    // Fallback to local downloaded tracks if the server cannot be reached
                    DownloadedTracks.scan()
                        .filter { it.state != DownloadedTracks.State.PARTIAL }
                        .map { it.track }
                }
            }

            if (songs.isEmpty()) {
                Util.toast(R.string.home_idle_hint, context = context)
                return@launch
            }

            mediaPlayerManager.resumePlaylistAt(
                id = playlist.id,
                name = playlist.name,
                songs = songs,
                index = progress?.index ?: 0,
                position = progress?.position ?: 0
            )
            // Open that playlist's track list so the driver sees the playback progress and the
            // other songs straight away. It is shown, not re-started (autoPlay defaults to false).
            if (isAdded) {
                findNavController().navigate(
                    NavigationGraphDirections.toTrackCollection(
                        id = playlist.id,
                        name = playlist.name,
                        playlistId = playlist.id,
                        playlistName = playlist.name
                    )
                )
            }
            Util.toast(
                getString(R.string.home_playlist_playing, playlist.name),
                context = context
            )
        }
    }

    private fun orderedTiles(): List<Tile> {
        val all = tiles()
        val saved = HomePrefs.getTileOrder()
        if (saved.isEmpty()) return all
        val byKey = all.associateBy { it.key }
        val ordered = saved.mapNotNull { byKey[it] }.toMutableList()
        // Append any tiles that were added after the saved order was written.
        all.forEach { if (!saved.contains(it.key)) ordered.add(it) }
        return ordered
    }

    private fun tiles(): List<Tile> = listOf(
        Tile("artists", R.drawable.ic_artist, R.string.main_artists_title) {
            it.findNavController().navigate(NavigationGraphDirections.toArtistList())
        },
        Tile("albums", R.drawable.ic_home_album, R.string.main_albums_title) {
            it.findNavController().navigate(
                NavigationGraphDirections.toAlbumList(
                    AlbumListType.NEWEST,
                    size = Settings.maxAlbums
                )
            )
        },
        Tile("songs", R.drawable.ic_home_song, R.string.main_songs_title) {
            it.findNavController().navigate(NavigationGraphDirections.toTrackCollection())
        },
        Tile("playlists", R.drawable.ic_menu_playlists, R.string.main_playlists_title) {
            it.findNavController().navigate(NavigationGraphDirections.toPlaylistFragment())
        },
        Tile("folders", R.drawable.ic_home_folder, R.string.main_folders_title) {
            it.findNavController().navigate(R.id.folderFragment)
        },
        Tile("genres", R.drawable.ic_home_genre, R.string.main_genres_title) {
            it.findNavController().navigate(NavigationGraphDirections.toGenreList())
        },
        Tile("downloads", R.drawable.ic_menu_download, R.string.menu_downloads) {
            it.findNavController().navigate(R.id.downloadsFragment)
        },
        // The three tiles below are the way to start music without typing anything. On a
        // head unit the search is the slowest possible way to pick a song, because the
        // driver has to spell it out on a keyboard he cannot look at.
        Tile("recent", R.drawable.ic_home_recent, R.string.home_recent_title) {
            it.findNavController().navigate(
                NavigationGraphDirections.toAlbumList(
                    AlbumListType.RECENT,
                    size = Settings.maxAlbums
                )
            )
        },
        Tile("starred", R.drawable.ic_home_starred, R.string.home_starred_title) {
            // The favourites live on the server. In offline mode they cannot be read, so
            // the tile leads to the local library instead of to an empty list.
            if (ActiveServerProvider.isOffline()) {
                it.findNavController().navigate(R.id.downloadsFragment)
            } else {
                it.findNavController().navigate(
                    NavigationGraphDirections.toTrackCollection(
                        getStarred = true
                    )
                )
            }
        },
        Tile("radio", R.drawable.ic_home_radio, R.string.home_radio_title) {
            it.findNavController().navigate(NavigationGraphDirections.toGenreList())
        },
        // One tap toggles the offline library. While driving the network is not guaranteed,
        // so the driver needs a single, always visible switch between "play what is on the
        // car" and "play from the server". The tile remembers the server it came from, so
        // leaving offline mode lands on the same library again.
        Tile(
            "offline",
            R.drawable.ic_home_offline,
            if (ActiveServerProvider.isOffline()) R.string.home_online_title
            else R.string.home_offline_title
        ) {
            if (ActiveServerProvider.isOffline()) {
                val onlineId = Settings.lastOnlineServerId
                if (onlineId > 0) {
                    it.activeServerProvider.setActiveServerById(onlineId)
                } else {
                    Util.toast(R.string.home_offline_no_server, context = it.requireContext())
                }
            } else {
                // Remember the current online server so the tile can bring us back.
                Settings.lastOnlineServerId = Settings.activeServer
                it.activeServerProvider.setActiveServerById(ActiveServerProvider.OFFLINE_DB_ID)
            }
        },
        Tile("settings", R.drawable.ic_menu_settings, R.string.menu_settings) {
            it.findNavController().navigate(R.id.settingsFragment)
        }
    )

    private fun styleTile(view: View, tile: Tile, index: Int) {
        val context = requireContext()
        val palette = CarPalette.current()
        val accent = CarTheme.accent(context, palette)

        val background = CarTheme.tileBackground(context, index, palette)
        view.background = RippleDrawable(
            ColorStateList.valueOf(CarTheme.withAlpha(accent, PRESS_ALPHA)),
            background,
            background
        )

        val chip = view.findViewById<View?>(R.id.home_tile_chip)
        chip?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = CarTheme.dp(context, 14f)
            setColor(CarTheme.withAlpha(CarTheme.color(context, palette.accentSoft), 0xCC))
            setStroke(
                CarTheme.dp(context, 1.5f).toInt(),
                CarTheme.withAlpha(accent, 0x55)
            )
        }

        val icon = view.findViewById<ImageView>(R.id.home_tile_icon)
        icon.setImageDrawable(ResourcesCompat.getDrawable(context.resources, tile.icon, null))
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(accent))

        val label = view.findViewById<TextView>(R.id.home_tile_label)
        label.setText(tile.label)

        view.setOnClickListener { tile.open(this) }
    }

    private class HomeTileAdapter(
        private val items: MutableList<Tile>,
        private val fragment: HomeFragment
    ) : RecyclerView.Adapter<HomeTileAdapter.VH>() {

        class VH(val view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_home_grid_tile, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            fragment.styleTile(holder.view, items[position], position)
        }

        fun move(from: Int, to: Int) {
            if (from == to) return
            val tile = items.removeAt(from)
            items.add(to, tile)
            notifyItemMoved(from, to)
        }

        fun keys(): List<String> = items.map { it.key }
    }

    private class PlaylistVH(val view: View) : RecyclerView.ViewHolder(view)

    private val playlistDiff = object : DiffUtil.ItemCallback<PinnedPlaylist>() {
        override fun areItemsTheSame(a: PinnedPlaylist, b: PinnedPlaylist) = a.id == b.id
        override fun areContentsTheSame(a: PinnedPlaylist, b: PinnedPlaylist) =
            a.id == b.id && a.name == b.name && a.coverArt == b.coverArt
    }

    private inner class HomePlaylistAdapter(
        private val onClick: (PinnedPlaylist) -> Unit
    ) : ListAdapter<PinnedPlaylist, PlaylistVH>(playlistDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_home_playlist_tile, parent, false)
            // One "page" of the sideways grid holds exactly `columns()` cards, so the setting
            // (4 / 8 / 12 / 16) really equals the number of playlists on the first screen.
            val cols = columns().coerceAtLeast(1)
            val gap = parent.resources.getDimensionPixelSize(R.dimen.home_grid_tile_gap)
            val available = parent.width - parent.paddingLeft - parent.paddingRight
            if (available > 0) {
                val tileWidth = (available / cols) - (2 * gap)
                val minWidth = CarTheme.dp(parent.context, 100f).toInt()
                (view.layoutParams as? RecyclerView.LayoutParams)?.let {
                    it.width = tileWidth.coerceAtLeast(minWidth)
                    view.layoutParams = it
                }
            }
            return PlaylistVH(view)
        }

        override fun onBindViewHolder(holder: PlaylistVH, position: Int) {
            val playlist = getItem(position)
            val context = holder.view.context
            val palette = CarPalette.current()
            val accent = CarTheme.accent(context, palette)

            val background = CarTheme.tileBackground(context, position, palette)
            holder.view.background = RippleDrawable(
                ColorStateList.valueOf(CarTheme.withAlpha(accent, PRESS_ALPHA)),
                background,
                background
            )

            bindPlaylistIcon(holder.view.findViewById(R.id.home_tile_icon), playlist, accent)

            val label = holder.view.findViewById<TextView>(R.id.home_tile_label)
            label.text = playlist.name

            holder.view.setOnClickListener { onClick(playlist) }
        }

        private fun bindPlaylistIcon(icon: ImageView, playlist: PinnedPlaylist, accent: Int) {
            if (playlist.coverArt.isNotEmpty()) {
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                icon.imageTintList = null
                icon.setPadding(0, 0, 0, 0)
                // Horizontal scrolling rebinds tiles often. A recycled tile that already shows
                // this cover needs no new decode request (a major jank source on a head unit).
                val token = "playlist-${playlist.coverArt}"
                if (icon.getTag(R.id.home_tile_icon) == token) return
                icon.setTag(R.id.home_tile_icon, token)
                imageLoaderProvider.executeOn { loader ->
                    if (isAdded) {
                        loader.loadImage(
                            icon,
                            playlist.coverArt,
                            "playlist-${playlist.coverArt}",
                            true,
                            0,
                            R.drawable.ic_menu_playlists
                        )
                    }
                }
            } else {
                icon.setTag(R.id.home_tile_icon, null)
                icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = CarTheme.dp(icon.context, 12f).toInt()
                icon.setPadding(pad, pad, pad, pad)
                icon.setImageDrawable(
                    ResourcesCompat.getDrawable(
                        icon.context.resources, R.drawable.ic_menu_playlists, null
                    )
                )
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(accent))
            }
        }
    }

    // endregion

    // region playback

    private fun updateNowPlaying() {
        if (!isAdded) return

        val track = mediaPlayerManager.currentMediaItem?.toTrack()
        val playing = mediaPlayerManager.isPlaying

        // setImageResource re-creates a drawable and invalidates the view. This runs once per
        // second, so only do it when the play state really changes.
        if (lastPlayingState != playing) {
            lastPlayingState = playing
            playButton?.setImageResource(
                if (playing) R.drawable.media_pause else R.drawable.media_start
            )
        }

        val canPrev = mediaPlayerManager.canSeekToPrevious() || mediaPlayerManager.playerPosition > 0
        val canNext = mediaPlayerManager.canSeekToNext()
        prevButton?.isEnabled = canPrev
        prevButton?.alpha = if (canPrev) 1.0f else 0.38f
        nextButton?.isEnabled = canNext
        nextButton?.alpha = if (canNext) 1.0f else 0.38f

        if (track == null) {
            setTextIfChanged(titleView, getString(R.string.home_idle))
            setTextIfChanged(artistView, getString(R.string.home_idle_hint))
            loadCover(null)
            progressView?.progress = 0
            return
        }

        setTextIfChanged(titleView, track.title.orEmpty())
        setTextIfChanged(artistView, formatNowPlayingLine(track))
        loadCover(track)

        // The hero card doubles as a glanceable progress indicator, so the driver can see how
        // far into the track the car is without opening the player.
        val position = mediaPlayerManager.playerPosition
        val duration = mediaPlayerManager.playerDuration
        progressView?.progress = if (duration > 0) {
            (position.toLong() * 1000 / duration).toInt().coerceIn(0, 1000)
        } else 0
    }

    /** Avoids a layout/invalidate pass every second when the label has not changed. */
    private fun setTextIfChanged(view: TextView?, text: String) {
        if (view != null && view.text?.toString() != text) view.text = text
    }

    /**
     * Builds the secondary line of the continue-playing card: artist/album plus the position of
     * the track in the queue. The live progress is already drawn by the card's progress bar, so
     * the text no longer changes every second (that used to force a full layout pass each tick).
     */
    private fun formatNowPlayingLine(track: Track?): String {
        val meta = listOfNotNull(track?.artist, track?.album).joinToString(" · ")
        val index = mediaPlayerManager.currentMediaItemIndex
        val trackNo = if (index >= 0) " · 第${index + 1}首" else ""
        return meta.ifBlank { getString(R.string.home_idle_hint) } + trackNo
    }

    private fun loadCover(track: Track?) {
        val id = track?.id
        if (id == currentCoverId) return
        currentCoverId = id

        val target = cover ?: return
        if (track == null) {
            target.setImageResource(R.drawable.unknown_album)
            return
        }

        imageLoaderProvider.executeOn { loader ->
            if (isAdded) loader.loadImage(target, track, true, 0)
        }
    }

    /**
     * Plays a random selection of the whole library. On a head unit this is the fastest way to
     * get music going without browsing, so it gets its own always visible button.
     *
     * When the network is unreachable or times out, automatically falls back to shuffling local
     * downloaded tracks so the driver never gets stuck.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun shuffleAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            var usedLocalFallback = false
            val songs = withContext(Dispatchers.IO) {
                val remote = withTimeoutOrNull(NETWORK_ACTION_TIMEOUT_MS) {
                    try {
                        MusicServiceFactory.getMusicService()
                            .getRandomSongs(Settings.maxSongs)
                            .getTracks()
                    } catch (all: Exception) {
                        Timber.w(all, "Could not load random songs")
                        emptyList()
                    }
                }.orEmpty()

                if (remote.isNotEmpty()) {
                    remote
                } else {
                    val local = DownloadedTracks.scan()
                        .filter { it.state != DownloadedTracks.State.PARTIAL }
                        .map { it.track }
                        .shuffled()
                        .take(Settings.maxSongs)
                    if (local.isNotEmpty()) usedLocalFallback = true
                    local
                }
            }

            if (songs.isEmpty()) {
                Util.toast(R.string.home_idle_hint, context = context)
                return@launch
            }

            if (usedLocalFallback) {
                Util.toast(R.string.car_fallback_shuffle_cached, context = context)
            }

            mediaPlayerManager.addToPlaylist(
                songs = songs,
                autoPlay = true,
                shuffle = true,
                insertionMode = MediaPlayerManager.InsertionMode.CLEAR
            )
        }
    }

    // endregion

    private companion object {
        /** Below this width four columns per row would squeeze the labels. */
        private const val WIDE_GRID_MIN_DP = 520
        private const val WIDE_COLUMNS = 4
        private const val NARROW_COLUMNS = 2

        /** Skip the server playlist refresh if the cache was fetched less than this long ago. */
        private const val PLAYLIST_REFRESH_TTL_MS = 10 * 60 * 1000L

        /** Timeout for interactive home actions before falling back to local cache. */
        private const val NETWORK_ACTION_TIMEOUT_MS = 8_000L

        /** Portrait: one big row per entry, the way the parked layout is designed. */
        private const val PORTRAIT_COLUMNS = 1
        private const val PORTRAIT_CARD_HEIGHT_DP = 108f
        private const val PORTRAIT_COVER_DP = 78f

        private const val PRESS_ALPHA = 0x40
        private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        private val UPDATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)
    }
}
