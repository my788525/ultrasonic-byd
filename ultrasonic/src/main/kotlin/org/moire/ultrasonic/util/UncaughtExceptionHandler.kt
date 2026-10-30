package org.moire.ultrasonic.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.moire.ultrasonic.util.Util.safeClose
import timber.log.Timber

/**
 * Logs the stack trace of uncaught exceptions to a file on the SD card.
 *
 * The head unit has no logcat that anybody can read after a crash, so the file is the only
 * way to find out what happened. Two things matter here:
 *
 * - The handler has to be installed in the application, not in an activity. On a car the
 *   music plays in the background, the activity is often gone, and a crash in the playback
 *   service would leave no trace at all.
 * - Every crash gets its own file. A single file is overwritten by the next crash, which is
 *   exactly the wrong behaviour when the crash happens once per drive.
 */
class UncaughtExceptionHandler(
    private val context: Context
) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        var file: File? = null
        var printWriter: PrintWriter? = null

        try {
            file = createStacktraceFile()
            printWriter = PrintWriter(file)
            val logMessage = String.format(
                "Android API level: %s\nUltrasonic version name: %s\n" +
                    "Ultrasonic version code: %s\nThread: %s\n\n",
                Build.VERSION.SDK_INT, Util.getVersionName(context), Util.getVersionCode(context),
                thread.name
            )
            printWriter.println(logMessage)
            throwable.printStackTrace(printWriter)
            Timber.e(throwable, "Uncaught Exception! %s", logMessage)
            Timber.i("Stack trace written to %s", file)
        } catch (all: Throwable) {
            Timber.e(all, "Failed to write stack trace to %s", file)
        } finally {
            printWriter.safeClose()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val STACKTRACE_PREFIX = "ultrasonic-stacktrace-"
        private const val MAX_TRACE_FILES = 5

        /**
         * A file that carries the time of the crash, so that the crashes of several drives
         * can be told apart.
         */
        private fun createStacktraceFile(): File {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                .format(Date())
            val file = File(FileUtil.ultrasonicDirectory, "$STACKTRACE_PREFIX$stamp.txt")
            file.parentFile?.mkdirs()
            pruneOldTraces()
            return file
        }

        /**
         * Keeps the number of trace files small, so that the directory does not fill up.
         */
        private fun pruneOldTraces() {
            val directory = FileUtil.ultrasonicDirectory
            val traces = directory.listFiles { _, name ->
                name.startsWith(STACKTRACE_PREFIX) && name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() } ?: return

            traces.drop(MAX_TRACE_FILES).forEach { old ->
                old.delete()
            }
        }
    }
}
