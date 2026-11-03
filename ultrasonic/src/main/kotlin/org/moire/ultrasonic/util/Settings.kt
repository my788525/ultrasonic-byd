/*
 * Settings.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.regex.Pattern
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp

/**
 * Contains convenience functions for reading and writing preferences
 */
object Settings {

    @JvmStatic
    val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(Util.appContext())

    @JvmStatic
    var theme by StringSetting(
        getKey(R.string.setting_key_theme),
        getKey(R.string.setting_key_theme_day_night)
    )

    /** Key of the dark color scheme used by the car UI, see CarPalette. */
    @JvmStatic
    var palette by StringSetting(
        getKey(R.string.setting_key_palette),
        getKey(R.string.setting_key_palette_graphite)
    )

    /** Size of the floating window above other apps, "off" hides it. */
    @JvmStatic
    var floatingMode by StringSetting(
        getKey(R.string.setting_key_floating_mode),
        "off"
    )

    /** Last position of the floating window, so it comes back where the driver left it. */
    @JvmStatic
    var floatingX by IntSetting(getKey(R.string.setting_key_floating_x), -1)

    @JvmStatic
    var floatingY by IntSetting(getKey(R.string.setting_key_floating_y), -1)

    /** Side the window docks to ("left" or "right"), used by the edge snapping. */
    @JvmStatic
    var floatingSide by StringSetting(getKey(R.string.setting_key_floating_side), "right")

    /** A locked window ignores taps, so a hand crossing the screen cannot skip a track. */
    @JvmStatic
    var floatingLocked by BooleanSetting(getKey(R.string.setting_key_floating_locked), false)

    /** One switch for driving: small window, docked lower left, almost opaque. */
    @JvmStatic
    var navFloating by BooleanSetting(getKey(R.string.setting_key_nav_floating), false)

    /** Dims the UI at night, following the day/night switch of the head unit. */
    @JvmStatic
    var followsNight by BooleanSetting(getKey(R.string.setting_key_follow_night), true)

    /**
     * How opaque the background of the floating window is, in percent. At 0 the window has no
     * background at all and only the text is drawn over the map.
     */
    @JvmStatic
    var floatingBgOpacity by IntSetting(getKey(R.string.setting_key_floating_bg_opacity), 90)

    /**
     * How dim the idle-faded floating window is, in percent of its full brightness. After a few
     * seconds without a touch the window fades to this brightness so it stops drawing the eye;
     * a tap wakes it back to full. 100 keeps it fully bright (no dimming), 0 would be invisible.
     * Defaults to the previous hard-coded look: 45 % for the title-only tiers, which the lyric
     * tiers scale up automatically so the lyrics stay readable.
     */
    @JvmStatic
    var floatingIdleBrightness by IntSetting(getKey(R.string.setting_key_floating_idle_brightness), 45)

    /** The colour the text of the floating window uses: theme, white, amber or accent. */
    @JvmStatic
    var floatingTextColor by StringSetting(getKey(R.string.setting_key_floating_text_color), "theme")

    /**
     * Whether the text of the floating window carries a dark shadow that improves contrast over a
     * busy map. Off by default is not an option here: the shadow is the only thing that keeps the
     * text readable once the background is transparent, so it stays on unless the driver turns it
     * off because it makes the text look fuzzy on their map.
     */
    @JvmStatic
    var floatingTextShadow by BooleanSetting(getKey(R.string.setting_key_floating_text_shadow), true)

    /**
     * A global lyrics calibration in milliseconds, added to (or subtracted from) the song position
     * before the highlighted line is chosen. Positive values delay the lyrics, negative ones show
     * them earlier, so the words can be lined up with what is actually heard. Set from the +/- bar
     * in the player panel.
     */
    @JvmStatic
    var lyricsOffsetMs by IntSetting(getKey(R.string.setting_key_lyrics_offset), 0)

    /**
     * Lyrics font size in sp, shared by the right-hand player pane, the full-screen player and the
     * floating window. 12 sp is comfortable on the small pane, 30 sp fills a wide banner.
     */
    @JvmStatic
    var lyricsFontSizeSp by IntSetting(getKey(R.string.setting_key_lyrics_font_size), 16)

