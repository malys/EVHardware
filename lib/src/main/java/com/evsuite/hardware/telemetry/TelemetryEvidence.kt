package com.evsuite.hardware.telemetry

import com.google.gson.Gson

/** Whether a signal is a quantity or a flag; a flag has no meaningful mean. */
enum class SignalKind { NUMERIC, BOOLEAN }

/**
 * What one signal did during a capture, in the terms a decision needs.
 *
 * Every energy model downstream divides by a number whose unit, sign and update rate are
 * currently a hypothesis: `EVHardware.getBatteryPowerKw` divides the raw property by
 * -1,000,000 because that is what the value looked like, not because a car confirmed it.
 * This is the record that turns the hypothesis into evidence — or refutes it.
 *
 * A signal the vehicle never answered has zero samples and no statistics at all. It does not
 * have a minimum of zero, or a mean of zero, because it has nothing.
 */
data class SignalEvidence(
    val signal: String,
    val kind: SignalKind,
    /** Reads that produced a value. */
    val samples: Int,
    /** Reads that produced nothing. */
    val nulls: Int,
    val min: Double? = null,
    val max: Double? = null,
    val mean: Double? = null,
    /** Sign split, numeric signals only: how a power reading proves its own convention. */
    val positive: Int? = null,
    val negative: Int? = null,
    val zero: Int? = null,
    val firstSeenMs: Long? = null,
    val lastSeenMs: Long? = null,
    /** How many times the value actually changed — the basis for the update period. */
    val changes: Int = 0,
    val updatePeriodMinMs: Long? = null,
    val updatePeriodMedianMs: Long? = null,
    val updatePeriodMaxMs: Long? = null,
) {
    val available: Boolean get() = samples > 0
}

/**
 * One capture: what was recorded, on which firmware, over what window.
 *
 * [schemaVersion] is written into the file because these captures outlive the build that
 * produced them — a capture from a car six months ago is still the only evidence anyone has
 * for that generation, and a reader has to know how to read it.
 */
