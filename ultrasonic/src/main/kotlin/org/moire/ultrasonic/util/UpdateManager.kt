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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * One version of the app, as the update file on GitHub describes it.
 *
 * [downloadUrls] holds the extra places the APK can be fetched from. A car network may
 * reach raw.githubusercontent.com but time out on github.com release redirects, or the
 * other way round, so the update file now carries several addresses instead of one.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
    val publishedAt: String,
    val changelog: List<String>,
    val downloadUrls: List<String> = emptyList()
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

                val mirrors = mutableListOf<String>()
                for (key in listOf("downloadUrls", "mirrors")) {
                    val urls = root.optJSONArray(key) ?: continue
                    for (index in 0 until urls.length()) {
                        val value = urls.optString(index, "")
                        if (value.startsWith("https://") && !mirrors.contains(value)) {
                            mirrors.add(value)
                        }
                    }
                }

                UpdateInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    sha256 = root.optString("sha256", ""),
                    size = root.optLong("size", 0L),
                    publishedAt = root.optString("publishedAt", ""),
                    changelog = changelog.filter { it.isNotEmpty() },
                    downloadUrls = mirrors
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

/** Why no APK could be handed to the installer. */
enum class UpdateFailure { NETWORK, CHECKSUM, STORAGE }

/** The result of a download, including enough detail to explain a failure to the driver. */
data class UpdateDownloadResult(
    val file: File?,
    val failure: UpdateFailure? = null,
    val attempts: Int = 0,
    val lastUrl: String = ""
)

private enum class DownloadOutcome { SUCCESS, NETWORK, TRUNCATED, STORAGE }

