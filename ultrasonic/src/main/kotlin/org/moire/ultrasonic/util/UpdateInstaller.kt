/*
 * UpdateInstaller.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.moire.ultrasonic.R
import org.moire.ultrasonic.activity.NavigationActivity
import java.io.File

/**
 * Hands a downloaded APK to the system installer, and announces a new version.
 *
 * Installing is never done on its own: Android will not let an app install silently unless it
 * is signed by the system, so the best this can do is put the installer on the screen with
 * everything already filled in, and let the driver press the one button that is left.
 */
object UpdateInstaller {

    private const val CHANNEL_ID = "update"
    private const val CHANNEL_NAME = "Updates"
    private const val NOTIFICATION_ID = 4711

    /** The version of this installation, as the number the update file is compared against. */
    fun currentVersionCode(context: Context): Int {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionCode
        }.getOrDefault(0)
    }

    /**
     * Whether the installer would accept an APK from us right now.
     *
     * On Android 8 and newer this is a per app switch the user has to turn on once; on older
     * systems it is the global "unknown sources" switch, which cannot be read here.
     */
    fun mayInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Sends the driver to the switch that lets this app install updates. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Opens the installer on the APK that was downloaded.
     *
     * @return false when the file is gone or no installer could be started, so the caller can
     * say something instead of leaving the driver looking at a dead screen.
     */
    @Suppress("DEPRECATION")
    fun install(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Tells the driver, once, that a newer version exists.
     *
     * A notification and not a dialog: this also runs while the car is moving, and something
     * that covers the screen and waits for an answer has no business appearing then.
     */
    fun announce(context: Context, info: UpdateInfo) {
        if (!areNotificationsAllowed(context)) return

        val manager = NotificationManagerCompat.from(context)
        createChannel(context)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val openApp = Intent(context, NavigationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or flags
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(
                context.getString(R.string.update_notification_title, info.versionName)
            )
            .setContentText(context.getString(R.string.update_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.update_notification_text)
                )
            )
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun areNotificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
