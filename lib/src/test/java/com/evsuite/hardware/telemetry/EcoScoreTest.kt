package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcoScoreTest {

    private fun trip(
        distanceKm: Double = 10.0,
        brakings: Int? = 3,
        harshMs: Long? = 60_000L,
        movingMs: Long? = 1_200_000L,
    ) = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = 1_200_000L,
        durationMs = 1_200_000L,
        distanceKm = distanceKm,
        startSocPercent = 80f,
        endSocPercent = 78f,
        consumedKwh = null,
        regeneratedKwh = null,
        distanceAvailable = true,
        movingMs = movingMs,
        harshMs = harshMs,
        harshAccelerations = 2,
        harshBrakings = brakings,
    )

    private fun verdict(delta: Double) = EcoVerdict(
        band = com.evsuite.hardware.telemetry.Provenanced.estimated(EcoBand.TYPICAL, 1.0),
        deltaPercent = delta,
    )

    @Test fun `a calm town drive at the usual consumption sits in the sixties, not near 100`() {
        // 3 hard stops per 10 km, 5 % of moving time hard, consumption exactly as expected.
        val score = EcoScore.of(trip(), verdict(0.0))!!
        assertEquals(50, score.anticipation)
        assertEquals(80, score.smoothness)
        assertEquals(63, score.efficiency)
        assertEquals(63, score.total)
    }

    @Test fun `a part that cannot be measured drops out instead of scoring zero or full`() {
        val score = EcoScore.of(trip(), verdict = null)!!
        assertNull(score.efficiency)
        // (50 × 0.35 + 80 × 0.25) / 0.60
        assertEquals(63, score.total)
    }

    @Test fun `a trip recorded before the counters existed has no score at all`() {
        assertNull(EcoScore.of(trip(brakings = null, harshMs = null, movingMs = null), null))
    }

    @Test fun `scales clamp at both ends`() {
        val best = EcoScore.of(trip(brakings = 0, harshMs = 0L), verdict(-40.0))!!
        assertEquals(100, best.total)
        val worst = EcoScore.of(trip(brakings = 20, harshMs = 600_000L), verdict(60.0))!!
        assertEquals(0, worst.total)
    }
}
