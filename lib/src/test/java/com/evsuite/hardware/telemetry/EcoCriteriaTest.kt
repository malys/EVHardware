package com.evsuite.hardware.telemetry

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-099: the eco score's five criteria, and the measurements under them. */
class EcoCriteriaTest {

    private val generation = FirmwareInfo.Gen.SWI68
    private val speedEvidence = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.CURRENT)
    private val powerEvidence = BatteryPowerEvidence(generation, 2)

    // --- SpeedSlope ---

    @Test fun `a burst of two updates in one read reads as the car's slope, not double`() {
        val slope = SpeedSlope()
        // 1 m/s² for real (3.6 km/h every second), but the read at 2 s missed its update and
        // the read at 3 s caught two: read to read, 0 then 2 m/s².
        assertNull(slope.add(0L, 50.0))
        assertNull(slope.add(1_000L, 53.6))
        assertNull(slope.add(2_000L, 53.6))
        assertEquals(1.0, slope.add(3_000L, 60.8)!!, 1e-9)
        assertEquals(1.0, slope.add(4_000L, 64.4)!!, 1e-9)
        assertEquals(1.0, slope.add(5_000L, 68.0)!!, 1e-9)
    }

    @Test fun `a gap or a missing speed starts the slope over`() {
        val slope = SpeedSlope()
        (0..3).forEach { slope.add(it * 1_000L, 50.0 + it) }
        assertNull(slope.add(30_000L, 120.0))
        assertNull(slope.add(31_000L, null))
        assertNull(slope.add(32_000L, 120.0))
    }

    // --- EnergyTripAccumulator ---

    private fun snapshot(atMs: Long, speed: Float, powerKw: Float?) = EnergySnapshot(
        timestampMs = atMs, firmware = generation, socPercent = 80f,
        rangeKm = null, speedKmh = speed, batteryPowerKw = powerKw,
        outsideTempCelsius = null, cabinTempCelsius = null, batteryTempCelsius = null,
        batteryEnergyKwh = null, batteryCapacityKwh = null, odometerKm = null,
        chargePortConnected = null, chargingStatus = null, parked = null,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )

    private fun drive(speeds: List<Float>, powerKw: (Int) -> Float? = { 0f }): EnergyTripSummary {
        val trip = EnergyTripAccumulator(0L, 80f)
        speeds.forEachIndexed { second, speed -> trip.add(snapshot(second * 1_000L, speed, powerKw(second))) }
        return trip.snapshot(speeds.size * 1_000L)
    }

    @Test fun `speed shed and gained are split into gentle and firm`() {
        // Up at 1 m/s² (3.6 km/h a second) to 36, down at 2 m/s² (7.2 a second) to 0.
        val up = (0..10).map { it * 3.6f }
        val down = (1..5).map { 36f - it * 7.2f }
        val result = drive(up + down)
        assertEquals(36.0, result.gainedKmh!!, 1e-3)
        assertEquals(0.0, result.gainedFirmKmh!!, 1e-3)
        assertEquals(36.0, result.slowedKmh!!, 1e-3)
        // The first second down is still half on the climb's slope: (−7,2 + 3,6) / 2 s.
        assertEquals(28.8, result.slowedFirmKmh!!, 1e-3)
    }

    @Test fun `only the distance from 95 km h counts as fast, and only its km h over 110`() {
        val result = drive(List(3_601) { 120f })
        assertEquals(120.0, result.fastKm!!, 0.1)
        assertEquals(10.0, result.fastExcessKmhKm!! / result.fastKm!!, 1e-6)
        assertEquals(0.0, drive(List(61) { 90f }).fastKm!!, 1e-9)
    }

    @Test fun `braking demand nets the road load out, and the battery's share is what came back`() {
        // 90 to 0 at 2,5 m/s², 30 kW back the whole way.
        val speeds = (0..10).map { 90f - it * 9f }
        val result = drive(speeds) { if (it == 0) 0f else -30f }
        val kineticKwh = 0.5 * RouteGrade.MASS_KG * 25.0 * 25.0 / 3_600_000.0
        assertTrue(result.brakingDemandKwh!! < kineticKwh)
        assertTrue(result.brakingDemandKwh!! > kineticKwh * 0.8)
        // 9,5 s of the 10 at −30 kW, the first interval averaging −15.
        assertEquals(30.0 * 9.5 / 3_600.0, result.brakingRecoveredKwh!!, 1e-6)
    }

    @Test fun `no power reading leaves the braking unknown rather than wasted`() {
        val result = drive((0..10).map { 90f - it * 9f }) { null }
        assertNull(result.brakingDemandKwh)
        assertNull(result.brakingRecoveredKwh)
    }

    // --- EcoScore ---

    private fun summary(
        slowed: Double? = 400.0,
        slowedFirm: Double? = 100.0,
        gained: Double? = 400.0,
        gainedFirm: Double? = 40.0,
        fastKm: Double? = 0.0,
        fastExcess: Double? = 0.0,
        demand: Double? = 0.5,
        recovered: Double? = 0.25,
        brakings: Int? = 6,
    ) = EnergyTripSummary(
        startedAtMs = 0L, endedAtMs = 1_200_000L, durationMs = 1_200_000L, distanceKm = 10.0,
        startSocPercent = 80f, endSocPercent = 78f, consumedKwh = null, regeneratedKwh = null,
        distanceAvailable = true, movingMs = 1_200_000L, harshMs = 0L, harshAccelerations = 0,
        harshBrakings = brakings,
        slowedKmh = slowed, slowedFirmKmh = slowedFirm, gainedKmh = gained, gainedFirmKmh = gainedFirm,
        fastKm = fastKm, fastExcessKmhKm = fastExcess,
        brakingDemandKwh = demand, brakingRecoveredKwh = recovered,
    )

    @Test fun `a town drive is scored on four parts, its anticipation graded instead of counted`() {
        val score = EcoScore.of(summary(), verdict = null)!!
        // 25 % shed firmly, 10 % gained firmly, 50 % recovered; no motorway, no verdict.
        assertEquals(50, score.anticipation)
        assertEquals(80, score.smoothness)
        assertEquals(60, score.regeneration)
        assertNull(score.speed)
        assertNull(score.efficiency)
        // (50 × 0,25 + 80 × 0,15 + 60 × 0,15) / 0,55
        assertEquals(61, score.total)
    }

    @Test fun `a motorway drive at 130 loses the speed part, at 110 keeps it`() {
        assertEquals(20, EcoScore.of(summary(fastKm = 50.0, fastExcess = 50.0 * 20), null)!!.speed)
        assertEquals(100, EcoScore.of(summary(fastKm = 50.0, fastExcess = 0.0), null)!!.speed)
    }

    @Test fun `too little braking or slowing to judge drops the part, not to a default`() {
        val score = EcoScore.of(summary(slowed = 100.0, demand = 0.01), null)!!
        assertNull(score.anticipation)
        assertNull(score.regeneration)
    }

    @Test fun `a trip recorded before CP-099 keeps its hard-stop anticipation`() {
        val score = EcoScore.of(
            summary(slowed = null, slowedFirm = null, gained = null, gainedFirm = null,
                fastKm = null, fastExcess = null, demand = null, recovered = null, brakings = 3),
            verdict = null,
        )!!
        assertEquals(50, score.anticipation)
        assertEquals(100, score.smoothness)
        assertNull(score.regeneration)
    }

    // --- EcoVerdict on battery power ---

    /** Predicts 18.0 % per 100 km at 100 km/h and 20 °C. */
    private val model = SocConsumptionModel(
        speedEvidence = speedEvidence,
        rollingPercentPer100Km = 10.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0008,
        thermalPercentPer100KmPerDegree = 0.2,
        residualRmsePercentPer100Km = 0.5,
        segmentCount = SocConsumptionFitter.MIN_SEGMENTS,
        envelope = EnergyModelEnvelope(20.0, 160.0, -10.0, 35.0),
    )

    private fun motorway(consumed: Double?, regenerated: Double?) = EnergyTripSummary(
        startedAtMs = 0L, endedAtMs = 1_800_000L, durationMs = 1_800_000L, distanceKm = 50.0,
        startSocPercent = 90f, endSocPercent = 81f, consumedKwh = consumed, regeneratedKwh = regenerated,
        distanceAvailable = true, speedEvidence = speedEvidence,
        batteryPowerEvidence = powerEvidence.takeIf { consumed != null },
    )

    @Test fun `with a kWh per percent the verdict reads the integrated energy, not the gauge`() {
        val trip = motorway(consumed = 4.55, regenerated = 0.5)
        val gauge = EcoDrivingMonitor().verdict(model, trip, 20f, generation)
        val power = EcoDrivingMonitor().verdict(model, trip, 20f, generation, kwhPerSocPercent = 0.5)
        assertEquals(0.0, gauge.deltaPercent!!, 0.5)
        // 4,05 kWh at 0,5 kWh a percent is 8,1 %: 16,2 % per 100 km against 18,0.
        assertEquals(-10.0, power.deltaPercent!!, 0.5)
    }

    @Test fun `without a power reading the gauge still decides`() {
        val verdict = EcoDrivingMonitor().verdict(model, motorway(null, null), 20f, generation, kwhPerSocPercent = 0.5)
        assertEquals(0.0, verdict.deltaPercent!!, 0.5)
    }

    @Test fun `kWh per percent comes from the car's own trips, and needs enough of them`() {
        val trips = List(4) { motorway(consumed = 5.0, regenerated = 0.5) }
        // 18 kWh over 36 % of gauge.
        assertEquals(0.5, EcoDrivingMonitor.kwhPerSocPercent(trips)!!, 1e-9)
        assertNull(EcoDrivingMonitor.kwhPerSocPercent(trips.take(3)))
        assertNotNull(EcoDrivingMonitor.kwhPerSocPercent(trips + motorway(null, null)))
    }
}