    /** Side of the song/controls panel inside a banner floating window ("left" or "right"). */
    @JvmStatic
    var floatingBannerPanelSide by StringSetting(
        getKey(R.string.setting_key_floating_banner_panel_side), "left"
    )

    /**
     * How many lines of lyrics the half-width / minimal-lyrics floating window shows, 1 to 5.
     *
     * The height of the window follows this number and the lyrics font size, so a driver that
     * wants to read ahead keeps the whole block short instead of covering the map.
     */
    @JvmStatic
    var floatingLyricLines by IntSetting(getKey(R.string.setting_key_floating_lyric_lines), 2)

    /**
     * For how long the half-width and minimal-lyrics floating window shows the cover, title and
     * artist before it collapses to the lyrics alone.
     *
     * The song information is only interesting for a moment after a switch; then the lyrics
     * are what the driver wants to see. The window shows the detail block for this many seconds
     * and then the text block fades to the lyric lines. 0 disables the collapse.
     */
    @JvmStatic
    var floatingDetailSeconds by IntSetting(getKey(R.string.setting_key_floating_detail_seconds), 8)

    /**
     * Pass-through mode of the floating window.
     *
     * A locked window ignores taps, but the plate of the window still blocks the touches that
     * land on it, so the map underneath cannot be reached. Pass-through lets every touch pass
     * through the window to the app below: the window becomes pure display. It can only be
     * switched off in the settings, because no tap can reach the window any more.
     */
    @JvmStatic
    var floatingPassThrough by BooleanSetting(getKey(R.string.setting_key_floating_pass_through), false)

    /**
     * Hides the floating window and pauses the music while the 360 camera or the reverse gear is
     * active, and brings both back when they are off again.
     *
     * This is a driving-safety feature: while the driver watches the surround view or the rear,
     * a window over the screen and a song playing are both distractions. It is on by default.
     */
    @JvmStatic
    var floatingReverseSafety by BooleanSetting(getKey(R.string.setting_key_floating_reverse_safety), true)

    /** Number of songs kept on disk ahead of the current one, 0 disables the preloading. */
    @JvmStatic
    var carPreload by StringIntSetting(getKey(R.string.setting_key_car_preload), 1)

    @JvmStatic
    val maxBitRate: Int
        get() {
            return if (Util.isNetworkRestricted()) {
                maxBitRateMobile
            } else {
                maxBitRateWifi
            }
        }

    private var maxBitRateWifi
        by StringIntSetting(getKey(R.string.setting_key_max_bitrate_wifi))

    private var maxBitRateMobile
        by StringIntSetting(getKey(R.string.setting_key_max_bitrate_mobile))

    var maxBitRatePinning
        by StringIntSetting(getKey(R.string.setting_key_max_bitrate_pinning))
    val pinWithHighestQuality: Boolean
        get() = (maxBitRatePinning == 0)

    @JvmStatic
    val preloadCount: Int
        get() {
            val preloadCount =
                preferences.getString(getKey(R.string.setting_key_preload_count), "-1")!!
                    .toInt()
            return if (preloadCount == -1) Int.MAX_VALUE else preloadCount
        }

    val parallelDownloads by IntSetting(getKey(R.string.setting_key_parallel_downloads), 3)

    @JvmStatic
    val cacheSizeMB: Int
        get() {
            val cacheSize = preferences.getString(
                getKey(R.string.setting_key_cache_size),
                "-1"
            )!!.toInt()
            return if (cacheSize == -1) Int.MAX_VALUE else cacheSize
        }

    @JvmStatic
    var customCacheLocation by BooleanSetting(
        getKey(R.string.setting_key_custom_cache_location),
        false
    )

    @JvmStatic
    var cacheLocationUri by StringSetting(
        getKey(R.string.setting_key_cache_location), ""
    )

    @JvmStatic
    var isWifiRequiredForDownload by BooleanSetting(
        getKey(R.string.setting_key_wifi_required_for_download),
        false
    )

    @JvmStatic
    var shareOnServer by BooleanSetting(getKey(R.string.setting_key_share_on_server), true)

    @JvmStatic
    var shouldDisplayBitrateWithArtist by BooleanSetting(
        getKey(R.string.setting_key_display_bitrate_with_artist),
        true
    )

    @JvmStatic
    var shouldUseFolderForArtistName
        by BooleanSetting(getKey(R.string.setting_key_use_folder_for_album_artist), false)

