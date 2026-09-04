package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocRateEstimatorTest {

    /** The 2026-09-04 bundle: 56,1 % of charge and the car's own 278 km of range. */
    @Test fun `the vehicle's own range gives a rate from the first reading`() {
        val rate = SocRateEstimator.fromVehicleRange(56.1, 278.0)!!
        assertEquals(0.2018, rate.percentPerKm, 1e-4)
        assertEquals(SocRate.Source.VEHICLE_RANGE, rate.source)
        assertEquals(rate.percentPerKm * 0.20, rate.uncertaintyPercentPerKm, 1e-9)
    }

    @Test fun `an absent or impossible reading yields no rate rather than a zero one`() {
        assertNull(SocRateEstimator.fromVehicleRange(null, 278.0))
        assertNull(SocRateEstimator.fromVehicleRange(56.1, null))
        assertNull(SocRateEstimator.fromVehicleRange(56.1, 0.0))
        assertNull(SocRateEstimator.fromVehicleRange(-1.0, 278.0))
        assertNull(SocRateEstimator.fromVehicleRange(56.1, Double.NaN))
    }

    private fun trip(km: Double, spentSoc: Double, vouched: Boolean = true) = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = 1_000L,
        durationMs = 1_000L,
        distanceKm = km,
        startSocPercent = 80f,
        endSocPercent = (80.0 - spentSoc).toFloat(),
        consumedKwh = null,
        regeneratedKwh = null,
        distanceAvailable = true,
        speedEvidence = VehicleSpeedEvidence(
            GENERATION,
            if (vouched) VehicleSpeedEvidence.CURRENT else VehicleSpeedEvidence.MPS_TIMES_3_6_V1,
        ),
    )

    private companion object {
        /** The generation the vehicle evidence was gathered on. */
        val GENERATION = FirmwareInfo.Gen.SWI68
    }

    @Test fun `fewer than three usable trips is not a band`() {
        assertNull(SocRateEstimator.fromTrips(listOf(trip(10.0, 2.0), trip(10.0, 2.1)), GENERATION))
    }

    @Test fun `a distance the current conversion did not produce is never averaged in`() {
        // Three trips, all recorded under the 3.6x conversion: the rate would come out 3.6
        // times too optimistic, so no rate is offered at all.
        val stale = List(3) { trip(36.0, 2.0, vouched = false) }
        assertNull(SocRateEstimator.fromTrips(stale, GENERATION))
    }

    @Test fun `a trip whose charge rose is not a trip about consumption`() {
        val charging = EnergyTripSummary(
            startedAtMs = 0L, endedAtMs = 1L, durationMs = 1L, distanceKm = 10.0,
            startSocPercent = 40f, endSocPercent = 80f,
            consumedKwh = null, regeneratedKwh = null, distanceAvailable = true,
        )
        assertNull(SocRateEstimator.fromTrips(listOf(charging, charging, charging), GENERATION))
    }

    @Test fun `trip history is preferred over the vehicle's range when there is enough of it`() {
        val trips = List(4) { trip(10.0, 2.0) }
        val best = SocRateEstimator.best(56.1, 278.0, trips, GENERATION)!!
        assertEquals(SocRate.Source.TRIP_HISTORY, best.source)
        assertEquals(0.2, best.percentPerKm, 1e-9)
        assertEquals(4, best.sampleCount)
    }

    @Test fun `identical trips still carry a floor on their band`() {
        val best = SocRateEstimator.fromTrips(List(4) { trip(10.0, 2.0) }, GENERATION)!!
        assertEquals(0.2 * 0.10, best.uncertaintyPercentPerKm, 1e-9)
    }

    @Test fun `with too little history the vehicle's own range is used instead`() {
        val best = SocRateEstimator.best(56.1, 278.0, listOf(trip(10.0, 2.0)), GENERATION)!!
        assertEquals(SocRate.Source.VEHICLE_RANGE, best.source)
    }
}

class ArrivalSocForecastTest {

    private val vehicleRate = SocRateEstimator.fromVehicleRange(56.1, 278.0)!!

    @Test fun `the captured route forecasts an arrival with a band`() {
        // The 2026-09-04 guidance capture: 9788 m remaining at 56,1 %.
        val arrival = ArrivalSocForecast.of(56.1, 9.788, vehicleRate)
        assertEquals(Provenance.ESTIMATED, arrival.provenance)
        assertEquals(54.12, arrival.value!!, 0.01)
        assertNotNull("an arrival figure is never a bare number", arrival.uncertainty)
    }

    @Test fun `the band widens with distance`() {
        val near = ArrivalSocForecast.of(56.1, 10.0, vehicleRate).uncertainty!!
        val far = ArrivalSocForecast.of(56.1, 50.0, vehicleRate).uncertainty!!
        assertTrue("a longer route is a less certain one", far > near)
        assertEquals(5.0, far / near, 1e-9)
    }

    @Test fun `a band wider than the ceiling is refused rather than shown`() {
        val arrival = ArrivalSocForecast.of(56.1, 500.0, vehicleRate)
        assertEquals(Provenance.UNAVAILABLE, arrival.provenance)
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, arrival.reason)
        assertNull(arrival.value)
    }

    @Test fun `a destination beyond the charge reports a negative arrival, not a zero`() {
        // 56,1 % at 0,2018 %/km reaches about 278 km; 300 km does not fit.
        val trips = SocRate(0.2018, 0.002, SocRate.Source.TRIP_HISTORY, 5)
        val arrival = ArrivalSocForecast.of(56.1, 300.0, trips)
        assertTrue("running out has to be sayable", arrival.value!! < 0.0)
    }

    @Test fun `no rate means no forecast, with a reason`() {
        val arrival = ArrivalSocForecast.of(56.1, 9.788, null)
        assertEquals(Provenance.UNAVAILABLE, arrival.provenance)
        assertEquals(UnavailableReason.MODEL_NOT_TRAINED, arrival.reason)
    }

    @Test fun `an absent charge or distance is unavailable`() {
        assertEquals(
            Provenance.UNAVAILABLE,
            ArrivalSocForecast.of(null, 9.788, vehicleRate).provenance,
        )
        assertEquals(
            Provenance.UNAVAILABLE,
            ArrivalSocForecast.of(56.1, null, vehicleRate).provenance,
        )
    }

    @Test fun `range at this rate matches the vehicle's own when the rate came from it`() {
        val range = ArrivalSocForecast.rangeAtRateKm(56.1, vehicleRate)
        assertEquals(278.0, range.value!!, 0.1)
        assertTrue("the quoted band never overstates range", range.uncertainty!! > 0.0)
    }
}