data class EvidenceCapture(
    val schemaVersion: Int = SCHEMA_VERSION,
    val firmware: String,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val snapshots: Int,
    val signals: List<SignalEvidence>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Reads every signal of every snapshot and remembers what it saw.
 *
 * Thread-confined by construction: a sampler owns one recorder and no one else touches it,
 * which is why nothing here is synchronized. Per sample it does arithmetic on primitives and
 * allocates nothing, because it runs beside a 1 Hz read on a head unit that has other work.
 */
class TelemetryEvidenceRecorder {

    private val accumulators = LinkedHashMap<String, Accumulator>()
    private var snapshots = 0
    private var firstSnapshotMs: Long? = null
    private var lastSnapshotMs: Long? = null
    private var firmware: String? = null

    fun record(snapshot: EnergySnapshot) {
        snapshots++
        if (firstSnapshotMs == null) firstSnapshotMs = snapshot.timestampMs
        lastSnapshotMs = snapshot.timestampMs
        firmware = snapshot.firmware.name
        val at = snapshot.timestampMs

        record(SOC_PERCENT, snapshot.socPercent, at)
        record(RANGE_KM, snapshot.rangeKm, at)
        record(SPEED_KMH, snapshot.speedKmh, at)
        record(BATTERY_POWER_KW, snapshot.batteryPowerKw, at)
        record(BATTERY_TEMP_C, snapshot.batteryTempCelsius, at)
        record(BATTERY_ENERGY_KWH, snapshot.batteryEnergyKwh, at)
        record(BATTERY_CAPACITY_KWH, snapshot.batteryCapacityKwh, at)
        record(ODOMETER_KM, snapshot.odometerKm, at)
        record(OUTSIDE_TEMP_C, snapshot.outsideTempCelsius, at)
        record(CABIN_TEMP_C, snapshot.cabinTempCelsius, at)
        record(CHARGING_STATUS, snapshot.chargingStatus?.toDouble(), at)
        record(CHARGE_PORT_CONNECTED, snapshot.chargePortConnected, at)
        record(PARKED, snapshot.parked, at)

        val climate = snapshot.climate
        record(CLIMATE_POWER_ON, climate.powerOn, at)
        record(CLIMATE_AC_ON, climate.acOn, at)
        record(CLIMATE_AUTO_ON, climate.autoOn, at)
        record(CLIMATE_ECON_ON, climate.econOn, at)
        record(CLIMATE_RECIRCULATION_ON, climate.recirculationOn, at)
        record(CLIMATE_FAN_LEVEL, climate.fanLevel?.toDouble(), at)
        record(CLIMATE_FAN_LEVEL_MAX, climate.fanLevelMax?.toDouble(), at)
        record(CLIMATE_DRIVER_TARGET_C, climate.driverTargetCelsius, at)
        record(CLIMATE_PASSENGER_TARGET_C, climate.passengerTargetCelsius, at)
    }

    /** For a candidate property being probed alongside the snapshot (see the CP-004 findings). */
    fun record(signal: String, value: Double?, atMs: Long) =
        accumulator(signal, SignalKind.NUMERIC).add(value, atMs)

    fun record(signal: String, value: Float?, atMs: Long) =
        record(signal, value?.toDouble(), atMs)

    fun record(signal: String, value: Boolean?, atMs: Long) =
        accumulator(signal, SignalKind.BOOLEAN).add(value?.let { if (it) 1.0 else 0.0 }, atMs)

    fun evidence(): List<SignalEvidence> = accumulators.map { (name, it) -> it.evidence(name) }

    fun capture(): EvidenceCapture = EvidenceCapture(
        firmware = firmware ?: "UNKNOWN",
        startedAtMs = firstSnapshotMs,
        endedAtMs = lastSnapshotMs,
        snapshots = snapshots,
        signals = evidence(),
    )

    private fun accumulator(signal: String, kind: SignalKind): Accumulator =
        accumulators.getOrPut(signal) { Accumulator(kind) }

    private class Accumulator(private val kind: SignalKind) {
        private var samples = 0
        private var nulls = 0
        private var min = Double.MAX_VALUE
        private var max = -Double.MAX_VALUE
        private var sum = 0.0
        private var positive = 0
        private var negative = 0
        private var zero = 0
        private var firstSeenMs: Long? = null
        private var lastSeenMs: Long? = null
        private var lastValue: Double? = null
        private var lastChangeMs: Long? = null
        private var changes = 0
        /**
         * Intervals between value *changes*, not between reads. A 1 Hz sampler over a signal
         * the vehicle republishes every five seconds must report five seconds, or every model
         * built on it will assume a resolution the car does not have.
         */
        private val intervals = LongArray(MAX_INTERVALS)
        private var intervalCount = 0

        fun add(value: Double?, atMs: Long) {
            if (value == null) {
                nulls++
                return
            }
            samples++
            sum += value
            if (value < min) min = value
            if (value > max) max = value
            when {
                value > 0.0 -> positive++
                value < 0.0 -> negative++
                else -> zero++
            }
            if (firstSeenMs == null) firstSeenMs = atMs
            lastSeenMs = atMs

            val previous = lastValue
            if (previous == null) {
                lastChangeMs = atMs
            } else if (previous != value) {
                changes++
                lastChangeMs?.let { since ->
                    val interval = atMs - since
                    if (interval > 0L && intervalCount < MAX_INTERVALS) {
                        intervals[intervalCount++] = interval
                    }
                }
                lastChangeMs = atMs
            }
            lastValue = value
        }

        fun evidence(signal: String): SignalEvidence {
            if (samples == 0) {
                return SignalEvidence(signal, kind, samples = 0, nulls = nulls)
            }
            val sorted = intervals.copyOf(intervalCount).sortedArray()
            return SignalEvidence(
                signal = signal,
                kind = kind,
                samples = samples,
                nulls = nulls,
                min = min,
                max = max,
                mean = sum / samples,
                positive = positive.takeIf { kind == SignalKind.NUMERIC },
                negative = negative.takeIf { kind == SignalKind.NUMERIC },
                zero = zero.takeIf { kind == SignalKind.NUMERIC },
                firstSeenMs = firstSeenMs,
                lastSeenMs = lastSeenMs,
                changes = changes,
                updatePeriodMinMs = sorted.firstOrNull(),
                updatePeriodMedianMs = sorted.medianOrNull(),
                updatePeriodMaxMs = sorted.lastOrNull(),
            )
        }

        private fun LongArray.medianOrNull(): Long? =
            if (isEmpty()) null else this[size / 2]

        private companion object {
            /** A long capture proves the cadence early; the tail adds bytes, not knowledge. */
            const val MAX_INTERVALS = 1024
        }
    }

    companion object {
        const val SOC_PERCENT = "socPercent"
        const val RANGE_KM = "rangeKm"
        const val SPEED_KMH = "speedKmh"
        const val BATTERY_POWER_KW = "batteryPowerKw"
        const val BATTERY_TEMP_C = "batteryTempCelsius"
        const val BATTERY_ENERGY_KWH = "batteryEnergyKwh"
        const val BATTERY_CAPACITY_KWH = "batteryCapacityKwh"
        const val ODOMETER_KM = "odometerKm"
        const val OUTSIDE_TEMP_C = "outsideTempCelsius"
        const val CABIN_TEMP_C = "cabinTempCelsius"
        const val CHARGING_STATUS = "chargingStatus"
        const val CHARGE_PORT_CONNECTED = "chargePortConnected"
        const val PARKED = "parked"
        const val CLIMATE_POWER_ON = "climate.powerOn"
        const val CLIMATE_AC_ON = "climate.acOn"
        const val CLIMATE_AUTO_ON = "climate.autoOn"
        const val CLIMATE_ECON_ON = "climate.econOn"
        const val CLIMATE_RECIRCULATION_ON = "climate.recirculationOn"
        const val CLIMATE_FAN_LEVEL = "climate.fanLevel"
        const val CLIMATE_FAN_LEVEL_MAX = "climate.fanLevelMax"
        const val CLIMATE_DRIVER_TARGET_C = "climate.driverTargetCelsius"
        const val CLIMATE_PASSENGER_TARGET_C = "climate.passengerTargetCelsius"
    }
}

/**
 * The capture, written for two readers: a future build, and a person.
 *
 * The JSON is what a later session parses; the Markdown is what gets pasted into the
 * validation analysis, where the conclusion is written by someone reading a table.
 */
object TelemetryEvidenceFormat {

    private val gson = Gson()

    fun toJson(capture: EvidenceCapture): String = gson.toJson(capture)

    fun fromJson(text: String): EvidenceCapture? =
        runCatching { gson.fromJson(text, EvidenceCapture::class.java) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == EvidenceCapture.SCHEMA_VERSION }

    fun toMarkdown(capture: EvidenceCapture): String = buildString {
        appendLine("# Telemetry evidence — ${capture.firmware}")
        appendLine()
        appendLine("- snapshots: ${capture.snapshots}")
        appendLine("- window: ${capture.startedAtMs ?: "—"} → ${capture.endedAtMs ?: "—"}")
        appendLine()
        appendLine("| signal | samples | nulls | min | max | mean | +/-/0 | period med (ms) |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: |")
        capture.signals.forEach { evidence ->
            val split = if (evidence.kind == SignalKind.NUMERIC) {
                "${evidence.positive ?: 0}/${evidence.negative ?: 0}/${evidence.zero ?: 0}"
            } else {
                "—"
            }
            appendLine(
                "| `${evidence.signal}` | ${evidence.samples} | ${evidence.nulls} | " +
                    "${evidence.min.orDash()} | ${evidence.max.orDash()} | " +
                    "${evidence.mean.orDash()} | $split | " +
                    "${evidence.updatePeriodMedianMs ?: "—"} |"
            )
        }
    }

    private fun Double?.orDash(): String =
        this?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "—"
}
