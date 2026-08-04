package com.mg4.hardware.diag

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.mg4.hardware.AppLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file in `filesDir`, surfaced on the next launch.
 *
 * On a vehicle there is no crash reporter and usually no cable. Without this, a crash at
 * ignition leaves nothing behind: the process dies, the user sees the app "not working",
 * and there is no way to find out why after the fact.
 *
 * It lives here rather than in each app because every app in the suite crashes on the same
 * head unit for the same reasons, and the two copies that preceded this one had each fixed
 * a bug the other still carried — one chained the previous handler, the other counted bytes
 * instead of characters when truncating.
 *
 * The previous handler is always chained, so the system still gets to do its job.
 */
object CrashLogger {

    private const val TAG = "Crash"
    private const val FILE_NAME = "last_crash.txt"

    /**
     * Reports are truncated by keeping the HEAD, not the tail.
     *
     * The exception and its top frames are at the top of the report — that is the part
     * that identifies the bug. Cutting from the start would discard exactly what is
     * needed and keep the least interesting frames.
     */
    private const val MAX_BYTES = 64 * 1024

    /** How far down the `cause` chain to walk, and how many frames to keep per cause. */
    private const val MAX_CAUSE_DEPTH = 5
    private const val CAUSE_FRAMES = 20

    /**
     * Installs the handler. [appName] only titles the report — it is read by a human, and
     * the package name alone does not say which app of the suite produced it.
     */
    fun install(context: Context, appName: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e(TAG, "uncaught exception on thread '${thread.name}': $throwable")
            runCatching { write(appContext, appName, thread, throwable) }
            // Chain, never swallow: the platform still needs to terminate the process,
            // and swallowing here would leave the app in an undefined state instead.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun hasReport(context: Context): Boolean = file(context).exists()

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    @VisibleForTesting
    internal fun write(context: Context, appName: String, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        val report = buildString {
            appendLine("$appName crash report")
            appendLine("time    : $timestamp")
            appendLine("thread  : ${thread.name}")
            appendLine("version : ${version(context)}")
            appendLine("android : ${android.os.Build.VERSION.SDK_INT}")
            appendLine("device  : ${android.os.Build.DEVICE}")
            appendLine()
            appendLine("-- Stack trace --")
            appendLine(stackTrace)
            appendCauses(throwable)
            appendLine("-- Recent log (AppLogger) --")
            appendLine(AppLogger.dump())
        }

        writeAtomically(file(context), truncate(report))
    }

    /**
     * `printStackTrace` already prints the causes, but abbreviates them to the frames that
     * differ from the enclosing trace. On a reflection layer the interesting frame is
     * usually one of the common ones, so the chain is spelled out again in full.
     */
    private fun StringBuilder.appendCauses(throwable: Throwable) {
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            appendLine("-- Caused by --")
            appendLine(cause.toString())
            cause.stackTrace.take(CAUSE_FRAMES).forEach { appendLine("  at $it") }
            appendLine()
            cause = cause.cause
            depth++
        }
    }

    /**
     * Truncates to [MAX_BYTES] keeping the BEGINNING, and counts bytes rather than
     * characters: the log buffer is full of non-ASCII, which is two bytes in UTF-8, so a
     * character count writes up to twice the announced ceiling.
     */
    @VisibleForTesting
    internal fun truncate(content: String): ByteArray {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_BYTES) return bytes

        val marker = "\n... report truncated (${bytes.size} bytes) ...\n".toByteArray(Charsets.UTF_8)
        // Never cut mid-character: back up over UTF-8 continuation bytes (10xxxxxx).
        var end = MAX_BYTES - marker.size
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end) + marker
    }

    /**
     * Temp file then rename over the target.
     *
     * Never delete-then-write: that leaves a window with no file at all, and this runs
     * while the process is already dying — a second failure mid-write would destroy the
     * previous report without producing a new one.
     */
    private fun writeAtomically(target: File, content: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeBytes(content)
            if (!temp.renameTo(target)) {
                temp.delete()
                AppLogger.w(TAG, "could not rename crash report into place")
            }
        } catch (e: Exception) {
            temp.delete()
            AppLogger.w(TAG, "could not write crash report: ${e.message}")
        }
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