/**
 * Looks for a newer version of the app and downloads it.
 *
 * A car head unit has no app store on it, so until now every version had to be brought over
 * by hand. The file this reads is a small `update.json` in the repository, written by the
 * release workflow; it points at the APK attached to the matching GitHub release and at a
 * plain static copy of the same APK on the `apk` branch.
 *
 * Neither GitHub nor any single proxy is reliably reachable from every network the car ends
 * up on. Both the update file and the APK are therefore tried at several addresses in turn,
 * and every download is checked against the size and hash in the update file before it is
 * allowed anywhere near the installer.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val OWNER = "my788525"
        private const val REPO = "ultrasonic-byd"
        private const val BRANCH = "main"
        private const val APK_BRANCH = "apk"
        private const val FILE = "update.json"

        private const val GITHUB_RAW = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"
        private const val GH_PROXY = "https://gh-proxy.com/$GITHUB_RAW"
        private const val JSDELIVR = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$BRANCH/$FILE"

        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val DOWNLOAD_DIR = "update"
        private const val USER_AGENT = "NavMusic-Updater/1.0"
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
     * Downloads the APK and checks it against the size and hash in the update file.
     *
     * Every address in [UpdateInfo.downloadUrls] is tried, followed by addresses derived
     * from the release URL and the static `apk` branch. A source that answers with an error
     * page, a JSON description or a truncated file is rejected and the next one is tried.
     *
     * @param onProgress called with the percentage, on the main thread.
     * @param onDone called with the file, or with the reason no file could be used, on the
     * main thread.
     */
    fun download(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: (UpdateDownloadResult) -> Unit
    ) {
        executor.execute {
            val target = File(downloadDir(), "NavMusic-${info.versionName}.apk")
            val urls = candidateUrls(info)
            var attempts = 0
            var lastUrl = ""
            var sawChecksumFailure = false
            var sawStorageFailure = false
            var downloaded: File? = null

            for (url in urls) {
                attempts++
                lastUrl = url
                target.delete()
                val outcome = downloadToFile(url, info.size, target) { percent ->
                    mainHandler.post { onProgress(percent) }
                }
                when (outcome) {
                    DownloadOutcome.SUCCESS ->
                        if (info.sha256.isEmpty() ||
                            sha256Of(target).equals(info.sha256, ignoreCase = true)
                        ) {
                            downloaded = target
                        } else {
                            sawChecksumFailure = true
                        }
                    DownloadOutcome.TRUNCATED -> sawChecksumFailure = true
                    DownloadOutcome.STORAGE -> sawStorageFailure = true
                    DownloadOutcome.NETWORK -> Unit
                }
                if (downloaded != null) break
            }

            val result = if (downloaded != null) {
                UpdateDownloadResult(downloaded, null, attempts, lastUrl)
            } else {
                val failure = when {
                    sawChecksumFailure -> UpdateFailure.CHECKSUM
                    sawStorageFailure -> UpdateFailure.STORAGE
                    else -> UpdateFailure.NETWORK
                }
                UpdateDownloadResult(null, failure, attempts, lastUrl)
            }
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

    /**
     * All addresses worth trying, best first, without duplicates.
     *
     * The static copy on the `apk` branch is deliberately included even when an older
     * update file does not mention it: every release publishes its APK under the same
     * predictable name there.
     */
    private fun candidateUrls(info: UpdateInfo): List<String> {
        val urls = LinkedHashSet<String>()
        fun add(url: String) {
            if (url.startsWith("https://")) urls.add(url)
        }

        add(info.downloadUrl)
        info.downloadUrls.forEach { add(it) }

        val name = "NavMusic-${info.versionName}.apk"
        add("https://raw.githubusercontent.com/$OWNER/$REPO/$APK_BRANCH/$name")
        add("https://cdn.jsdelivr.net/gh/$OWNER/$REPO@$APK_BRANCH/$name")

        // A mirror set for update.json is also tried as a base for the APK itself.
        val configured = Settings.updateMirrorUrl.trim().trimEnd('/')
        if (configured.isNotEmpty() && !configured.endsWith(FILE, ignoreCase = true)) {
            add("$configured/$name")
        }

        val releaseUrl = (listOf(info.downloadUrl) + info.downloadUrls)
            .firstOrNull { it.contains("/releases/download/") }
            ?.substringBefore("?")
        if (releaseUrl != null) {
            add("$releaseUrl?raw=1")
            add("https://ghfast.top/$releaseUrl")
            add("https://gh-proxy.com/$releaseUrl")
            add("https://hub.gitmirror.com/$releaseUrl")
        }
        return urls.toList()
    }

    private fun readText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches one address into [target].
     *
     * The GitHub assets API answers with a JSON description unless it is explicitly asked
     * for the binary, so that one address needs a different Accept header. A short file is
     * rejected right here: a proxy that cut the download off must not be counted as success.
     */
    private fun downloadToFile(
        url: String,
        expectedSize: Long,
        target: File,
        onProgress: (Int) -> Unit
    ): DownloadOutcome {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Accept",
                if (url.contains("api.github.com") && url.contains("/releases/assets/")) {
                    "application/octet-stream"
                } else {
                    "*/*"
                }
            )
            if (conn.responseCode !in 200..299) {
                DownloadOutcome.NETWORK
            } else {
                // contentLengthLong would be nicer, but it is API 24 and this still installs
                // on Android 5. The plain one only loses precision above 2 GB, which an APK
                // of ours never reaches.
                val total = if (expectedSize > 0L) expectedSize
                else conn.contentLength.toLong().coerceAtLeast(0L)
                var done = 0L
                var lastPercent = -1

                conn.inputStream.use { input ->
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
                when {
                    target.length() <= 0L -> DownloadOutcome.NETWORK
                    expectedSize > 0L && target.length() != expectedSize ->
                        DownloadOutcome.TRUNCATED
                    else -> DownloadOutcome.SUCCESS
                }
            }
        } catch (exception: SocketTimeoutException) {
            DownloadOutcome.NETWORK
        } catch (exception: UnknownHostException) {
            DownloadOutcome.NETWORK
        } catch (exception: IOException) {
            DownloadOutcome.STORAGE
        } catch (exception: Exception) {
            DownloadOutcome.NETWORK
        } finally {
            connection?.disconnect()
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
