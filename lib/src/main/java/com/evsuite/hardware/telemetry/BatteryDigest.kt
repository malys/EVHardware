package com.evsuite.hardware.telemetry

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * One day of the battery analyses, kept so that tomorrow can be compared with today (CP-087).
 *
 * Nothing here is measured: every field is copied from [StateOfHealthEstimator],
 * [CalibrationDrift] and [BatteryExposure] as they stood that day. A null is an analysis that
 * refused, never a zero.
 */
data class BatteryDigest(
    /** The local calendar day, `yyyy-MM-dd`. One record per day; the last write of a day wins. */
    val day: String,
    val healthPercent: Double?,
    /** Half-width of the health band, in points. Null where the estimate refused. */
    val healthBandPercent: Double?,
    val healthWindows: Int,
    val calibration: CalibrationVerdict?,
    val daysSinceFullCharge: Double?,
    val hoursAboveHighSoc: Double?,
    val equivalentFullCycles: Double?,
    val chargeCount: Int,
) {
    companion object {
        fun of(
            day: String,
            health: StateOfHealthResult,
            drift: CalibrationDriftReport?,
            exposure: BatteryExposureReport?,
            charge: ChargeEnergyReport,
        ): BatteryDigest {
            val estimate = (health as? StateOfHealthResult.Ready)?.estimate
            return BatteryDigest(
                day = day,
                healthPercent = estimate?.stateOfHealthPercent?.value,
                healthBandPercent = estimate?.stateOfHealthPercent?.uncertainty,
                healthWindows = estimate?.windowCount ?: 0,
                calibration = drift?.verdict,
                daysSinceFullCharge = drift?.daysSinceFullCharge,
                hoursAboveHighSoc = exposure?.hoursAboveHighSoc,
                equivalentFullCycles = exposure?.equivalentFullCycles,
                chargeCount = charge.charges.size,
            )
        }

        /**
         * What moved between two days by more than its own uncertainty, and only that.
         *
         * A health figure that wanders inside its band has not changed: saying so every morning
         * is the notification a driver turns off in a week.
         */
        fun changes(previous: BatteryDigest?, current: BatteryDigest): List<DigestChange> {
            if (previous == null) return emptyList()
            return listOfNotNull(
                healthMoved(previous, current),
                bandNarrowed(previous, current),
                calibrationChanged(previous, current),
            )
        }

        private fun healthMoved(previous: BatteryDigest, current: BatteryDigest): DigestChange? {
            val from = previous.healthPercent ?: return null
            val to = current.healthPercent ?: return null
            val band = max(previous.healthBandPercent ?: 0.0, current.healthBandPercent ?: 0.0)
            if (abs(to - from) <= band) return null
            return DigestChange.HealthMoved(from, to, current.healthBandPercent ?: band)
        }

        private fun bandNarrowed(previous: BatteryDigest, current: BatteryDigest): DigestChange? {
            val from = previous.healthBandPercent
            val to = current.healthBandPercent ?: return null
            // A first estimate is a narrowing from nothing, and the driver has never seen it.
            if (from == null) {
                val health = current.healthPercent ?: return null
                return DigestChange.HealthBandNarrowed(null, to, health)
            }
            if (from - to < MIN_BAND_NARROWING_PERCENT) return null
            return DigestChange.HealthBandNarrowed(from, to, current.healthPercent ?: return null)
        }

        private fun calibrationChanged(previous: BatteryDigest, current: BatteryDigest): DigestChange? {
            val to = current.calibration ?: return null
            if (previous.calibration == to) return null
            return DigestChange.CalibrationChanged(previous.calibration, to, current.daysSinceFullCharge)
        }

        /** A point of band is what one more watched window buys; less is rounding. */
        const val MIN_BAND_NARROWING_PERCENT = 1.0
    }
}

sealed interface DigestChange {
    data class HealthMoved(val fromPercent: Double, val toPercent: Double, val bandPercent: Double) : DigestChange
    data class HealthBandNarrowed(
        val fromBandPercent: Double?,
        val toBandPercent: Double,
        val healthPercent: Double,
    ) : DigestChange
    data class CalibrationChanged(
        val from: CalibrationVerdict?,
        val to: CalibrationVerdict,
        val daysSinceFullCharge: Double?,
    ) : DigestChange
}

/** A year of days, newest last. Same atomic write and quarantine as [BatteryLedgerStore]. */
class BatteryDigestStore(
    private val target: File,
    private val maxDays: Int = MAX_DAYS,
    private val gson: Gson = Gson(),
) {
    fun read(): List<BatteryDigest> {
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        val envelope = runCatching { gson.fromJson(text, Envelope::class.java) }.getOrNull()
        if (envelope == null || envelope.schemaVersion != SCHEMA_VERSION) {
            target.renameTo(File(target.parentFile, "${target.name}.quarantine.${System.currentTimeMillis()}"))
            return emptyList()
        }
        @Suppress("SENSELESS_COMPARISON")
        return envelope.days.orEmpty().filter { it != null && it.day != null }.sortedBy { it.day }
    }

    /** Replaces the record of the same day, or adds it; returns the day before it, if any. */
    fun upsert(digest: BatteryDigest): BatteryDigest? {
        val others = read().filter { it.day != digest.day }
        val previous = others.lastOrNull { it.day < digest.day }
        write((others + digest).sortedBy { it.day }.takeLast(maxDays.coerceAtLeast(1)))
        return previous
    }

    private fun write(days: List<BatteryDigest>): Boolean {
        val bytes = gson.toJson(Envelope(SCHEMA_VERSION, days)).toByteArray(Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private data class Envelope(val schemaVersion: Int = 0, val days: List<BatteryDigest>? = null)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_DAYS = 365
        const val FILE_NAME = "battery-digest.json"
    }
}
