package com.evsuite.hardware.telemetry

data class EnergyTripSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    /**
     * Time actually covered by usable samples, not wall clock. A trip whose sampler was
     * suspended — the dashboard went to the background, the process was paused — keeps a
     * duration consistent with its distance and energy, which is what the averages divide.
     */
    val durationMs: Long,
    val distanceKm: Double,
    val startSocPercent: Float?,
    val endSocPercent: Float?,
    val consumedKwh: Double?,
    val regeneratedKwh: Double?,
) {
    val averageConsumptionKwhPer100Km: Double?
        get() = if (consumedKwh != null && distanceKm >= 0.1) {
            consumedKwh * 100.0 / distanceKm
        } else null
}

/** A finished trip: its totals, and the track they were integrated from. */
data class RecordedTrip(
    val summary: EnergyTripSummary,
    val samples: List<TripSample>,
)

/** Trapezoidal integration with bounded gaps so sleep/resume never invents energy. */
class EnergyTripAccumulator(
    private val startedAtMs: Long,
    private val startSocPercent: Float?,
    private val track: TripSampleTrack = TripSampleTrack(),
) {
    private var last: EnergySnapshot? = null
    private var distanceKm = 0.0
    private var consumedKwh = 0.0
    private var regeneratedKwh = 0.0
    private var hasPowerInterval = false
    private var recordedMs = 0L
    private var latestSocPercent = startSocPercent

    fun add(sample: EnergySnapshot) {
        track.add(sample)
        latestSocPercent = sample.socPercent ?: latestSocPercent
        val previous = last
        last = sample
        if (previous == null) return
        val gapMs = sample.timestampMs - previous.timestampMs
        if (gapMs <= 0L || gapMs > MAX_SAMPLE_GAP_MS) return
        recordedMs += gapMs
        val hours = gapMs / 3_600_000.0
        if (previous.speedKmh != null && sample.speedKmh != null) {
            distanceKm += ((previous.speedKmh + sample.speedKmh) / 2.0) * hours
        }
        if (previous.batteryPowerKw != null && sample.batteryPowerKw != null) {
            hasPowerInterval = true
            val intervalKwh =
                ((previous.batteryPowerKw + sample.batteryPowerKw) / 2.0) * hours
            if (intervalKwh >= 0.0) consumedKwh += intervalKwh
            else regeneratedKwh += -intervalKwh
        }
    }

    fun samples(): List<TripSample> = track.samples()

    fun snapshot(nowMs: Long) = EnergyTripSummary(
        startedAtMs = startedAtMs,
        endedAtMs = nowMs,
        durationMs = recordedMs,
        distanceKm = distanceKm,
        startSocPercent = startSocPercent,
        endSocPercent = latestSocPercent,
        consumedKwh = consumedKwh.takeIf { hasPowerInterval },
        regeneratedKwh = regeneratedKwh.takeIf { hasPowerInterval },
    )

    private companion object { const val MAX_SAMPLE_GAP_MS = 5_000L }
}

/** Process-local recording state shared by any energy dashboard in the same app process. */
object EnergyTripSession {
    private var accumulator: EnergyTripAccumulator? = null
    val isRecording: Boolean get() = accumulator != null

    @Synchronized fun start(sample: EnergySnapshot) {
        if (accumulator != null) return
        accumulator = EnergyTripAccumulator(sample.timestampMs, sample.socPercent)
            .also { it.add(sample) }
    }
    @Synchronized fun add(sample: EnergySnapshot) = accumulator?.add(sample)
    @Synchronized fun current(nowMs: Long) = accumulator?.snapshot(nowMs)
    @Synchronized fun stop(nowMs: Long): RecordedTrip? {
        val finished = accumulator ?: return null
        accumulator = null
        return RecordedTrip(finished.snapshot(nowMs), finished.samples())
    }
}
