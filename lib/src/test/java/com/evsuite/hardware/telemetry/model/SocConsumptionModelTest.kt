package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocConsumptionModelTest {

    private val generation = FirmwareInfo.Gen.SWI68
    private val current = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.CURRENT)
    private val stale = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.MPS_TIMES_3_6_V1)

    /**
     * A drive at one speed and one temperature, consuming [percentPer100Km] of charge.
     *
     * Sampled every 5 s like the real track, and long enough to cross the 2 % step several
     * times, which is what produces more than one training point.
     */
    private fun drive(
        speedKmh: Double,
        tempCelsius: Double,
        percentPer100Km: Double,
        distanceKm: Double = 60.0,
        startSoc: Double = 90.0,
        startAtMs: Long = 0L,
        intervalMs: Long = 5_000L,
    ): List<TripSample> {
        val hours = distanceKm / speedKmh
        val steps = (hours * 3_600_000.0 / intervalMs).toInt()
        return (0..steps).map { index ->
            val travelled = distanceKm * index / steps
            TripSample(
                atMs = startAtMs + index * intervalMs,
                speedKmh = speedKmh.toFloat(),
                batteryPowerKw = null,
                socPercent = (startSoc - travelled * percentPer100Km / 100.0).toFloat(),
                outsideTempCelsius = tempCelsius.toFloat(),
                cabinTempCelsius = null,
                batteryTempCelsius = null,
                climatePowerOn = null,
                climateAcOn = null,
                climateFanLevel = null,
            )
        }
    }

    private fun trip(
        samples: List<TripSample>,
        evidence: VehicleSpeedEvidence? = current,
    ) = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = samples.first().atMs,
            endedAtMs = samples.last().atMs,
            durationMs = samples.last().atMs - samples.first().atMs,
            distanceKm = 60.0,
            startSocPercent = samples.first().socPercent,
            endSocPercent = samples.last().socPercent,
            consumedKwh = null,
            regeneratedKwh = null,
            distanceAvailable = true,
            batteryPowerEvidence = null,
            speedEvidence = evidence,
        ),
        samples = samples,
    )

    /** Four drives, two speeds and two temperatures — the smallest history that determines a fit. */
    private fun history() = listOf(
        trip(drive(90.0, 20.0, 16.0, startAtMs = 0L)),
        trip(drive(130.0, 20.0, 26.0, startAtMs = 100_000_000L)),
        trip(drive(90.0, 0.0, 21.0, startAtMs = 200_000_000L)),
        trip(drive(130.0, 0.0, 31.0, startAtMs = 300_000_000L)),
    )

    @Test
    fun `a car that never publishes battery power still gets a consumption model`() {
        val result = SocConsumptionFitter().fit(history(), generation)
        assertTrue("$result", result is SocConsumptionFitResult.Ready)
        val model = (result as SocConsumptionFitResult.Ready).model

        val at90 = model.predict(90.0, 20.0)
        val at130 = model.predict(130.0, 20.0)
        assertNotNull(at90.value)
        assertNotNull(at130.value)
        // The whole point of the fit: going faster costs more, and by roughly what was driven.
        assertTrue("130 costs more than 90", at130.value!! > at90.value!!)
        assertEquals(16.0, at90.value!!, 1.5)
        assertEquals(26.0, at130.value!!, 1.5)
        assertNotNull("every prediction carries a band", at130.uncertainty)
    }

    @Test
    fun `no capacity is asked for anywhere, because none is used`() {
        // The unit is percent of charge. If this ever needs a pack size to answer, the
        // specification sheet has crept back into the model.
        val model = (SocConsumptionFitter().fit(history(), generation)
            as SocConsumptionFitResult.Ready).model
        assertEquals(current, model.speedEvidence)
        assertTrue(model.predict(110.0, 20.0).value!! > 0.0)
    }

    @Test
    fun `a distance recorded by a conversion no longer believed contributes nothing`() {
        val result = SocConsumptionFitter().fit(
            history().map { trip(it.samples!!, evidence = stale) },
            generation,
        )
        assertEquals(
            SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES),
            result,
        )
    }

    @Test
    fun `one speed is not a speed dependence`() {
        val result = SocConsumptionFitter().fit(
            listOf(
                trip(drive(90.0, 20.0, 16.0, startAtMs = 0L)),
                trip(drive(90.0, 0.0, 21.0, startAtMs = 100_000_000L)),
            ),
            generation,
        )
        assertEquals(
            SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES),
            result,
        )
    }

    @Test
    fun `one temperature fits two coefficients and refuses the cold it never saw`() {
        val result = SocConsumptionFitter().fit(
            listOf(
                trip(drive(90.0, 20.0, 16.0, distanceKm = 120.0, startAtMs = 0L)),
                trip(drive(130.0, 20.0, 26.0, distanceKm = 120.0, startAtMs = 100_000_000L)),
            ),
            generation,
        )
        val model = (result as SocConsumptionFitResult.Ready).model
        assertEquals(0.0, model.thermalPercentPer100KmPerDegree, 1e-12)
        assertNotNull(model.predict(110.0, 20.0).value)
        // -5 °C is outside everything this fit has seen, and it says so rather than guessing.
        assertNull(model.predict(110.0, -5.0).value)
    }

    @Test
    fun `a hole in the track is not driven through`() {
        // Two minutes of missing samples across which the car covered kilometres nobody
        // recorded. Bridged, the charge they cost would land on the kilometres either side.
        val first = drive(110.0, 20.0, 20.0, distanceKm = 30.0, startAtMs = 0L)
        val second = drive(
            110.0, 20.0, 20.0,
            distanceKm = 30.0,
            startSoc = 80.0,
            startAtMs = first.last().atMs + 600_000L,
        )
        val fitter = SocConsumptionFitter()
        val segments = ArrayList<SocConsumptionFitter.Segment>()
        fitter.collect(first + second, segments)
        assertTrue("segments exist on both sides", segments.size >= 2)
        // 20 %/100 km on both sides; a bridged segment would report a far larger number.
        assertTrue(segments.all { it.percentPer100Km < 25.0 })
    }

    @Test
    fun `charging is not driving`() {
        val charging = (0..40).map { index ->
            TripSample(
                atMs = index * 5_000L,
                speedKmh = 0f,
                batteryPowerKw = null,
                socPercent = (40.0 + index).toFloat(),
                outsideTempCelsius = 20f,
                cabinTempCelsius = null,
                batteryTempCelsius = null,
                climatePowerOn = null,
                climateAcOn = null,
                climateFanLevel = null,
            )
        }
        val segments = ArrayList<SocConsumptionFitter.Segment>()
        SocConsumptionFitter().collect(charging, segments)
        assertTrue(segments.isEmpty())
    }
}
