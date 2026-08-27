package com.evsuite.hardware.telemetry

/**
 * Integrates charge power into energy received during the current charging session.
 *
 * The meter is intentionally stateful and not thread-safe: one sampling owner must call
 * [sample] in timestamp order. Missing samples keep an open session alive without inventing
 * energy, and long gaps are skipped.
 */
class ChargingSessionMeter @JvmOverloads constructor(
    private val maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS
) {
    private var sessionOpen = false
    private var lastSampleMs = 0L
    private var lastRateKw: Float? = null
    private var chargedKwh = 0.0

    /**
     * @param charging true while energy is flowing, false when the session ended, null when
     * the state is temporarily unreadable
     * @param chargeRateKw positive power entering the pack; negative noise is clamped to zero
     * @return session energy in kWh, or null when no charging session is open
     */
    fun sample(nowMs: Long, charging: Boolean?, chargeRateKw: Float?): Double? {
        if (charging == false) {
            sessionOpen = false
            lastRateKw = null
            return null
        }
        if (charging == null) {
            if (!sessionOpen) return null
            lastRateKw = null
            lastSampleMs = nowMs
            return chargedKwh.rounded()
        }

        val rateKw = chargeRateKw?.coerceAtLeast(0f)
        if (!sessionOpen) {
            sessionOpen = true
            chargedKwh = 0.0
            lastSampleMs = nowMs
            lastRateKw = rateKw
            return 0.0
        }

        val gapMs = nowMs - lastSampleMs
        val previousRate = lastRateKw
        if (rateKw != null && previousRate != null && gapMs in 1..maxSampleGapMs) {
            chargedKwh += (previousRate + rateKw) / 2.0 * (gapMs / MS_PER_HOUR)
        }
        lastSampleMs = nowMs
        lastRateKw = rateKw
        return chargedKwh.rounded()
    }

    private fun Double.rounded(): Double = kotlin.math.round(this * 100.0) / 100.0

    companion object {
        const val MAX_SAMPLE_GAP_MS = 1_200_000L
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
