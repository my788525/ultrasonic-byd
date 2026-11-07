/*
 * ZeroPermissionCameraDetector.kt
 *
 * Fallback for head units where neither the accessibility service nor usage access can be
 * switched on. It needs no permission at all: it watches side effects that the car itself
 * writes when the 360 / reversing picture comes up.
 *
 *  1. System properties (`getprop`): BYD firmware writes vehicle-state properties. Any key
 *     whose name mentions the camera/gear/reverse and whose value changes is a hit.
 *  2. Settings.Global / Settings.System keys: the same idea for values the car writes into
 *     Android settings.
 *  3. Media volume dropping to zero: most head units duck or mute music in reverse.
 *  4. `dumpsys window windows`: tried once; on ROMs that allow an ordinary app to dump the
 *     window list, the com.byd.avc visible window is a direct hit. On ROMs that refuse it
 *     the probe is disabled for the rest of the session.
 *
 * Every observation, including changes that do not match a keyword, is kept for the
 * settings diagnostic. That is deliberate: if a particular firmware writes a property with
 * an unexpected name, the driver can read it from the settings screen and the rule can be
 * added without adb and without guessing.
 *
 * All work runs on a dedicated HandlerThread. Nothing here touches the main thread except
 * the single CameraSignalBus notification, so it cannot make the UI stutter.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

object ZeroPermissionCameraDetector {

    private const val POLL_INTERVAL_MS = 1_500L
    private const val DUMPSYS_EVERY_N = 8
    private const val COMMAND_TIMEOUT_MS = 1_500L

    /** Keywords in a property / setting name that plausibly describe the camera view. */
    private val keywords = arrayOf(
        "pano", "avc", "avm", "aroundview", "surround", "camera", "reverse",
        "reversing", "gear", "360", "park", "radar"
    )

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var baselineProperties: Map<String, String>? = null
    @Volatile private var baselineSettings: Map<String, String>? = null
    @Volatile private var lastVolume = -1
    @Volatile private var dumpsysDisabled = false
    private var ticks = 0

    /** The most recent human-readable observation, shown in settings. */
    @Volatile var lastObservation: String = "not started"
        private set

    /** Whether the low-permission scanner is actually running. */
    @Volatile var running = false
        private set

    /** Whether the head unit lets a normal app read its window list. */
    @Volatile var dumpsysAvailable: Boolean? = null
        private set

    fun start(context: Context) {
        if (running) return
        val appContext = context.applicationContext
        running = true
        baselineProperties = null
        baselineSettings = null
        lastVolume = -1
        dumpsysDisabled = false
        dumpsysAvailable = null
        ticks = 0
        lastObservation = "scanning"
        val t = HandlerThread("camera-fallback-scan").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    scan(appContext)
                } catch (all: Throwable) {
                    Timber.w(all, "Zero-permission camera scan failed")
                }
                ticks++
                handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
        Timber.i("Zero-permission camera detector started")
    }

    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        handler = null
        thread = null
        lastObservation = "stopped"
        Timber.i("Zero-permission camera detector stopped")
    }

    private fun scan(context: Context) {
        scanProperties()
        scanSettings(context)
        scanVolume(context)
        if (!dumpsysDisabled && ticks % DUMPSYS_EVERY_N == 0) scanWindowDump()
    }

    // ------------------------------------------------------------------ properties

    private fun scanProperties() {
        val current = readGetprop()
        val baseline = baselineProperties
        if (baseline == null) {
            baselineProperties = current
            lastObservation = "baseline: ${current.size} properties"
            return
        }
        val changes = diff(baseline, current)
        baselineProperties = current
        for ((key, from, to) in changes) {
            val label = "prop $key: $from -> $to"
            lastObservation = label
            if (matches(key)) {
                CameraSignalBus.noteFallbackSignal(label)
            }
        }
    }

    private fun readGetprop(): Map<String, String> {
        val result = HashMap<String, String>()
        runCommand(arrayOf("getprop"))?.lines()?.forEach { line ->
            val m = Regex("^\\[(.*?)]\\s*:\\s*\\[(.*)]$").find(line.trim())
            if (m != null) result[m.groupValues[1]] = m.groupValues[2]
        }
        return result
    }

    // ------------------------------------------------------------------ settings

    private fun scanSettings(context: Context) {
        val current = HashMap<String, String>()
        readSettingsTable(context, android.provider.Settings.Global.CONTENT_URI, current)
        readSettingsTable(context, android.provider.Settings.System.CONTENT_URI, current)
        val baseline = baselineSettings
        if (baseline == null) {
            baselineSettings = current
            return
        }
        val changes = diff(baseline, current)
        baselineSettings = current
        for ((key, from, to) in changes) {
            val label = "setting $key: $from -> $to"
            lastObservation = label
            if (matches(key)) {
                CameraSignalBus.noteFallbackSignal(label)
            }
        }
    }

    private fun readSettingsTable(context: Context, uri: Uri, into: MutableMap<String, String>) {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return
            cursor.use { c ->
                val nameIdx = c.getColumnIndex("name")
                val valueIdx = c.getColumnIndex("value")
                if (nameIdx < 0) return
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val value = if (valueIdx >= 0) c.getString(valueIdx).orEmpty() else ""
                    into[uri.lastPathSegment + "/" + name] = value
                }
            }
        } catch (all: Throwable) {
            // Some head units hide parts of the settings provider; that is not fatal.
        }
    }

    // ------------------------------------------------------------------ volume

    private fun scanVolume(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (lastVolume > 0 && volume == 0) {
                val label = "media volume $lastVolume -> 0 (head unit muted music)"
                lastObservation = label
                CameraSignalBus.noteFallbackSignal(label)
            }
            lastVolume = volume
        } catch (all: Throwable) {
            // Ignore: volume is only a supporting hint.
        }
    }

    // ------------------------------------------------------------------ window dump

    private fun scanWindowDump() {
        val dump = runCommand(arrayOf("dumpsys", "window", "windows")) ?: return
        if (dump.isBlank() || dump.contains("Permission Denial", ignoreCase = true) ||
            dump.contains("permission denial", ignoreCase = true)
        ) {
            dumpsysDisabled = true
            dumpsysAvailable = false
            lastObservation = "dumpsys window: refused (normal on most head units)"
            return
        }
        dumpsysAvailable = true
        // A window section, not the whole dump: a hidden camera window must not borrow the
        // visibility flag of an unrelated window that happens to follow it in the output.
        val visibleCameraWindow = dump
            .split(Regex("(?=Window #\\d+)"))
            .any { section ->
                section.contains("com.byd.avc") && section.contains("mViewVisibility=0x0")
            }
        if (visibleCameraWindow) {
            val label = "dumpsys: visible com.byd.avc camera window"
            lastObservation = label
            CameraSignalBus.noteFallbackSignal(label)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun matches(key: String): Boolean {
        val lower = key.lowercase(Locale.ROOT)
        return keywords.any { lower.contains(it) }
    }

    private fun diff(
        before: Map<String, String>,
        after: Map<String, String>
    ): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        for ((key, value) in after) {
            val old = before[key] ?: "<absent>"
            if (old != value) out.add(Triple(key, old, value))
        }
        return out
    }

    /** Runs a short system command with a hard timeout, never on the main thread. */
    private fun runCommand(command: Array<String>): String? {
        var process: Process? = null
        return try {
            // Merge stderr into stdout: a refused command often writes its denial to stderr,
            // and reading two streams one after the other can deadlock on a full pipe.
            process = ProcessBuilder(command.asList()).redirectErrorStream(true).start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return null
            }
            text
        } catch (all: Throwable) {
            null
        } finally {
            process?.destroy()
        }
    }
}
