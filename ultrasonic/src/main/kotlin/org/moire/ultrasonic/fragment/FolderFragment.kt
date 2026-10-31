/*
 * FolderFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.service.MusicService
import org.moire.ultrasonic.service.MusicServiceFactory
import timber.log.Timber

/**
 * Browses the library by its directory structure instead of the ID3 tags.
 *
 * The first level lists the music folders (libraries) of the server, every further level uses
 * the getIndexes / getMusicDirectory calls, which is the folder paradigm of the Subsonic API.
 * Directories are opened as [TrackCollectionFragment]s, so albums and tracks are played,
 * downloaded and queued with exactly the same actions as anywhere else in the app.
 */
class FolderFragment : Fragment() {

    private enum class Level { LIBRARIES, ARTISTS }

    private var level = Level.LIBRARIES
    private var libraryId: String? = null

    private var swipeRefresh: SwipeRefreshLayout? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null

    private val folderAdapter = FolderAdapter { entry -> onEntryClick(entry) }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showLibraries()
        }
    }

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

        emptyText?.setText(R.string.folder_empty)

        swipeRefresh?.setOnRefreshListener { load() }

        view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = folderAdapter
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backCallback
        )

        load()
    }

    private fun showLibraries() {
        level = Level.LIBRARIES
        libraryId = null
        backCallback.isEnabled = false
        load()
    }

    private fun load() {
        swipeRefresh?.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadFromServer() }

            if (!isAdded) return@launch

            swipeRefresh?.isRefreshing = false

            val entries = loaded.orEmpty()
            folderAdapter.submitList(entries)

            emptyView?.isVisible = entries.isEmpty()
            if (entries.isEmpty()) {
                emptyText?.setText(
                    if (loaded == null) R.string.folder_load_failed else R.string.folder_empty
                )
            }
        }
    }

    /**
     * Returns the entries of the current level, or null when the server call failed.
     * A server with a single music folder skips the folder selection and shows its
     * top level directories right away.
     */
    private fun loadFromServer(): List<FolderEntry>? {
        val service = MusicServiceFactory.getMusicService()

        return try {
            when (level) {
                Level.LIBRARIES -> {
                    val folders = service.getMusicFolders(true)
                    if (folders.size == 1) {
                        libraryId = folders.first().id
                        level = Level.ARTISTS
                        loadArtists(service)
                    } else {
                        folders.map { FolderEntry(it.id, it.name, null, isLibrary = true) }
                    }
                }
                Level.ARTISTS -> loadArtists(service)
            }
        } catch (all: Exception) {
            Timber.w(all, "Could not load the folder tree")
            null
        }
    }

    private fun loadArtists(service: MusicService): List<FolderEntry> {
        val comparator = compareBy(Collator.getInstance()) { entry: FolderEntry -> entry.title }

        return service.getIndexes(libraryId, true)
            .map { index ->
                FolderEntry(
                    id = index.id,
                    title = index.name ?: "",
                    subtitle = index.albumCount?.let {
                        resources.getQuantityString(
                            R.plurals.folder_album_count,
                            it.toInt(),
                            it.toInt()
                        )
                    }
                )
            }
            .sortedWith(comparator)
    }

    private fun onEntryClick(entry: FolderEntry) {
        if (entry.isLibrary) {
            level = Level.ARTISTS
            libraryId = entry.id
            backCallback.isEnabled = true
            load()
            return
        }

        val action = NavigationGraphDirections.toTrackCollection(
            id = entry.id,
            name = entry.title,
            parentId = entry.id,
            isArtist = true
        )
        findNavController().navigate(action)
    }
}

/**
 * A single directory in the folder browser.
 */
data class FolderEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val isLibrary: Boolean = false
)

/**
 * Minimal adapter for the folder rows. A folder browser has no selection, drag and drop or
 * download states, so it does not need the heavyweight adapter used by the other lists.
 */
private class FolderAdapter(
    private val onClick: (FolderEntry) -> Unit
) : ListAdapter<FolderEntry, FolderAdapter.ViewHolder>(FolderDiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.folder_name)
        val subtitle: TextView = view.findViewById(R.id.folder_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.name.text = entry.title

        if (entry.subtitle.isNullOrBlank()) {
            holder.subtitle.isVisible = false
        } else {
            holder.subtitle.isVisible = true
            holder.subtitle.text = entry.subtitle
        }

        holder.itemView.setOnClickListener { onClick(entry) }
    }

    private object FolderDiffCallback : DiffUtil.ItemCallback<FolderEntry>() {
        override fun areItemsTheSame(oldItem: FolderEntry, newItem: FolderEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FolderEntry, newItem: FolderEntry): Boolean {
            return oldItem == newItem
        }
    }
}
