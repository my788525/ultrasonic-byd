/*
 * DownloadsFragment.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.adapters.Utils
import org.moire.ultrasonic.adapters.TrackViewBinder
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.model.GenericListModel
import org.moire.ultrasonic.service.DownloadService
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.util.CacheCleaner
import org.moire.ultrasonic.util.CacheUsage
import org.moire.ultrasonic.util.ConfirmationDialog
import org.moire.ultrasonic.util.DownloadedTracks
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util
import timber.log.Timber

/**
 * Shows the songs that are stored on the device.
 *
 * Two things are shown:
 *  - the songs that were downloaded, so that the user can play them without a network
 *  - the downloads that are currently running
 *
 * The running downloads come from the download service, the finished songs are read from
 * the music directory on the device. On top of the list the space the music takes is
 * shown, so that the driver can see what is on the car and how much of it may be thrown
 * away without giving up the offline library.
 */
class DownloadsFragment : MultiListFragment<Track>() {

    internal val mediaPlayerManager: MediaPlayerManager by inject()
    internal val cacheCleaner: CacheCleaner by inject()

    /**
     * The ViewModel to use to get the data
     */
    override val listModel: DownloadListModel by viewModels()

    private var usageTotalLabel: TextView? = null
    private var usageTotalValue: TextView? = null
    private var usageReclaimableLabel: TextView? = null
    private var usageReclaimableValue: TextView? = null
    private var usageLibraryLabel: TextView? = null
    private var usageLibraryValue: TextView? = null
    private var cleanButton: View? = null

    /**
     * The id of the main layout
     */
    override val mainLayout: Int = R.layout.list_layout_downloads

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    override fun getLiveData(refresh: Boolean, append: Boolean): LiveData<List<Track>> {
        return listModel.getList()
    }

    override fun setTitle(title: String?) {
        FragmentTitle.setTitle(this, Util.appContext().getString(R.string.menu_downloads))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewAdapter.register(
            TrackViewBinder(
                onItemClick = { item, _ -> playFromTrack(item) },
                onContextMenuClick = ::onContextMenuItemSelected,
                checkable = false,
                draggable = false,
                lifecycleOwner = viewLifecycleOwner,
                // The songs already are on the device, so the menu offers only what a
                // file can do: play it, or remove it.
                createContextMenu = { v, _ ->
                    Utils.createPopupMenu(v, R.menu.context_menu_download)
                }
            )
        )
        val liveDataList = listModel.getList()

        emptyTextView.setText(R.string.download_empty)
        emptyView.isVisible = liveDataList.value?.isEmpty() ?: true

        viewAdapter.submitList(liveDataList.value)

        usageTotalLabel = view.findViewById(R.id.downloads_usage_total_label)
        usageTotalValue = view.findViewById(R.id.downloads_usage_total_value)
        usageReclaimableLabel = view.findViewById(R.id.downloads_usage_reclaimable_label)
        usageReclaimableValue = view.findViewById(R.id.downloads_usage_reclaimable_value)
        usageLibraryLabel = view.findViewById(R.id.downloads_usage_library_label)
        usageLibraryValue = view.findViewById(R.id.downloads_usage_library_value)
        cleanButton = view.findViewById(R.id.downloads_clean_button)

        cleanButton?.setOnClickListener {
            if (Settings.showConfirmationDialog) {
                ConfirmationDialog.Builder(requireContext())
                    .setMessage(R.string.downloads_clean_confirm)
                    .setPositiveButton(R.string.common_delete) { _, _ ->
                        clearSpace()
                    }.show()
            } else {
                clearSpace()
            }
        }

        updateUsage()
    }

    /**
     * What to do when the list has changed
     */
    override val defaultObserver: (List<Track>) -> Unit = {
        emptyView.isVisible = it.isEmpty() && !(swipeRefresh?.isRefreshing ?: false)
        viewAdapter.submitList(it)
    }

    /**
     * Refreshes the numbers of the space the music takes. They come from the same pass
     * over the files that the cleaner walks, so the button can only promise what the
     * cleaner really is allowed to do.
     */
    private fun updateUsage() {
        // The labels are given here and not when the numbers arrive, so that the rows do not
        // change their height under the finger while the files are being counted.
        usageTotalLabel?.setText(R.string.downloads_usage_total)
        usageReclaimableLabel?.setText(R.string.downloads_usage_reclaimable)
        usageLibraryLabel?.setText(R.string.downloads_usage_library)

        // Counting the files is a walk over the whole music directory, which on a full
        // library takes a moment. Saying so is better than showing 0 B and looking finished.
        usageTotalValue?.setText(R.string.downloads_usage_reading)
        usageReclaimableValue?.text = ""
        usageLibraryValue?.text = ""
        cleanButton?.isEnabled = false

        cacheCleaner.usage { usage ->
            withContext(Dispatchers.Main) {
                usageTotalLabel?.setText(R.string.downloads_usage_total)
                usageTotalValue?.text = Util.formatBytes(usage.bytesMusicDir)

                usageReclaimableLabel?.setText(R.string.downloads_usage_reclaimable)
                usageReclaimableValue?.text = Util.formatBytes(usage.bytesReclaimable)

                usageLibraryLabel?.setText(R.string.downloads_usage_library)
                usageLibraryValue?.text = Util.formatBytes(usage.bytesLibrary)

                val canClean = usage.bytesReclaimable > 0L
                cleanButton?.isVisible = canClean
                cleanButton?.isEnabled = canClean
            }
        }
    }

