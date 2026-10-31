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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.service.FloatingPlayerService
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * The start screen of the car UI.
 *
 * A head unit is operated while driving, so the home screen is not a list but a grid of eight
 * large tiles, plus one very large "continue playing" card on top. Nothing here is smaller than
 * a 60 dp touch target, and every label is set in a size that can be read from the driver seat.
 *
 * It has to be a [ScopeFragment]: the media player manager lives in the NavigationActivity scope.
 */
class HomeFragment : ScopeFragment() {

    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()
    private val activeServerProvider: ActiveServerProvider by inject()

    private var cover: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var playButton: ImageButton? = null

    private var currentCoverId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateNowPlaying()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private data class Tile(
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
        playButton = view.findViewById(R.id.home_play)

        decorateContinueCard(view)
        buildGrid(view)

        playButton?.setOnClickListener {
            mediaPlayerManager.togglePlayPause()
            updateNowPlaying()
        }

        view.findViewById<View>(R.id.home_shuffle).setOnClickListener { shuffleAll() }
        view.findViewById<View>(R.id.home_nav_mode).setOnClickListener { toggleNavigationOverlay() }

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

    // region palette

    private fun decorateContinueCard(root: View) {
        val context = requireContext()
        val palette = CarPalette.current()
        val accent = CarTheme.color(context, palette.accent)

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

        val shuffle = root.findViewById<ImageButton>(R.id.home_shuffle)
        ImageViewCompat.setImageTintList(
            shuffle,
            android.content.res.ColorStateList.valueOf(accent)
        )

        val navMode = root.findViewById<ImageButton>(R.id.home_nav_mode)
        ImageViewCompat.setImageTintList(
            navMode,
            android.content.res.ColorStateList.valueOf(
                if (Settings.navFloating) accent else CarTheme.onSurfaceSecondary(context)
            )
        )

        root.findViewById<TextView>(R.id.home_continue_label).setTextColor(accent)

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

    private fun buildGrid(root: View) {
        val context = requireContext()
        val grid = root.findViewById<LinearLayout>(R.id.home_grid)
        val columns = columns()

        // In one column the rows have to size themselves, otherwise eight rows share the height
        // of the screen and every one of them becomes a sliver
        if (columns == PORTRAIT_COLUMNS) {
            grid.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var index = 0
        tiles().chunked(columns).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (columns == PORTRAIT_COLUMNS) {
                        CarTheme.dp(context, PORTRAIT_ROW_HEIGHT_DP).toInt()
                    } else {
                        0
                    },
                    if (columns == PORTRAIT_COLUMNS) 0f else 1f
                ).apply {
                    topMargin = if (grid.childCount == 0) 0
                    else CarTheme.dp(context, TILE_GAP_DP).toInt()
                }
            }

            chunk.forEachIndexed { column, tile ->
                row.addView(createTile(row, tile, column, columns))
            }

            // Keep the last row aligned with the rows above it
            repeat(columns - chunk.size) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }

            grid.addView(row)
            index += chunk.size
        }

