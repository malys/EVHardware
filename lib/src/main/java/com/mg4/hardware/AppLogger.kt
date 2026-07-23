package com.mg4.hardware

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app log buffer — mirrors every Log.* call to an in-memory ring buffer
 * so the ConsoleFragment can display them without ADB.
 *
 * The buffer is a lock-guarded ArrayDeque: the previous CopyOnWriteArrayList copied all
 * 400 entries twice per log line, on the calling thread — i.e. while a profile was being
 * applied. Listeners are notified off the hot path.
 */
object AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val time: String,
        val tag: String,
        val level: Level,
        val msg: String
    )

    private const val MAX_ENTRIES = 400
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    /**
     * Total entries added since process start — eviction does not decrement it. Lets the UI
     * tell what is new without comparing sizes (size stops moving once the buffer is full).
     */
    @Volatile
    var totalCount: Long = 0L
        private set

    /** Instant snapshot of the buffer, oldest to newest entry. */
    val entries: List<Entry>
        get() = synchronized(lock) { buffer.toList() }

    /** Whole buffer as text, for sharing or attaching to a crash report. */
    fun dump(): String = entries.joinToString("\n") { "[${it.time}] ${it.level}/${it.tag}: ${it.msg}" }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Notification already scheduled: a burst of logs yields a single UI wake-up. */
    @Volatile
    private var notifyPending = false

    // ---- Public log methods (mirror android.util.Log) ----

    fun d(tag: String, msg: String) { add(tag, Level.DEBUG, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { add(tag, Level.INFO,  msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { add(tag, Level.WARN,  msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { add(tag, Level.ERROR, msg); Log.e(tag, msg) }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            totalCount = 0L
        }
        notifyListeners()
    }

    /**
     * Entries added since the caller last saw [sinceTotal], or null if the missing entries
     * were already evicted — in which case the caller must redraw everything.
     */
    fun entriesSince(sinceTotal: Long): List<Entry>? = synchronized(lock) {
        val missing = totalCount - sinceTotal
        when {
            missing < 0L          -> null          // buffer cleared in the meantime
            missing == 0L         -> emptyList()
            missing > buffer.size -> null          // too late: entries evicted
            else                  -> buffer.toList().takeLast(missing.toInt())
        }
    }

    // ---- Listener for live UI updates ----

    fun addListener(l: () -> Unit)    { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ---- Internal ----

    private fun add(tag: String, level: Level, msg: String) {
        val entry = Entry(sdf.format(Date()), tag, level, msg)
        synchronized(lock) {
            // while, not if: the cap holds even under concurrent adds.
            while (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            totalCount++
        }
        notifyListeners()
    }

    /**
     * Wakes the UI on the main thread, at most once per burst: the notification must not
     * run on the logging thread.
     */
    private fun notifyListeners() {
        if (listeners.isEmpty() || notifyPending) return
        notifyPending = true
        mainHandler.post {
            notifyPending = false
            listeners.forEach { runCatching { it.invoke() } }
        }
    }
}
