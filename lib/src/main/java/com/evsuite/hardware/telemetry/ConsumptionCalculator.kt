package com.evsuite.hardware.telemetry

import kotlin.math.exp

/** Instantaneous and short-window consumption derived only from measured power and speed. */
class ConsumptionCalculator(
    private val rollingWindowMs: Long = ROLLING_WINDOW_MS,
    private val maxWindowSamples: Int = MAX_WINDOW_SAMPLES,
) {
    data class Reading(
        /** Unfiltered arithmetic, exposed for diagnostics; trip integration never consumes it. */
        val rawInstantaneous: Provenanced<Double>,
        /** Exponential smoothing used only for the glanceable dashboard number. */
        val smoothedInstantaneous: Provenanced<Double>,
        /** Consumed energy divided by distance over the bounded recent window. */
        val rollingAverage: Provenanced<Double>,
    )

    private data class Sample(
        val atMs: Long,
        val speedKmh: Double?,
        val batteryPowerKw: Double?,
    )

    private val window = ArrayDeque<Sample>()
    private var smoothedValue: Double? = null
    private var smoothedAtMs: Long? = null

    init {
        require(rollingWindowMs > 0L) { "rolling window must be positive" }
        require(maxWindowSamples >= 2) { "rolling window needs at least two samples" }
    }

    fun add(
        snapshot: EnergySnapshot,
        missingReason: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
    ): Reading = add(
        timestampMs = snapshot.timestampMs,
        batteryPowerKw = snapshot.batteryPowerKw,
        speedKmh = snapshot.speedKmh,
        missingReason = missingReason,
    )

    fun add(
        timestampMs: Long,
        batteryPowerKw: Float?,
        speedKmh: Float?,
        missingReason: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
    ): Reading {
        if (window.lastOrNull()?.atMs?.let { timestampMs <= it } == true) reset()

        val raw = instantaneous(batteryPowerKw, speedKmh, missingReason)
        val smoothed = smooth(timestampMs, raw)
        window.addLast(
            Sample(
                atMs = timestampMs,
                speedKmh = speedKmh?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 },
                batteryPowerKw = batteryPowerKw?.toDouble()?.takeIf(Double::isFinite),
            )
        )
        trim(timestampMs)
        return Reading(raw, smoothed, rollingAverage(timestampMs))
    }

    fun reset() {
        window.clear()
        smoothedValue = null
        smoothedAtMs = null
    }

    private fun smooth(timestampMs: Long, raw: Provenanced<Double>): Provenanced<Double> {
        val current = raw.value ?: run {
            smoothedValue = null
            smoothedAtMs = null
            return Provenanced.unavailable(checkNotNull(raw.reason))
        }
        val previous = smoothedValue
        val previousAt = smoothedAtMs
        val deltaMs = previousAt?.let { timestampMs - it }
        val signChanged = previous != null && previous * current < 0.0
        val value = if (
            previous == null || deltaMs == null || deltaMs <= 0L ||
            deltaMs > MAX_SAMPLE_GAP_MS || signChanged
        ) {
            current
        } else {
            val alpha = 1.0 - exp(-deltaMs.toDouble() / SMOOTHING_TIME_CONSTANT_MS)
            previous + alpha * (current - previous)
        }
        smoothedValue = value
        smoothedAtMs = timestampMs
        return Provenanced.derived(value)
    }

    private fun trim(nowMs: Long) {
        val cutoff = nowMs - rollingWindowMs
        // Keep one predecessor so an interval crossing the window edge can be clipped.
        while (window.size > 1 && window.elementAt(1).atMs <= cutoff) window.removeFirst()
        while (window.size > maxWindowSamples) window.removeFirst()
    }

    private fun rollingAverage(nowMs: Long): Provenanced<Double> {
        if (window.size < 2) return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        val cutoff = nowMs - rollingWindowMs
        var distanceKm = 0.0
        var consumedKwh = 0.0
        var hasSpeedInterval = false
        var hasPowerInterval = false
        val samples = window.toList()
        for (index in 1 until samples.size) {
            val first = samples[index - 1]
            val second = samples[index]
            val fullGapMs = second.atMs - first.atMs
            if (fullGapMs <= 0L || fullGapMs > MAX_SAMPLE_GAP_MS) continue
            val fromMs = maxOf(first.atMs, cutoff)
            val gapMs = second.atMs - fromMs
            if (gapMs <= 0L) continue
            val fraction = (fromMs - first.atMs).toDouble() / fullGapMs.toDouble()
            val hours = gapMs / 3_600_000.0

            if (first.speedKmh != null && second.speedKmh != null) {
                val speedAtEdge = interpolate(first.speedKmh, second.speedKmh, fraction)
                distanceKm += ((speedAtEdge + second.speedKmh) / 2.0) * hours
                hasSpeedInterval = true
            }
            if (first.batteryPowerKw != null && second.batteryPowerKw != null) {
                val powerAtEdge = interpolate(
                    first.batteryPowerKw,
                    second.batteryPowerKw,
                    fraction,
                )
                val intervalKwh = ((powerAtEdge + second.batteryPowerKw) / 2.0) * hours
                if (intervalKwh >= 0.0) consumedKwh += intervalKwh
                hasPowerInterval = true
            }
        }
        val average = if (
            hasSpeedInterval && hasPowerInterval && distanceKm >= MIN_AVERAGE_DISTANCE_KM
        ) {
            consumedKwh * 100.0 / distanceKm
        } else null
        return Provenanced.derived(average)
    }

    private fun interpolate(first: Double, second: Double, fraction: Double): Double =
        first + (second - first) * fraction

    companion object {
        /** Below 5 km/h, power / speed diverges and stationary accessory use has no distance. */
        const val LOW_SPEED_FLOOR_KMH = 5.0

        /** Five-second EMA time constant: stable at a glance without hiding sustained changes. */
        const val SMOOTHING_TIME_CONSTANT_MS = 5_000.0

        /** Two recent minutes describe current driving without growing with trip duration. */
        const val ROLLING_WINDOW_MS = 120_000L

        /** Extra hard bound if a caller samples faster than the dashboard's expected 1 Hz. */
        const val MAX_WINDOW_SAMPLES = 256

        /** Matches the trip accumulator: longer gaps represent motion the app did not observe. */
        const val MAX_SAMPLE_GAP_MS = 5_000L

        /** Avoids presenting a ratio dominated by rounding after only a few metres. */
        const val MIN_AVERAGE_DISTANCE_KM = 0.1

        fun instantaneous(
            batteryPowerKw: Float?,
            speedKmh: Float?,
            missingReason: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
        ): Provenanced<Double> {
            val power = batteryPowerKw?.toDouble()?.takeIf(Double::isFinite)
                ?: return Provenanced.unavailable(missingReason)
            val speed = speedKmh?.toDouble()?.takeIf(Double::isFinite)
                ?: return Provenanced.unavailable(missingReason)
            if (speed < LOW_SPEED_FLOOR_KMH) {
                return Provenanced.unavailable(UnavailableReason.SPEED_TOO_LOW)
            }
            return Provenanced.derived(power * 100.0 / speed)
        }
    }
}
