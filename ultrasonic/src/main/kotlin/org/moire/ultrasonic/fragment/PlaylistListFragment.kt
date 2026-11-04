/*
 * PlaylistListFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.service.MusicServiceFactory
import org.moire.ultrasonic.util.DownloadAction
import org.moire.ultrasonic.util.DownloadUtil
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * Lists the playlists stored on the server and plays them with a single tap.
 *
 * Tapping a row opens the playlist as a track collection, the play button of the row starts
 * the whole playlist right away, and a long press offers the shuffled playback and the
 * download of the playlist. This tab exists next to the drawer entry, because on a car
 * head unit the drawer and its context menus are hard to reach while driving.
 */
class PlaylistListFragment : Fragment() {

    private var swipeRefresh: SwipeRefreshLayout? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null

    private val playlistAdapter = PlaylistAdapter(
        onClick = { playlist -> playPlaylist(playlist, shuffle = false) },
        onPlay = { playlist -> playPlaylist(playlist, shuffle = false) },
        onLongClick = { view, playlist -> showRowMenu(view, playlist) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.list_layout_generic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh = view.findViewById(R.id.swipe_refresh_view)
        emptyView = view.findViewById(R.id.empty_list_view)
        emptyText = view.findViewById(R.id.empty_list_text)

        swipeRefresh?.setOnRefreshListener { load(refresh = true) }

        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = playlistAdapter
        }

        load(refresh = false)
    }

    private fun load(refresh: Boolean) {
        swipeRefresh?.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    MusicServiceFactory.getMusicService().getPlaylists(refresh)
                } catch (all: Exception) {
                    Timber.w(all, "Could not load the playlists")
                    null
                }
            }

            if (!isAdded) return@launch

            swipeRefresh?.isRefreshing = false

            val playlists = loaded.orEmpty()
            playlistAdapter.submitList(playlists)

            emptyView?.isVisible = playlists.isEmpty()
            if (playlists.isEmpty()) {
                emptyText?.setText(
                    if (loaded == null) R.string.playlist_load_failed else R.string.playlist_empty
                )
            }
        }
    }

    /** Opens the playlist, so that a single track can be picked. */
    private fun openPlaylist(playlist: Playlist) {
        val action = NavigationGraphDirections.toTrackCollection(
            id = playlist.id,
            name = playlist.name,
            playlistId = playlist.id,
            playlistName = playlist.name
        )
        findNavController().navigate(action)
    }

    /** Starts the playlist immediately, without showing the track list first. */
    private fun playPlaylist(playlist: Playlist, shuffle: Boolean) {
        val action = NavigationGraphDirections.toTrackCollection(
            id = playlist.id,
            name = playlist.name,
            playlistId = playlist.id,
            playlistName = playlist.name,
            autoPlay = true,
            shuffle = shuffle
        )
        findNavController().navigate(action)
    }

    private fun showRowMenu(anchor: View, playlist: Playlist) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.inflate(R.menu.playlist_row_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.playlist_row_open -> {
                    openPlaylist(playlist)
                    true
                }
                R.id.playlist_row_play -> {
                    playPlaylist(playlist, shuffle = false)
                    true
                }
                R.id.playlist_row_shuffle -> {
                    playPlaylist(playlist, shuffle = true)
                    true
                }
                R.id.playlist_row_download -> {
                    DownloadUtil.justDownload(
                        action = DownloadAction.DOWNLOAD,
                        fragment = this,
                        id = playlist.id,
                        name = playlist.name,
                        isShare = false,
                        isDirectory = false
                    )
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}

/**
 * Adapter for the playlist rows. See [FolderAdapter] for the reasons behind this lightweight
 * implementation.
 */
private class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onPlay: (Playlist) -> Unit,
    private val onLongClick: (View, Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.ViewHolder>(PlaylistDiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val subtitle: TextView = view.findViewById(R.id.playlist_subtitle)
        val play: View = view.findViewById(R.id.playlist_play)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = getItem(position)
        val context = holder.itemView.context

        holder.name.text = playlist.name

        val songCount = playlist.songCount.toIntOrNull()
        val subtitle = when {
            songCount == null || songCount < 0 -> playlist.comment
            else -> context.resources.getQuantityString(
                R.plurals.playlist_song_count,
                songCount,
                songCount
            )
        }

        if (subtitle.isNullOrBlank()) {
            holder.subtitle.isVisible = false
        } else {
            holder.subtitle.isVisible = true
            holder.subtitle.text = subtitle
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener {
            onLongClick(it, playlist)
            true
        }
        holder.play.setOnClickListener { onPlay(playlist) }
    }

    private object PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
    }
}
