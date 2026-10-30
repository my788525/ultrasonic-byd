/*
 * UpdateManager.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * One version of the app, as the update file on GitHub describes it.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
    val publishedAt: String,
    val changelog: List<String>
) {
    companion object {
        /**
         * Reads the update file written by the release workflow.
         *
         * A version that cannot be understood is rejected instead of being half read: a broken
         * update file has to look like "no update" rather than like an update to something odd.
         */
        fun fromJson(text: String): UpdateInfo? {
            return runCatching {
                val root = JSONObject(text)
                val versionCode = root.optInt("versionCode", -1)
                val versionName = root.optString("versionName", "")
                val downloadUrl = root.optString("downloadUrl", "")
                if (versionCode <= 0 || versionName.isEmpty() || downloadUrl.isEmpty()) return null

                val changelog = mutableListOf<String>()
                val array = root.optJSONArray("changelog")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        changelog.add(array.optString(index, ""))
                    }
                }
                UpdateInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    sha256 = root.optString("sha256", ""),
                    size = root.optLong("size", 0L),
                    publishedAt = root.optString("publishedAt", ""),
                    changelog = changelog.filter { it.isNotEmpty() }
                )
            }.getOrNull()
        }
    }
}

/**
 * The outcome of one look for a newer version.
 *
 * [info] is the version the update file offers, whatever it is compared to the running one, so
 * that the caller can decide whether it is worth showing. It is null when no source could be
 * read, and then [errorMessage] says why.
 */
data class UpdateCheckResult(
    val info: UpdateInfo?,
    val errorMessage: String? = null
)

/**
 * Looks for a newer version of the app and downloads it.
 *
 * A car head unit has no app store on it, so until now every version had to be brought over
 * by hand. The file this reads is a small `update.json` in the repository, written by the
 * release workflow; it points at the APK attached to the matching GitHub release.
 *
 * GitHub is not reliably reachable from every network the car ends up on, so the file is asked
 * for from several mirrors in turn, and the driver can put his own in the settings. The first
 * one that answers with something readable wins.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val OWNER = "my788525"
        private const val REPO = "ultrasonic-byd"
        private const val BRANCH = "main"
        private const val FILE = "update.json"

        private const val GITHUB_RAW = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"
        private const val GH_PROXY = "https://gh-proxy.com/$GITHUB_RAW"
        private const val JSDELIVR = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH/$FILE"

        /** Prefix that turns a GitHub URL into one served by a mirror. */
        private const val DOWNLOAD_PROXY = "https://gh-proxy.com/"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val DOWNLOAD_DIR = "update"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Every place the update file is looked for, in the order they are tried.
     *
     * The mirror from the settings comes first: if the driver knows one that works on his
     * network, there is no reason to wait for the others to time out.
     */
    fun updateFileSources(): List<String> {
        val sources = mutableListOf<String>()
        val custom = Settings.updateMirrorUrl.trim().trimEnd('/')
        if (custom.isNotEmpty()) {
            sources.add(if (custom.endsWith(FILE)) custom else "$custom/$FILE")
        }
        sources.add(GITHUB_RAW)
        sources.add(GH_PROXY)
        sources.add(JSDELIVR)
        return sources
    }

    /**
     * Reads the update file. The callback runs on the main thread.
     */
    fun check(onDone: (UpdateCheckResult) -> Unit) {
        executor.execute {
            var info: UpdateInfo? = null
            var lastError: String? = null
            for (source in updateFileSources()) {
                try {
                    val text = readText(source)
                    val parsed = text?.let { UpdateInfo.fromJson(it) }
                    if (parsed != null) {
                        info = parsed
                        break
                    }
                    lastError = "unreadable: $source"
                } catch (exception: Exception) {
                    lastError = exception.message ?: exception.javaClass.simpleName
                }
            }
            val result = UpdateCheckResult(info, if (info == null) lastError else null)
            mainHandler.post { onDone(result) }
        }
    }

    /**
     * Downloads the APK and checks it against the hash in the update file.
     *
     * The hash is not decoration: a file that a proxy cut short or replaced has to be thrown
     * away before it reaches the installer. If the GitHub download fails, the same URL through
     * the mirror is tried once.
     *
     * @param onProgress called with the percentage, on the main thread.
     * @param onDone called with the file, or null when the download failed or the hash did not
     * match, on the main thread.
     */
    fun download(info: UpdateInfo, onProgress: (Int) -> Unit, onDone: (File?) -> Unit) {
        executor.execute {
            val target = File(downloadDir(), "NavMusic-${info.versionName}.apk")
            val candidates = listOf(info.downloadUrl, DOWNLOAD_PROXY + info.downloadUrl)
            var downloaded: File? = null

            for (url in candidates) {
                target.delete()
                val ok = runCatching {
                    downloadToFile(url, info.size, target) { percent ->
                        mainHandler.post { onProgress(percent) }
                    }
                }.getOrDefault(false)
                if (!ok) continue
                if (info.sha256.isEmpty() || sha256Of(target).equals(info.sha256, ignoreCase = true)) {
                    downloaded = target
                    break
                }
            }

            val result = downloaded
            mainHandler.post { onDone(result) }
        }
    }

    /** Deletes the APKs left over from earlier downloads, so the car does not fill up. */
    fun clearDownloads() {
        executor.execute {
            downloadDir().listFiles()?.forEach { it.delete() }
        }
    }

    /** Where a downloaded APK waits to be installed. */
    private fun downloadDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DOWNLOAD_DIR).apply { mkdirs() }
    }

    private fun readText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(
        url: String,
        expectedSize: Long,
        target: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false

            // contentLengthLong would be nicer, but it is API 24 and this still installs on
            // Android 5. The plain one only loses precision above 2 GB, which an APK of ours
            // never reaches.
            val total = if (expectedSize > 0L) expectedSize
            else connection.contentLength.toLong().coerceAtLeast(0L)
            var done = 0L
            var lastPercent = -1

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0L) {
                            val percent = ((done * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return target.length() > 0L
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
