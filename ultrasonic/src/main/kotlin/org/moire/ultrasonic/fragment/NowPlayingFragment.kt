/*
 * NowPlayingFragment.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.fragment

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlin.math.abs
import org.koin.android.ext.android.inject
import org.koin.androidx.scope.ScopeFragment
import org.moire.ultrasonic.NavigationGraphDirections
import org.moire.ultrasonic.R
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.service.plusAssign
import org.moire.ultrasonic.subsonic.ImageLoaderProvider
import org.moire.ultrasonic.ui.CarPalette
import org.moire.ultrasonic.ui.CarTheme
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Util.applyTheme
import org.moire.ultrasonic.util.Util.getNotificationImageSize
import org.moire.ultrasonic.util.toTrack
import timber.log.Timber

/**
 * Contains the mini-now playing information box displayed at the bottom of the screen
 */
class NowPlayingFragment : ScopeFragment() {

    private var downX = 0f
    private var downY = 0f

    private var rootBar: View? = null
    private var prevButton: MaterialButton? = null
    private var playButton: MaterialButton? = null
    private var nextButton: MaterialButton? = null
    private var nowPlayingAlbumArtImage: ImageView? = null
    private var nowPlayingTrack: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var lastLoadedTrackId: String? = null

    private val rxBusSubscription = CompositeDisposable()
    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val imageLoaderProvider: ImageLoaderProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this.context)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.now_playing, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rootBar = view.findViewById(R.id.now_playing)
        prevButton = view.findViewById(R.id.now_playing_control_prev)
        playButton = view.findViewById(R.id.now_playing_control_play)
        nextButton = view.findViewById(R.id.now_playing_control_next)
        nowPlayingAlbumArtImage = view.findViewById(R.id.now_playing_image)
        nowPlayingTrack = view.findViewById(R.id.now_playing_title)
        nowPlayingArtist = view.findViewById(R.id.now_playing_artist)

        prevButton?.setOnClickListener { mediaPlayerManager.seekToPrevious() }
        playButton?.setOnClickListener { mediaPlayerManager.togglePlayPause() }
        nextButton?.setOnClickListener { mediaPlayerManager.seekToNext() }

        view.setOnTouchListener { _: View?, event: MotionEvent ->
            handleOnTouch(event)
        }
        view.setOnClickListener { }

        applyPalette(CarPalette.current())

        rxBusSubscription += RxBus.playerStateObservable.subscribe { update() }
    }

    override fun onResume() {
        super.onResume()
        applyPalette(CarPalette.current())
        update()
    }

    override fun onDestroyView() {
        rxBusSubscription.clear()
        rootBar = null
        prevButton = null
        playButton = null
        nextButton = null
        nowPlayingAlbumArtImage = null
        nowPlayingTrack = null
        nowPlayingArtist = null
        lastLoadedTrackId = null
        super.onDestroyView()
    }

    private fun applyPalette(palette: CarPalette) {
        val ctx = context ?: return
        val accent = CarTheme.accent(ctx, palette)
        rootBar?.background = CarTheme.roundedFill(
            ctx,
            CarTheme.surfaceAltColor(ctx, palette),
            CarTheme.withAlpha(accent, 0x33),
            0f
        )
        nowPlayingTrack?.setTextColor(CarTheme.onSurface(ctx))
        nowPlayingArtist?.setTextColor(CarTheme.onSurfaceSecondary(ctx))

        val secondaryTint = ColorStateList.valueOf(CarTheme.onSurface(ctx))
        prevButton?.iconTint = secondaryTint
        nextButton?.iconTint = secondaryTint
        playButton?.let { btn ->
            btn.background = CarTheme.accentButton(ctx, palette)
            btn.backgroundTintList = null
            btn.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.pal_on_accent))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun update() {
        if (!isAdded || view == null) return
        try {
            if (mediaPlayerManager.isPlaying) {
                playButton?.setIconResource(R.drawable.media_pause)
            } else {
                playButton?.setIconResource(R.drawable.media_start)
            }
            prevButton?.isEnabled = mediaPlayerManager.canSeekToPrevious()
            nextButton?.isEnabled = mediaPlayerManager.canSeekToNext()

            val file = mediaPlayerManager.currentMediaItem?.toTrack()

            if (file != null) {
                nowPlayingTrack?.text = file.title
                nowPlayingArtist?.text = file.artist

                if (file.id != lastLoadedTrackId) {
                    lastLoadedTrackId = file.id
                    val size = getNotificationImageSize(requireContext())
                    imageLoaderProvider.executeOn {
                        it.loadImage(
                            nowPlayingAlbumArtImage,
                            file,
                            false,
                            size
                        )
                    }
                }

                nowPlayingAlbumArtImage?.setOnClickListener {
                    val id3 = Settings.id3TagsEnabledOnline
                    val action = NavigationGraphDirections.toTrackCollection(
                        isAlbum = id3,
                        id = if (id3) file.albumId else file.parent,
                        name = file.album
                    )
                    findNavController().navigate(action)
                }
            }
        } catch (all: Exception) {
            Timber.w(all, "Failed to update NowPlaying mini bar")
        }
    }

    private fun handleOnTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_UP -> {
                val upX = event.x
                val upY = event.y
                val deltaX = downX - upX
                val deltaY = downY - upY

                if (abs(deltaX) > MIN_DISTANCE) {
                    // left or right
                    if (deltaX < 0) {
                        mediaPlayerManager.seekToPrevious()
                    }
                    if (deltaX > 0) {
                        mediaPlayerManager.seekToNext()
                    }
                } else if (abs(deltaY) > MIN_DISTANCE) {
                    if (deltaY < 0) {
                        RxBus.dismissNowPlayingCommandPublisher.onNext(Unit)
                    }
                } else {
                    Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
                        .navigate(R.id.playerFragment)
                }
            }
        }
        return false
    }

    companion object {
        private const val MIN_DISTANCE = 30
    }
}
