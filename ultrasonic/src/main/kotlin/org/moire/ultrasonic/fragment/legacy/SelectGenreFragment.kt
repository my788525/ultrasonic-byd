package org.moire.ultrasonic.fragment.legacy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ListView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.Genre
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.service.MusicServiceFactory.getMusicService
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.util.RefreshableFragment
import org.moire.ultrasonic.util.Settings.maxSongs
import org.moire.ultrasonic.util.Util.applyTheme
import org.moire.ultrasonic.util.toastingExceptionHandler
import org.moire.ultrasonic.util.Util
import timber.log.Timber
import org.moire.ultrasonic.view.GenreAdapter

/**
 * Displays the available genres in the media library
 */
class SelectGenreFragment : ScopeFragment(), RefreshableFragment {

    private val mediaPlayerManager: MediaPlayerManager by inject()
    override var swipeRefresh: SwipeRefreshLayout? = null
    private var genreListView: ListView? = null
    private var emptyView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.select_genre, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.select_genre_refresh)
        genreListView = view.findViewById(R.id.select_genre_list)
        swipeRefresh?.setOnRefreshListener { load(true) }

        genreListView?.setOnItemClickListener {
            parent: AdapterView<*>, _: View?,
            position: Int, _: Long
            ->
            val genre = parent.getItemAtPosition(position) as Genre

            val action = NavigationGraphDirections.toTrackCollection(
                genreName = genre.name,
                size = maxSongs,
                offset = 0
            )
            findNavController().navigate(action)
        }
        emptyView = view.findViewById(R.id.select_genre_empty)
        registerForContextMenu(genreListView!!)
        setTitle(this, R.string.main_genres_title)
        load(false)
    }

    /**
     * Starts a radio of a genre: the songs of that genre are fetched and played in random
     * order.
     *
     * This is the difference between one tap and a walk through a list of songs. The driver
     * does not have to pick a song at all, the app picks one.
     */
    private fun startGenreRadio(genre: Genre) {
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                try {
                    getMusicService()
                        .getSongsByGenre(genre.name, RADIO_SIZE, 0)
                        .getTracks()
                } catch (all: Exception) {
                    Timber.w(all, "Could not load the songs of the genre %s", genre.name)
                    emptyList()
                }
            }

            if (songs.isEmpty()) {
                Util.toast(R.string.home_radio_empty, context = context)
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

    private fun load(refresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(
            toastingExceptionHandler()
        ) {
            val result = withContext(Dispatchers.IO) {
                val musicService = getMusicService()
                musicService.getGenres(refresh)
            }
            swipeRefresh?.isRefreshing = false
            withContext(Dispatchers.Main) {
                emptyView?.isVisible = result.isEmpty()
                genreListView?.adapter = GenreAdapter(requireContext(), result) { genre ->
                    startGenreRadio(genre)
                }
            }
        }
    }
    private companion object {
        /** How many songs a genre radio holds. */
        private const val RADIO_SIZE = 100
    }

}