    @JvmStatic
    var shouldShowTrackNumber
        by BooleanSetting(getKey(R.string.setting_key_show_track_number), false)

    @JvmStatic
    var defaultAlbums
        by StringIntSetting(getKey(R.string.setting_key_default_albums), 5)

    @JvmStatic
    var maxAlbums
        by StringIntSetting(getKey(R.string.setting_key_max_albums), 20)

    @JvmStatic
    var defaultSongs
        by StringIntSetting(getKey(R.string.setting_key_default_songs), 10)

    @JvmStatic
    var maxSongs
        by StringIntSetting(getKey(R.string.setting_key_max_songs), 25)

    @JvmStatic
    var maxArtists
        by StringIntSetting(getKey(R.string.setting_key_max_artists), 10)

    @JvmStatic
    var defaultArtists
        by StringIntSetting(getKey(R.string.setting_key_default_artists), 3)

    @JvmStatic
    var seekInterval
        by StringIntSetting(getKey(R.string.setting_key_increment_time), 5000)

    val seekIntervalMillis: Long
        get() = (seekInterval / 1000).toLong()

    var resumePlayOnHeadphonePlug
        by BooleanSetting(R.string.setting_key_resume_play_on_headphones_plug, true)

    @JvmStatic
    var resumeOnBluetoothDevice by IntSetting(
        getKey(R.string.setting_key_resume_on_bluetooth_device),
        Constants.PREFERENCE_VALUE_DISABLED
    )

    @JvmStatic
    var pauseOnBluetoothDevice by IntSetting(
        getKey(R.string.setting_key_pause_on_bluetooth_device),
        Constants.PREFERENCE_VALUE_A2DP
    )

    @JvmStatic
    var showNowPlaying
        by BooleanSetting(getKey(R.string.setting_key_show_now_playing), true)

    /**
     * Keeps the UI in landscape orientation even if the device reports a rotation.
     * Enabled by default because the app is used on landscape car head units.
     */
    @JvmStatic
    var forceLandscape
        by BooleanSetting(getKey(R.string.setting_key_force_landscape), true)

    @JvmStatic
    var twoPaneLayout
        by BooleanSetting(getKey(R.string.setting_key_two_pane_layout), true)

    /**
     * Restores the last queue and continues playing right away when the app is opened.
     * A head unit is started together with the car, so this is the expected behaviour there.
     */
    @JvmStatic
    var resumeOnStart
        by BooleanSetting(getKey(R.string.setting_key_resume_on_start), true)

    /**
     * Maps the DPAD up/down keys of a car steering wheel to previous/next track.
     * Disabled by default, because the same keys scroll the lists.
     */
    @JvmStatic
    var dpadAsMediaKeys
        by BooleanSetting(getKey(R.string.setting_key_dpad_as_media_keys), false)

    @JvmStatic
    var shouldTransitionOnPlayback by BooleanSetting(
        getKey(R.string.setting_key_download_transition),
        true
    )

    @JvmStatic
    var showNowPlayingDetails
        by BooleanSetting(getKey(R.string.setting_key_show_now_playing_details), false)

    var scrobbleEnabled by BooleanSetting(getKey(R.string.setting_key_scrobble), false)

    // Normally you don't need to use these Settings directly,
    // use ActiveServerProvider.isID3Enabled() instead
    @JvmStatic
    var id3TagsEnabledOnline by BooleanSetting(getKey(R.string.setting_key_id3_tags), true)

    // See comment above.
    @JvmStatic
    var id3TagsEnabledOffline by BooleanSetting(getKey(R.string.setting_key_id3_tags_offline), true)

    var activeServer by IntSetting(getKey(R.string.setting_key_server_instance), -1)

    /**
     * The id of the online server the user was on before switching to offline mode, so that
     * the home-screen "Offline mode" tile can return to it. -1 means "no remembered server".
     */
    var lastOnlineServerId by IntSetting(getKey(R.string.setting_key_last_online_server), -1)

    /**
     * End of the sleep timer as epoch milliseconds. 0 means the timer is off. Persisted so a
     * restarted process keeps counting down instead of silently dropping the driver's timer.
     */
    var sleepTimerEndAt by LongSetting(getKey(R.string.setting_key_sleep_timer_end), 0L)