    /**
     * Clears the space the unfinished downloads take and tells the list to re-read the
     * files from the disk, so that the freed songs disappear right away.
     */
    private fun clearSpace() {
        cleanButton?.isEnabled = false
        cacheCleaner.cleanSpace()

        // The files are removed on a background thread, so the re-read is done after
        // the deletion is under way.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                listModel.refreshDownloaded()
                updateUsage()
            }
        }
    }

    /**
     * Plays the tapped song. The song comes from the local file, so it plays without a
     * network connection.
     */
    private fun playFromTrack(item: Track) {
        val tracks = viewAdapter.getCurrentList().filterIsInstance<Track>()
        val start = tracks.indexOfFirst { it.id == item.id }
        val queue = if (start >= 0) tracks.subList(start, tracks.size).toList() else listOf(item)

        mediaPlayerManager.playTracksAndToast(
            fragment = this,
            insertionMode = MediaPlayerManager.InsertionMode.CLEAR,
            tracks = queue
        )
    }

    override fun onContextMenuItemSelected(menuItem: MenuItem, item: Track): Boolean {
        return when (menuItem.itemId) {
            R.id.download_song_menu_play -> {
                playFromTrack(item)
                true
            }
            R.id.download_song_menu_delete -> {
                deleteTracks(listOf(item))
                true
            }
            else -> false
        }
    }

    override fun onItemClick(item: Track) {
        playFromTrack(item)
    }

    /**
     * Removes the songs from the device. This is the only way the driver gives the car
     * back its space: the cleaner only touches the downloads that did not finish.
     */
    private fun deleteTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val message = resources.getQuantityString(
            R.plurals.n_songs_deleted,
            tracks.size,
            tracks.size
        )

        val remove = {
            // The files of a song on the device are removed by the download service, which
            // knows all three file names of a track. The offline database rows are swept
            // by the full database cleanup that follows.
            DownloadService.delete(tracks)
            cacheCleaner.cleanSpace()
            listModel.refreshDownloaded()
            Util.toast(message, requireContext())
            updateUsage()
        }

        if (Settings.showConfirmationDialog) {
            ConfirmationDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.common_delete) { _, _ -> remove() }
                .show()
        } else {
            remove()
        }
    }
}

class DownloadListModel(application: Application) : GenericListModel(application) {

    /**
     * The songs that are available offline.
     */
    private val downloaded = MutableLiveData<List<DownloadedTracks.Downloaded>>()

    private val list = MediatorLiveData<List<Track>>()

    init {
        val running = DownloadService.observableDownloads

        list.addSource(running) { trackList ->
            list.value = merge(trackList, downloaded.value)
        }
        list.addSource(downloaded) { trackList ->
            list.value = merge(running.value, trackList)
        }
    }

    /**
     * Re-reads the files of the music directory and publishes them, so that the list of
     * the downloads updates after something was removed from the disk.
     */
    fun refreshDownloaded() {
        downloaded.value = readDownloadedSongs()
    }

    /**
     * The central function to pass a query to the model and return a LiveData object
     */
    fun getList(): LiveData<List<Track>> {
        refreshDownloaded()
        return list
    }

    /**
     * Reads the songs that were downloaded from the music directory.
     */
    private fun readDownloadedSongs(): List<DownloadedTracks.Downloaded> {
        return try {
            DownloadedTracks.scan()
        } catch (e: Exception) {
            Timber.e(e, "Could not read the downloaded songs")
            emptyList()
        }
    }

    private fun merge(
        running: List<Track>?,
        downloaded: List<DownloadedTracks.Downloaded>?
    ): List<Track> {
        val result = mutableListOf<Track>()
        val knownIds = mutableSetOf<String>()

        for (entry in downloaded.orEmpty()) {
            result += entry.track
            knownIds += entry.track.id
        }

        // A song that is currently being downloaded is already on the device, so it is not
        // listed twice.
        result += running.orEmpty().filter { it.id !in knownIds }
        return result
    }
}
