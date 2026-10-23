package com.evsuite.hardware.telemetry

/**
 * Two consecutive trips told as one drive with a stop in it.
 *
 * The head unit powers off about twelve seconds after P, so a trip is saved at P or not at all.
 * A five-minute stop at a bakery therefore splits one drive in two. Joining puts it back: the
 * totals add, the track is the two tracks end to end, and the stop itself is in neither the
 * recorded duration nor the distance — the gap between the tracks is longer than any interval
 * an integrator accepts, so nothing is invented across it.
 *
 * Two trips are never joined across a charge: the pack rose between them, and a drive whose
 * end is higher than its start would read as negative consumption.
 */
object TripJoin {
    /** A stop this short, with no charge in it, is a pause, not the end of a drive. */
    const val AUTO_JOIN_MAX_STOP_MS = 10 * 60_000L

    /** More than one point of rise between two trips is a charge, not gauge noise. */
    const val CHARGE_RISE_PERCENT = 1f

    /**
     * Whether [later] may be joined onto [earlier] at all: in order, not across a charge, and
     * measured by the same conversions so the sums mean one thing. An unknown charge level
     * does not refuse — the driver who asks to join knows whether they plugged in.
     */
    fun canJoin(earlier: EnergyTripSummary, later: EnergyTripSummary): Boolean {
        if (later.startedAtMs < earlier.endedAtMs) return false
        val end = earlier.endSocPercent
        val start = later.startSocPercent
        if (end != null && start != null && start - end > CHARGE_RISE_PERCENT) return false
        return compatible(earlier.batteryPowerEvidence, later.batteryPowerEvidence) &&
            compatible(earlier.speedEvidence, later.speedEvidence)
    }

    /**
     * Whether the save at P should join on its own: a short stop, and both charge levels read,
     * so "no charge in between" is a measurement rather than an assumption.
     */
    fun shouldAutoJoin(earlier: EnergyTripSummary, later: EnergyTripSummary): Boolean =
        canJoin(earlier, later) &&
            later.startedAtMs - earlier.endedAtMs <= AUTO_JOIN_MAX_STOP_MS &&
            earlier.endSocPercent != null && later.startSocPercent != null

    fun join(earlier: StoredTrip, later: StoredTrip): StoredTrip {
        val a = earlier.summary
        val b = later.summary
        val power = a.consumedKwh != null && b.consumedKwh != null
        val distance = a.distanceAvailable != false && b.distanceAvailable != false
        val longer = if (b.durationMs > a.durationMs) b else a
        val summary = EnergyTripSummary(
            startedAtMs = a.startedAtMs,
            endedAtMs = b.endedAtMs,
            durationMs = a.durationMs + b.durationMs,
            distanceKm = a.distanceKm + b.distanceKm,
            startSocPercent = a.startSocPercent,
            endSocPercent = b.endSocPercent,
            consumedKwh = if (power) a.consumedKwh!! + b.consumedKwh!! else null,
            regeneratedKwh = sum(a.regeneratedKwh, b.regeneratedKwh).takeIf { power },
            // Null stays null: a pre-field record keeps its numeric distance authoritative.
            distanceAvailable = if (a.distanceAvailable == null && b.distanceAvailable == null) {
                null
            } else {
                distance
            },
            // Kept only when both halves carry it: an unrecorded half is unvalidated, never promoted.
            batteryPowerEvidence = a.batteryPowerEvidence
                .takeIf { power && it == b.batteryPowerEvidence },
            speedEvidence = a.speedEvidence.takeIf { distance && it == b.speedEvidence },
            movingMs = sum(a.movingMs, b.movingMs),
            harshMs = sum(a.harshMs, b.harshMs),
            harshAccelerations = sum(a.harshAccelerations, b.harshAccelerations),
            harshBrakings = sum(a.harshBrakings, b.harshBrakings),
            driveModeRaw = longer.driveModeRaw ?: a.driveModeRaw ?: b.driveModeRaw,
            regenLevelRaw = longer.regenLevelRaw ?: a.regenLevelRaw ?: b.regenLevelRaw,
        )
        val samples = earlier.samples.orEmpty() + later.samples.orEmpty()
        return StoredTrip(summary, samples.takeIf { it.isNotEmpty() })
    }

    /** Two evidences agree when they are equal or one of them was never recorded. */
    private fun <T> compatible(a: T?, b: T?) = a == null || b == null || a == b

    private fun sum(a: Long?, b: Long?): Long? = if (a != null && b != null) a + b else null
    private fun sum(a: Int?, b: Int?): Int? = if (a != null && b != null) a + b else null
    private fun sum(a: Double?, b: Double?): Double? = if (a != null && b != null) a + b else null
}
