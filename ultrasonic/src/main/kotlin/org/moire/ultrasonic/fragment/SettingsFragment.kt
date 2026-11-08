package org.moire.ultrasonic.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.SearchRecentSuggestions
import android.view.View
import androidx.annotation.StringRes
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.math.ceil
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.R
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.fragment.FragmentTitle.setTitle
import org.moire.ultrasonic.log.FileLoggerTree
import org.moire.ultrasonic.log.FileLoggerTree.Companion.deleteLogFiles
import org.moire.ultrasonic.log.FileLoggerTree.Companion.getLogFileNumber
import org.moire.ultrasonic.log.FileLoggerTree.Companion.getLogFileSizes
import org.moire.ultrasonic.log.FileLoggerTree.Companion.plantToTimberForest
import org.moire.ultrasonic.log.FileLoggerTree.Companion.uprootFromTimberForest
import org.moire.ultrasonic.provider.SearchSuggestionProvider
import org.moire.ultrasonic.service.FloatingPlayerService
import org.moire.ultrasonic.service.MediaPlayerManager
import org.moire.ultrasonic.service.PlaybackService
import org.moire.ultrasonic.service.RxBus
import org.moire.ultrasonic.util.ConfirmationDialog
import org.moire.ultrasonic.util.Constants
import org.moire.ultrasonic.util.ErrorDialog
import org.moire.ultrasonic.util.FileUtil.ultrasonicDirectory
import org.moire.ultrasonic.util.CameraSignalBus
import org.moire.ultrasonic.util.CameraWatchAccessibilityService
import org.moire.ultrasonic.util.ForegroundPackageMonitor
import org.moire.ultrasonic.util.ZeroPermissionCameraDetector
import org.moire.ultrasonic.util.HomePrefs
import org.moire.ultrasonic.util.InfoDialog
import org.moire.ultrasonic.util.SelectCacheActivityContract
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.Settings.id3TagsEnabledOnline
import org.moire.ultrasonic.util.Settings.preferences
import org.moire.ultrasonic.util.Storage
import org.moire.ultrasonic.util.UpdateInfo
import org.moire.ultrasonic.util.UpdateInstaller
import org.moire.ultrasonic.util.UpdateDownloadResult
import org.moire.ultrasonic.util.UpdateFailure
import org.moire.ultrasonic.util.UpdateManager
import org.moire.ultrasonic.util.TimeSpanPreference
import org.moire.ultrasonic.util.TimeSpanPreferenceDialogFragmentCompat
import org.moire.ultrasonic.util.Util.toast
import timber.log.Timber

/**
 * Shows main app settings.
 */