        Timber.d("Home grid built with %d columns, %d tiles", columns, index)
    }

    private fun tiles(): List<Tile> = listOf(
        Tile(R.drawable.ic_artist, R.string.main_artists_title) {
            it.findNavController().navigate(NavigationGraphDirections.toArtistList())
        },
        Tile(R.drawable.ic_home_album, R.string.main_albums_title) {
            it.findNavController().navigate(
                NavigationGraphDirections.toAlbumList(
                    AlbumListType.NEWEST,
                    size = Settings.maxAlbums
                )
            )
        },
        Tile(R.drawable.ic_home_song, R.string.main_songs_title) {
            it.findNavController().navigate(NavigationGraphDirections.toTrackCollection())
        },
        Tile(R.drawable.ic_menu_playlists, R.string.main_playlists_title) {
            it.findNavController().navigate(NavigationGraphDirections.toPlaylistFragment())
        },
        Tile(R.drawable.ic_home_folder, R.string.main_folders_title) {
            it.findNavController().navigate(R.id.folderFragment)
        },
        Tile(R.drawable.ic_home_genre, R.string.main_genres_title) {
            it.findNavController().navigate(NavigationGraphDirections.toGenreList())
        },
        Tile(R.drawable.ic_menu_download, R.string.menu_downloads) {
            it.findNavController().navigate(R.id.downloadsFragment)
        },
        // The three tiles below are the way to start music without typing anything. On a
        // head unit the search is the slowest possible way to pick a song, because the
        // driver has to spell it out on a keyboard he cannot look at.
        Tile(R.drawable.ic_home_recent, R.string.home_recent_title) {
            it.findNavController().navigate(
                NavigationGraphDirections.toAlbumList(
                    AlbumListType.RECENT,
                    size = Settings.maxAlbums
                )
            )
        },
        Tile(R.drawable.ic_home_starred, R.string.home_starred_title) {
            // The favourites live on the server. In offline mode they cannot be read, so
            // the tile leads to the local library instead of to an empty list.
            if (ActiveServerProvider.isOffline()) {
                it.findNavController().navigate(R.id.downloadsFragment)
            } else {
                it.findNavController().navigate(NavigationGraphDirections.toTrackCollection(
                    getStarred = true
                ))
            }
        },
        Tile(R.drawable.ic_home_radio, R.string.home_radio_title) {
            it.findNavController().navigate(NavigationGraphDirections.toGenreList())
        },
        // One tap toggles the offline library. While driving the network is not guaranteed,
        // so the driver needs a single, always visible switch between "play what is on the
        // car" and "play from the server". The tile remembers the server it came from, so
        // leaving offline mode lands on the same library again.
        Tile(
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
        Tile(R.drawable.ic_menu_settings, R.string.menu_settings) {
            it.findNavController().navigate(R.id.settingsFragment)
        }
    )

    private fun createTile(parent: ViewGroup, tile: Tile, index: Int, columns: Int): View {
        val context = requireContext()
        val palette = CarPalette.current()
        val accent = CarTheme.accent(context, palette)

        val view = layoutInflater.inflate(R.layout.item_home_tile, parent, false)
        val params = (view.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            height = LinearLayout.LayoutParams.MATCH_PARENT
            weight = 1f
            marginStart = if (index == 0) 0 else CarTheme.dp(context, TILE_GAP_DP).toInt()
        }
        view.layoutParams = params

        val background = CarTheme.tileBackground(context, index, palette)
        view.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(CarTheme.withAlpha(accent, PRESS_ALPHA)),
            background,
            background
        )

        val icon = view.findViewById<ImageView>(R.id.home_tile_icon)
        icon.setImageDrawable(ResourcesCompat.getDrawable(context.resources, tile.icon, null))
        ImageViewCompat.setImageTintList(icon, android.content.res.ColorStateList.valueOf(accent))

        val label = view.findViewById<TextView>(R.id.home_tile_label)
        label.setText(tile.label)

        // Stacked tiles in one column would waste the width, so they lay out like a row there
        if (columns == PORTRAIT_COLUMNS) {
            (view as LinearLayout).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPaddingRelative(
                    CarTheme.dp(context, 20f).toInt(),
                    0,
                    CarTheme.dp(context, 20f).toInt(),
                    0
                )
            }
            icon.layoutParams = icon.layoutParams.apply {
                width = CarTheme.dp(context, 20f).toInt()
                height = CarTheme.dp(context, 20f).toInt()
            }
            label.layoutParams = (label.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                marginStart = CarTheme.dp(context, 20f).toInt()
                topMargin = 0
            }
            label.gravity = android.view.Gravity.START
        }

        view.setOnClickListener { tile.open(this) }
        return view
    }

    // endregion

    // region playback

    private fun updateNowPlaying() {
        if (!isAdded) return

        val track = mediaPlayerManager.currentMediaItem?.toTrack()
        val playing = mediaPlayerManager.isPlaying

        playButton?.setImageResource(
            if (playing) R.drawable.media_pause else R.drawable.media_start
        )

        if (track == null) {
            titleView?.setText(R.string.home_idle)
            artistView?.setText(R.string.home_idle_hint)
            loadCover(null)
            return
        }

        titleView?.text = track.title
        artistView?.text = listOfNotNull(track.artist, track.album).joinToString(" · ")
            .ifBlank { getString(R.string.home_idle_hint) }
        loadCover(track)
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
     */
    private fun shuffleAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                try {
                    MusicServiceFactory.getMusicService().getRandomSongs(Settings.maxSongs).getTracks()
                } catch (all: Exception) {
                    Timber.w(all, "Could not load random songs")
                    emptyList()
                }
            }

            if (songs.isEmpty()) {
                Util.toast(R.string.home_idle_hint, context = context)
                return@launch
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

        /** Portrait: one big row per entry, the way the parked layout is designed. */
        private const val PORTRAIT_COLUMNS = 1
        private const val PORTRAIT_CARD_HEIGHT_DP = 104f
        private const val PORTRAIT_COVER_DP = 78f
        private const val PORTRAIT_ROW_HEIGHT_DP = 96f

        private const val TILE_GAP_DP = 12f
        private const val PRESS_ALPHA = 0x40
        private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        private val UPDATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)
    }
}
