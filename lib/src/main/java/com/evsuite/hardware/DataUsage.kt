package com.evsuite.hardware

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.TrafficStats
import java.util.Calendar

/**
 * How much data the head unit's connection has carried.
 *
 * These are Android's own counters — the kernel counts per interface and `NetworkStatsService`
 * keeps the history — so they are the same numbers the Settings screen shows, not a
 * measurement of our own.
 *
 * The catch is the interface. The head unit's modem presents itself as **Ethernet**, and the
 * public `querySummaryForDevice(int, …)` only knows how to build MOBILE and WIFI templates: it
 * answers zero here, which reads exactly like a car that used no data. The hidden overload
 * that takes a `NetworkTemplate` does answer, so that is the one used, by reflection, with
 * `TrafficStats` since boot as the fallback.
 */
object DataUsage {

    private const val TAG = "EV_DATA"

    /** Bytes in and out over a window, with the source that answered — for the diagnostic. */
    data class Usage(val rxBytes: Long, val txBytes: Long, val source: String) {
        val totalBytes: Long get() = rxBytes + txBytes
    }

    private fun Calendar.atMidnight(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    fun startOfDay(): Long = Calendar.getInstance().atMidnight().timeInMillis

    /** The first of the current month at midnight — the bound the Settings screen defaults to. */
    fun startOfMonth(): Long = Calendar.getInstance().atMidnight()
        .apply { set(Calendar.DAY_OF_MONTH, 1) }
        .timeInMillis

    /**
     * Ethernet traffic over the window, or null when no route answered.
     *
     * Null rather than zero, deliberately: "used nothing" and "could not read" must not look
     * alike, or a missing permission passes for a quiet month.
     */
    fun ethernet(context: Context, start: Long, end: Long): Usage? {
        val manager = try {
            context.applicationContext.getSystemService(Context.NETWORK_STATS_SERVICE)
                as? NetworkStatsManager
        } catch (_: Exception) {
            null
        } ?: return null

        val template = ethernetTemplate() ?: return null
        return try {
            val method = manager.javaClass.getMethod(
                "querySummaryForDevice", template.javaClass,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            val bucket = method.invoke(manager, template, start, end) as? NetworkStats.Bucket
            if (bucket == null) {
                AppLogger.w(TAG, "querySummaryForDevice(template) → null bucket")
                null
            } else {
                Usage(bucket.rxBytes, bucket.txBytes, "NetworkStatsManager/Ethernet")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "hidden route unavailable: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** Every interface, but only since the last boot — the fallback that always answers. */
    fun sinceBoot(): Usage = Usage(
        TrafficStats.getTotalRxBytes().coerceAtLeast(0),
        TrafficStats.getTotalTxBytes().coerceAtLeast(0),
        "TrafficStats/since boot"
    )

    /** Megabytes over the window, or null when nothing could be read. */
    fun megabytesSince(context: Context, start: Long): Int? =
        ethernet(context, start, System.currentTimeMillis())
            ?.let { (it.totalBytes / 1_048_576L).toInt() }

    private fun ethernetTemplate(): Any? = try {
        Class.forName("android.net.NetworkTemplate")
            .getMethod("buildTemplateEthernet")
            .invoke(null)
    } catch (e: Exception) {
        AppLogger.w(TAG, "buildTemplateEthernet unavailable: ${e.javaClass.simpleName}")
        null
    }
}