    /**
     * The preset in minutes the sleep timer was started with, so a single tap can advance to
     * the next preset rather than having to remember how much time was left.
     */
    var sleepTimerMinutes by IntSetting(getKey(R.string.setting_key_sleep_timer_minutes), 0)

    /**
     * Whether a new version is looked for when the app starts. Nothing is downloaded and no
     * dialog is shown while driving: a new version is announced with a notification the driver
     * can deal with when he is parked.
     */
    var updateCheckOnStart by BooleanSetting(getKey(R.string.setting_key_update_check_on_start), true)

    /**
     * A place of the driver's own to read the update file from, tried before GitHub. GitHub is
     * not reachable from every network, so this is the way out when it is not.
     */
    var updateMirrorUrl by StringSetting(getKey(R.string.setting_key_update_mirror_url), "")

    /**
     * The version the driver chose not to install. Newer versions are still offered.
     */
    var updateSkipVersionCode by IntSetting(getKey(R.string.setting_key_update_skip_version), 0)

    var serverScaling by BooleanSetting(getKey(R.string.setting_key_server_scaling), false)

    var firstRunExecuted by BooleanSetting(getKey(R.string.setting_key_first_run_executed), false)

    val shouldShowArtistPicture
        by BooleanSetting(getKey(R.string.setting_key_show_artist_picture), true)

    @JvmStatic
    var chatRefreshInterval by StringIntSetting(
        getKey(R.string.setting_key_chat_refresh_interval),
        5000
    )

    var directoryCacheTime by StringIntSetting(
        getKey(R.string.setting_key_directory_cache_time),
        300
    )

    var shouldSortByDisc
        by BooleanSetting(getKey(R.string.setting_key_disc_sort), false)

    var shouldClearBookmark
        by BooleanSetting(getKey(R.string.setting_key_clear_bookmark), false)

    var shouldAskForShareDetails
        by BooleanSetting(getKey(R.string.setting_key_ask_for_share_details), true)

    var defaultShareDescription
        by StringSetting(getKey(R.string.setting_key_default_share_description), "")

    @JvmStatic
    val shareGreeting: String?
        get() {
            val context = Util.appContext()
            val defaultVal = String.format(
                context.resources.getString(R.string.share_default_greeting),
                context.resources.getString(R.string.common_appname)
            )
            return preferences.getString(
                getKey(R.string.setting_key_default_share_greeting),
                defaultVal
            )
        }

    var defaultShareExpiration by StringSetting(
        getKey(R.string.setting_key_default_share_expiration),
        "0"
    )

    val defaultShareExpirationInMillis: Long
        get() {
            val preference = defaultShareExpiration
            val split = COLON_PATTERN.split(preference)
            if (split.size == 2) {
                val timeSpanAmount = split[0].toLong()
                val timeSpanType = split[1]
                return TimeSpanPicker.calculateTimeSpan(appContext, timeSpanType, timeSpanAmount)
            }
            return 0
        }

    @JvmStatic
    var debugLogToFile by BooleanSetting(getKey(R.string.setting_key_debug_log_to_file), false)

    @JvmStatic
    val overrideLanguage by StringSetting(getKey(R.string.setting_key_override_language), "")

    var useFiveStarRating by BooleanSetting(
        getKey(R.string.setting_key_use_five_star_rating),
        false
    )

    var useHwOffload by BooleanSetting(getKey(R.string.setting_key_hardware_offload), false)

    @JvmStatic
    var firstInstalledVersion by IntSetting(
        getKey(R.string.setting_key_first_installed_version),
        0
    )

    @JvmStatic
    var showConfirmationDialog by BooleanSetting(
        getKey(R.string.setting_key_show_confirmation_dialog),
        false
    )

    @JvmStatic
    var lastViewType by IntSetting(
        getKey(R.string.setting_key_last_view_type),
        0
    )

    fun hasKey(key: String): Boolean {
        return preferences.contains(key)
    }

    private fun getKey(key: Int): String {
        return appContext.getString(key)
    }

    fun getAllKeys(): List<String> {
        return preferences.all.keys.toList()
    }

    private val appContext: Context
        get() = UApp.applicationContext()

    val COLON_PATTERN: Pattern = Pattern.compile(":")
}