@Suppress("TooManyFunctions")
class SettingsFragment :
    PreferenceFragmentCompat(),
    OnSharedPreferenceChangeListener,
    KoinComponent {
    private var theme: ListPreference? = null

    private var cacheLocation: Preference? = null
    private var showArtistPicture: CheckBoxPreference? = null
    private var useId3TagsOffline: CheckBoxPreference? = null
    private var resumeOnBluetoothDevice: Preference? = null
    private var pauseOnBluetoothDevice: Preference? = null
    private var debugLogToFile: CheckBoxPreference? = null
    private var customCacheLocation: CheckBoxPreference? = null

    private val mediaPlayerManager: MediaPlayerManager by inject()
    private val updateManager: UpdateManager by inject()

    /** The version offered by the last look for an update, kept until it is acted on. */
    private var offeredUpdate: UpdateInfo? = null
    private var checkUpdatePreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(this, R.string.menu_settings)
        theme = findPreference(getString(R.string.setting_key_palette))
        resumeOnBluetoothDevice =
            findPreference(getString(R.string.setting_key_resume_on_bluetooth_device))
        pauseOnBluetoothDevice =
            findPreference(getString(R.string.setting_key_pause_on_bluetooth_device))
        debugLogToFile = findPreference(getString(R.string.setting_key_debug_log_to_file))
        showArtistPicture = findPreference(getString(R.string.setting_key_show_artist_picture))
        useId3TagsOffline = findPreference(getString(R.string.setting_key_id3_tags_offline))
        customCacheLocation = findPreference(getString(R.string.setting_key_custom_cache_location))
        cacheLocation = findPreference(getString(R.string.setting_key_cache_location))
        checkUpdatePreference = findPreference(getString(R.string.setting_key_update_check_now))

        setupClearSearchPreference()
        setupCacheLocationPreference()
        setupBluetoothDevicePreferences()
        setupUpdatePreferences()
        setupHomePlaylistPreferences()
        setupFloatingPreferences()
    }

    /**
     * Lets the driver pin playlists to the front of the home grid. Pinning never hides the
     * others: the grid scrolls sideways, so every playlist stays one swipe away.
     */
    private fun setupHomePlaylistPreferences() {
        findPreference<Preference>(getString(R.string.setting_key_home_playlists_pinned))
            ?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                showPinnedPlaylistsDialog()
                true
            }
    }

    private fun showPinnedPlaylistsDialog() {
        val context = context ?: return
        val playlists = HomePrefs.getCachedPlaylists()
        if (playlists.isEmpty()) {
            toast(R.string.home_playlist_pin_empty, context = context)
            return
        }
        val selected = HomePrefs.getVisiblePlaylistIds().orEmpty()
        val checked = BooleanArray(playlists.size) { i -> selected.contains(playlists[i].id) }
        val names = playlists.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_playlist_pin_dialog_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HomePrefs.setVisiblePlaylistIds(
                    playlists.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                )
                toast(R.string.home_playlist_pin_saved, context = context)
            }
            .setNeutralButton(R.string.home_playlist_pin_reset) { _, _ ->
                HomePrefs.clearVisiblePlaylistIds()
                toast(R.string.home_playlist_pin_saved, context = context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Read-only diagnostic: which zero-permission signal last hid the window. On a
     * real head unit this says whether the camera was seen at all, without adb.
     */
    private fun setupFloatingPreferences() {
        findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_adb_grant)
        )?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val command = getString(R.string.floating_camera_adb_command)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.floating_camera_adb_grant_dialog_title)
                .setMessage(getString(R.string.floating_camera_adb_grant_dialog))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.floating_camera_adb_grant_copy) { _, _ ->
                    val clipboard = requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("NavMusic ADB command", command)
                    )
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.floating_camera_adb_grant_copied,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                .show()
            true
        }
        findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_detection_service)
        )?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (all: Exception) {
                Timber.w(all, "Accessibility settings not available on this head unit")
            }
            true
        }
        findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_temp_hide)
        )?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            FloatingPlayerService.hideTemporarily(requireContext())
            true
        }
        findPreference<Preference>(
            getString(R.string.setting_key_floating_manual_toggle)
        )?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            FloatingPlayerService.setManuallyHidden(
                requireContext(), !FloatingPlayerService.isManuallyHidden()
            )
            updateCameraSignalSummary()
            true
        }
        updateCameraSignalSummary()
    }

    private fun updateCameraSignalSummary() {
        val context = context ?: return
        val enabled = CameraWatchAccessibilityService.isEnabled(context)
        val usage = ForegroundPackageMonitor.hasUsageAccess(context)
        CameraSignalBus.accessibilityEnabled = enabled
        CameraSignalBus.usageAccessGranted = usage

        findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_detection_service)
        )?.summary = getString(
            if (enabled) {
                R.string.floating_camera_detection_service_on
            } else {
                R.string.floating_camera_detection_service_off
            }
        )

        val row = findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_signal_status)
        ) ?: return
        val state = if (CameraSignalBus.isActive()) {
            getString(R.string.floating_camera_signal_active)
        } else {
            getString(R.string.floating_camera_signal_idle)
        }
        val on = getString(R.string.floating_camera_source_on)
        val off = getString(R.string.floating_camera_source_off)
        val sources = getString(
            R.string.floating_camera_signal_sources,
            if (usage) on else off,
            if (enabled) on else off,
            if (CameraSignalBus.bydApiAvailable) on else off,
            if (Settings.floatingCameraAudioFocus) on else off,
            if (FloatingPlayerService.isManuallyHidden()) on else off,
            CameraSignalBus.lastForegroundPackage ?: "-",
            CameraSignalBus.audioFocusLastEvent
        )
        row.summary = getString(
            R.string.floating_camera_signal_status_summary,
            CameraSignalBus.lastReason,
            state,
            sources
        )

        findPreference<Preference>(
            getString(R.string.setting_key_floating_camera_fallback)
        )?.summary = getString(
            R.string.floating_camera_fallback_summary,
            if (ZeroPermissionCameraDetector.running) on else off,
            ZeroPermissionCameraDetector.lastObservation,
            when (ZeroPermissionCameraDetector.dumpsysAvailable) {
                true -> on
                false -> off
                else -> "-"
            }
        )
    }

    /**
     * The update screen. Looking for a version is one tap, because the driver has no store on
     * the head unit to do it for him.
     */
    private fun setupUpdatePreferences() {
        checkUpdatePreference?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                lookForUpdate(userAsked = true)
                false
            }

        val mirror = findPreference<EditTextPreference>(
            getString(R.string.setting_key_update_mirror_url)
        )
        mirror?.setOnBindEditTextListener { editText ->
            editText.setSingleLine(true)
        }
    }

    /**
     * Asks every source in turn for the newest version.
     *
     * @param userAsked true when the driver pressed "check for updates": then he is told either
     * way, including that this is already the newest version. The silent check at start up only
     * says something when there really is something newer.
     */
    private fun lookForUpdate(userAsked: Boolean) {
        val context = context ?: return
        if (userAsked) {
            toast(R.string.settings_update_check_title, context = context)
        }
        checkUpdatePreference?.isEnabled = false

        updateManager.check { result ->
            checkUpdatePreference?.isEnabled = true
            val fragmentContext = this.context ?: return@check
            val info = result.info

            when {
                info == null -> {
                    if (userAsked) toast(R.string.update_check_failed, context = fragmentContext)
                }
                info.versionCode <= UpdateInstaller.currentVersionCode(fragmentContext) -> {
                    if (userAsked) toast(R.string.update_up_to_date, context = fragmentContext)
                }
                info.versionCode == Settings.updateSkipVersionCode -> {
                    if (userAsked) {
                        // The driver asked by hand, so re-offer even a version he skipped before.
                        offeredUpdate = info
                        showUpdateDialog(info)
                    } else {
                        toast(R.string.update_up_to_date, context = fragmentContext)
                    }
                }
                else -> {
                    offeredUpdate = info
                    showUpdateDialog(info)
                }
            }
        }
    }

    /** Offers the version that was found, and downloads it if the driver wants it. */
    private fun showUpdateDialog(info: UpdateInfo) {
        val context = context ?: return
        val changes = if (info.changelog.isEmpty()) {
            context.getString(R.string.update_no_changelog)
        } else {
            info.changelog.joinToString(separator = "\n• ", prefix = "• ")
        }

        val message = buildString {
            append(context.getString(R.string.update_available_title, info.versionName))
            append("\n\n")
            append(changes)
        }

        ConfirmationDialog.Builder(context, message)
            .setPositiveButton(R.string.update_available_download) { _, _ ->
                downloadUpdate(info)
            }
            .setNegativeButton(R.string.update_available_skip) { _, _ ->
                Settings.updateSkipVersionCode = info.versionCode
            }
            .show()
    }

    private fun downloadUpdate(info: UpdateInfo) {
        val context = context ?: return
        var lastShown = -1
        updateManager.download(
            info,
            onProgress = { percent ->
                // A toast per percent would be unusable, so it only moves in steps of ten.
                if (percent / 10 > lastShown / 10 && percent < 100) {
                    lastShown = percent
                    toast(
                        getString(R.string.update_downloading, percent),
                        context = context
                    )
                }
            },
            onDone = { result ->
                val fragmentContext = this.context ?: return@download
                val file = result.file
                if (file == null) {
                    showDownloadFailure(info, result)
                    return@download
                }
                if (!UpdateInstaller.mayInstall(fragmentContext)) {
                    toast(
                        R.string.update_install_permission_needed,
                        context = fragmentContext
                    )
                    UpdateInstaller.openInstallPermissionSettings(fragmentContext)
                    return@download
                }
                if (!UpdateInstaller.install(fragmentContext, file)) {
                    toast(R.string.update_install_failed, context = fragmentContext)
                }
            }
        )
    }

    /**
     * Says why the download failed instead of lumping network errors together with a bad
     * checksum, and leaves the driver a way out: retry, or open the APK in the browser.
     */
    private fun showDownloadFailure(info: UpdateInfo, result: UpdateDownloadResult) {
        val dialogContext = context ?: return
        val reason = getString(
            when (result.failure) {
                UpdateFailure.CHECKSUM -> R.string.update_failure_checksum
                UpdateFailure.STORAGE -> R.string.update_failure_storage
                else -> R.string.update_failure_network
            }
        )
        val message = buildString {
            append(getString(R.string.update_download_failed, reason, result.attempts))
            append("\n\n")
            append(getString(R.string.update_download_source, info.downloadUrl))
        }
        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.update_download_failed_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download_retry) { _, _ -> downloadUpdate(info) }
            .setNeutralButton(R.string.update_download_browser) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentVersionCode(): Int {
        val context = this.context ?: return 0
        return UpdateInstaller.currentVersionCode(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get all setting keys and populate the summaries
        Settings.getAllKeys().forEach {
            updatePreferenceSummaries(it)
        }

        updateCustomPreferences()
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(this)
        // Refresh the camera-detection diagnostic after returning from another screen.
        updateCameraSignalSummary()
    }

    override fun onPause() {
        super.onPause()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || sharedPreferences == null) return

        Timber.d("Preference changed: %s", key)
        updateCustomPreferences()

        updatePreferenceSummaries(key)

        when (key) {
            getString(R.string.setting_key_hide_media) -> {
                setHideMedia(sharedPreferences.getBoolean(key, false))
            }
            getString(R.string.setting_key_debug_log_to_file) -> {
                setDebugLogToFile(sharedPreferences.getBoolean(key, false))
            }
            getString(R.string.setting_key_id3_tags) -> {
                val enabled = sharedPreferences.getBoolean(key, false)
                showArtistPicture?.isEnabled = enabled
                useId3TagsOffline?.isEnabled = enabled
            }
            getString(R.string.setting_key_palette) -> {
                RxBus.themeChangedEventPublisher.onNext(Unit)
            }
            getString(R.string.setting_key_floating_mode) -> {
                applyFloatingMode()
            }
            getString(R.string.setting_key_nav_floating),
            getString(R.string.setting_key_floating_locked) -> {
                applyFloatingGeometry()
            }
            getString(R.string.setting_key_floating_side) -> {
                // Changing the dock side re-docks the window to that side, discarding any
                // freely dragged position so the choice actually takes effect.
                Settings.floatingX = -1
                Settings.floatingY = -1
                applyFloatingGeometry()
            }
            getString(R.string.setting_key_floating_banner_panel_side) -> {
                // Keep the dragged position, only re-layout the banner panel side.
                applyFloatingGeometry()
            }
            getString(R.string.setting_key_floating_bg_opacity),
            getString(R.string.setting_key_floating_text_color),
            getString(R.string.setting_key_floating_text_shadow),
            getString(R.string.setting_key_floating_idle_brightness),
            getString(R.string.setting_key_lyrics_font_size),
            getString(R.string.setting_key_floating_lyric_lines) -> {
                applyFloatingAppearance()
            }
            getString(R.string.setting_key_floating_detail_seconds),
            getString(R.string.setting_key_floating_pass_through) -> {
                // Geometry of the running window, then re-apply pass-through so the touch
                // flags follow the new setting immediately.
                applyFloatingGeometry()
                FloatingPlayerService.applyPassThroughFromSettings()
            }
            getString(R.string.setting_key_follow_night) -> {
                // The night dimming is baked into the window background, so the UI is rebuilt
                RxBus.themeChangedEventPublisher.onNext(Unit)
            }
            getString(R.string.setting_key_custom_cache_location) -> {
                if (Settings.customCacheLocation) {
                    selectCacheLocation()
                } else {
                    if (Settings.cacheLocationUri != "") setCacheLocation("")
                    setupCacheLocationPreference()
                }
            }
        }
    }

    /**
     * Applies the floating window choice immediately. The overlay permission has to be granted
     * first, otherwise the window would silently never appear, so the user is sent to the system
     * screen that grants it.
     */
    private fun applyFloatingMode() {
        val ctx = context ?: return
        val tier = FloatingPlayerService.tierForKey(Settings.floatingMode)

        if (tier == null) {
            FloatingPlayerService.hide(ctx)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(ctx)
        ) {
            toast(getString(R.string.floating_permission_needed), ctx)
            openOverlayPermissionScreen(ctx)
            return
        }

        FloatingPlayerService.show(ctx, tier)
    }

    /**
     * Applies the background opacity and the text colour to the window that is already open, so
     * the driver sees the result without leaving the settings screen.
     */
    private fun applyFloatingAppearance() {
        val ctx = context ?: return
        FloatingPlayerService.refreshAppearance(ctx)
    }

    /**
     * Applies the navigation overlay switch, the docked side and the lock right away, so the
     * user sees what the setting does without leaving the settings screen.
     */
    private fun applyFloatingGeometry() {
        val ctx = context ?: return
        if (Settings.navFloating) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(ctx)
            ) {
                openOverlayPermission()
                return
            }
            FloatingPlayerService.setNavigationMode(ctx, true)
            return
        }

        val tier = FloatingPlayerService.tierForKey(Settings.floatingMode)
        if (tier == null) {
            FloatingPlayerService.hide(ctx)
            return
        }
        FloatingPlayerService.show(ctx, tier, navMode = false)
    }

    private fun openOverlayPermission() {
        val ctx = context ?: return
        toast(getString(R.string.floating_permission_needed), ctx)
        openOverlayPermissionScreen(ctx)
    }

    /**
     * Opens the screen that grants the "draw over other apps" permission.
     *
     * That screen only exists from Android 6 on, and the constant of the system is inlined by
     * the compiler, so the action is spelled out here. On Android 5 the app details page is
     * opened instead, where the permission can be granted by hand.
     */
    private fun openOverlayPermissionScreen(ctx: Context) {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "android.settings.action.MANAGE_OVERLAY_PERMISSION"
        } else {
            "android.settings.APPLICATION_DETAILS_SETTINGS"
        }
        try {
            startActivity(Intent(action, Uri.parse("package:" + ctx.packageName)))
        } catch (all: Exception) {
            Timber.w(all, "Could not open the overlay permission screen")
        }
    }

    /**
     * Update preference summaries to reflect the current select item (or entered text) in the UI
     *
     * @param key: The key of the preference to update
     */
    private fun updatePreferenceSummaries(key: String) {
        try {
            when (val pref: Preference? = findPreference(key)) {
                is ListPreference -> {
                    pref.summary = pref.entry
                }
                is EditTextPreference -> {
                    pref.summary = pref.text
                }
                is TimeSpanPreference -> {
                    pref.summary = pref.text
                }
            }
        } catch (ignored: Exception) {
            // If we have updated a ListPreferences possible values, and the user has now an
            // impossible value, getEntry() will throw an Exception.
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is TimeSpanPreference) {
            val dialogFragment = TimeSpanPreferenceDialogFragmentCompat()
            val bundle = Bundle(1)
            bundle.putString("key", preference.getKey())
            dialogFragment.arguments = bundle
            @Suppress("DEPRECATION") // Their own super class uses this call :shrug:
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(
                this.parentFragmentManager,
                "androidx.preference.PreferenceFragment.DIALOG"
            )
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun setupCacheLocationPreference() {
        if (!Settings.customCacheLocation) {
            cacheLocation?.isVisible = false
            return
        }

        cacheLocation?.isVisible = true
        val uri = Uri.parse(Settings.cacheLocationUri)
        cacheLocation!!.summary = uri.path
        cacheLocation!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            selectCacheLocation()
            true
        }
    }

    private fun selectCacheLocation() {
        // Start the activity to pick a directory using the system's file picker.
        selectCacheActivityContract.launch(Settings.cacheLocationUri)
    }

    // Custom activity result contract
    private val selectCacheActivityContract =
        registerForActivityResult(SelectCacheActivityContract()) { uri ->
            // parseResult will return the chosen path as an Uri
            if (uri != null) {
                val contentResolver = UApp.applicationContext().contentResolver
                contentResolver.takePersistableUriPermission(uri, RW_FLAG)
                setCacheLocation(uri.toString())
                setupCacheLocationPreference()
            } else {
                ErrorDialog.Builder(requireContext())
                    .setMessage(R.string.settings_cache_location_error)
                    .show()
                if (Settings.cacheLocationUri == "") {
                    Settings.customCacheLocation = false
                    customCacheLocation?.isChecked = false
                    setupCacheLocationPreference()
                }
            }
        }

    private fun setupBluetoothDevicePreferences() {
        val resumeSetting = Settings.resumeOnBluetoothDevice
        val pauseSetting = Settings.pauseOnBluetoothDevice
        resumeOnBluetoothDevice!!.summary = bluetoothDevicePreferenceToString(resumeSetting)
        pauseOnBluetoothDevice!!.summary = bluetoothDevicePreferenceToString(pauseSetting)
        resumeOnBluetoothDevice!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                showBluetoothDevicePreferenceDialog(
                    R.string.settings_playback_resume_on_bluetooth_device,
                    Settings.resumeOnBluetoothDevice
                ) { choice: Int ->
                    Settings.resumeOnBluetoothDevice = choice
                    resumeOnBluetoothDevice!!.summary = bluetoothDevicePreferenceToString(choice)
                }
                true
            }
        pauseOnBluetoothDevice!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                showBluetoothDevicePreferenceDialog(
                    R.string.settings_playback_pause_on_bluetooth_device,
                    Settings.pauseOnBluetoothDevice
                ) { choice: Int ->
                    Settings.pauseOnBluetoothDevice = choice
                    pauseOnBluetoothDevice!!.summary = bluetoothDevicePreferenceToString(choice)
                }
                true
            }
    }

    private fun showBluetoothDevicePreferenceDialog(
        @StringRes title: Int,
        defaultChoice: Int,
        onChosen: (Int) -> Unit
    ) {
        val choice = intArrayOf(defaultChoice)
        ConfirmationDialog.Builder(requireContext()).setTitle(title)
            .setSingleChoiceItems(
                R.array.bluetoothDeviceSettingNames, defaultChoice
            ) { _: DialogInterface?, i: Int -> choice[0] = i }
            .setNegativeButton(R.string.common_cancel) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.cancel()
            }
            .setPositiveButton(R.string.common_ok) { dialogInterface: DialogInterface, _: Int ->
                onChosen(choice[0])
                dialogInterface.dismiss()
            }
            .create().show()
    }

    private fun bluetoothDevicePreferenceToString(preferenceValue: Int): String {
        return when (preferenceValue) {
            Constants.PREFERENCE_VALUE_ALL -> {
                getString(R.string.settings_playback_bluetooth_all)
            }
            Constants.PREFERENCE_VALUE_A2DP -> {
                getString(R.string.settings_playback_bluetooth_a2dp)
            }
            Constants.PREFERENCE_VALUE_DISABLED -> {
                getString(R.string.settings_playback_bluetooth_disabled)
            }
            else -> ""
        }
    }

    private fun setupClearSearchPreference() {
        val clearSearchPreference =
            findPreference<Preference>(getString(R.string.setting_key_clear_search_history))
        if (clearSearchPreference != null) {
            clearSearchPreference.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    val suggestions = SearchRecentSuggestions(
                        activity,
                        SearchSuggestionProvider.AUTHORITY,
                        SearchSuggestionProvider.MODE
                    )
                    suggestions.clearHistory()
                    toast(R.string.settings_search_history_cleared)
                    false
                }
        }
    }

    private fun updateCustomPreferences() {
        if (debugLogToFile?.isChecked == true) {
            debugLogToFile?.summary = getString(
                R.string.settings_debug_log_path,
                ultrasonicDirectory, FileLoggerTree.FILENAME
            )
        } else {
            debugLogToFile?.summary = ""
        }

        showArtistPicture?.isEnabled = id3TagsEnabledOnline
        useId3TagsOffline?.isEnabled = id3TagsEnabledOnline
    }

    private fun setHideMedia(hide: Boolean) {
        // TODO this only hides the media files in the Ultrasonic dir and not in the music cache
        val nomediaDir = File(ultrasonicDirectory, ".nomedia")
        if (hide && !nomediaDir.exists()) {
            if (!nomediaDir.mkdir()) {
                Timber.w("Failed to create %s", nomediaDir)
            }
        } else if (nomediaDir.exists()) {
            if (!nomediaDir.delete()) {
                Timber.w("Failed to delete %s", nomediaDir)
            }
        }
        toast(R.string.settings_hide_media_toast, false)
    }

    private fun setCacheLocation(path: String) {
        if (path != "") {
            val uri = Uri.parse(path)
            cacheLocation!!.summary = uri.path ?: ""
        }

        Settings.cacheLocationUri = path

        // Clear download queue.
        mediaPlayerManager.clear()
        Storage.reset()
        Storage.checkForErrorsWithCustomRoot()
    }

    private fun setDebugLogToFile(writeLog: Boolean) {
        if (writeLog) {
            plantToTimberForest()
            Timber.i("Enabled debug logging to file")
        } else {
            uprootFromTimberForest()
            Timber.i("Disabled debug logging to file")
            val fileNum = getLogFileNumber()
            val fileSize = getLogFileSizes()
            val message = getString(
                R.string.settings_debug_log_summary,
                fileNum.toString(),
                ceil(fileSize.toDouble() / 1000 / 1000).toString(),
                ultrasonicDirectory
            )
            val keep = R.string.settings_debug_log_keep
            val delete = R.string.settings_debug_log_delete
            ConfirmationDialog.Builder(requireContext())
                .setMessage(message)
                .setNegativeButton(keep) { dIf: DialogInterface, _: Int ->
                    dIf.cancel()
                }
                .setPositiveButton(delete) { dIf: DialogInterface, _: Int ->
                    deleteLogFiles()
                    Timber.i("Deleted debug log files")
                    dIf.dismiss()
                    InfoDialog.Builder(requireContext())
                        .setMessage(R.string.settings_debug_log_deleted)
                        .setPositiveButton(R.string.common_ok) { dIf2: DialogInterface, _: Int ->
                            dIf2.dismiss()
                        }
                        .create().show()
                }
                .create().show()
        }
    }

    companion object {
        const val RW_FLAG = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val PERSISTABLE_FLAG = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}
