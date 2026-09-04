package com.evsuite.hardware.telemetry

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.VehicleSpeedEvidence

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
    /**
     * `null` means a pre-field record whose numeric distance remains authoritative for
     * compatibility. New accumulators write false until at least one usable speed interval
     * exists, so an unreadable signal can no longer masquerade as 0 km.
     */
    val distanceAvailable: Boolean? = null,
    /** Null for legacy or unvalidated records; models must not reuse their energy totals. */
    val batteryPowerEvidence: BatteryPowerEvidence? = null,
    /**
     * Which speed conversion produced [distanceKm]. Null on records written before the
     * conversion was versioned — their distance may carry the 3.6x error and no model may
     * train on it. See [VehicleSpeedEvidence].
     */
    val speedEvidence: VehicleSpeedEvidence? = null,
) {
    /**
     * The distance, only when the conversion behind it is the one believed correct today.
     *
     * `recordedDistanceKm` stays what the trip reported, because history is what it is and a
     * driver's own record should not silently change. This is the one a model may use.
     */
    fun modellableDistanceKm(
        generation: com.evsuite.hardware.FirmwareInfo.Gen =
            com.evsuite.hardware.FirmwareInfo.getGeneration(),
    ): Double? = recordedDistanceKm?.takeIf { speedEvidence?.matchesCurrent(generation) == true }

    val recordedDistanceKm: Double?
        get() = distanceKm.takeIf { distanceAvailable != false }

    val averageConsumptionKwhPer100Km: Double?
        get() = if (consumedKwh != null && recordedDistanceKm != null && distanceKm >= 0.1) {
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
    private val batteryPowerEvidence: BatteryPowerEvidence? = null,
    private val speedEvidence: VehicleSpeedEvidence? = VehicleSpeedEvidence.current(),
) {
    private var last: EnergySnapshot? = null
    private var distanceKm = 0.0
    private var consumedKwh = 0.0
    private var regeneratedKwh = 0.0
    private var hasSpeedInterval = false
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
            hasSpeedInterval = true
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
        distanceAvailable = hasSpeedInterval,
        speedEvidence = speedEvidence.takeIf { hasSpeedInterval },
        batteryPowerEvidence = batteryPowerEvidence.takeIf { hasPowerInterval },
    )

    private companion object { const val MAX_SAMPLE_GAP_MS = 5_000L }
}

/** Process-local recording state shared by any energy dashboard in the same app process. */
object EnergyTripSession {
    private var accumulator: EnergyTripAccumulator? = null
    val isRecording: Boolean get() = accumulator != null

    @Synchronized fun start(sample: EnergySnapshot) {
        if (accumulator != null) return
        accumulator = EnergyTripAccumulator(
            sample.timestampMs,
            sample.socPercent,
            batteryPowerEvidence = CarPropertyEvidence.batteryPowerEvidence(sample.firmware),
        )
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
