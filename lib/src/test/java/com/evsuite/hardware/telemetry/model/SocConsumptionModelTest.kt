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
    fun `weather that costs nothing falls back to two coefficients instead of refusing`() {
        // The 10 °C drives cost less than the 20 °C ones, so a thermal term comes out negative.
        val result = SocConsumptionFitter().fit(
            listOf(
                trip(drive(90.0, 20.0, 21.0, startAtMs = 0L)),
                trip(drive(130.0, 20.0, 31.0, startAtMs = 100_000_000L)),
                trip(drive(90.0, 10.0, 16.0, startAtMs = 200_000_000L)),
                trip(drive(130.0, 10.0, 26.0, startAtMs = 300_000_000L)),
            ),
            generation,
        )
        val model = (result as SocConsumptionFitResult.Ready).model
        assertEquals(0.0, model.thermalPercentPer100KmPerDegree, 1e-12)
        assertTrue(model.predict(130.0, 15.0).value!! > model.predict(90.0, 15.0).value!!)
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

    @Test
    fun `a gauge in tenths trains on a town drive that a whole-percent gauge cannot`() {
        // Nine kilometres at 40 km/h for 15 %/100 km: 1.35 % of charge, under two whole points.
        val town = drive(40.0, 20.0, 15.0, distanceKm = 9.0)
        fun gauge(step: Float) = town.map {
            it.copy(socPercent = kotlin.math.round(it.socPercent!! / step) * step)
        }
        val tenths = ArrayList<SocConsumptionFitter.Segment>()
        SocConsumptionFitter().collect(gauge(0.1f), tenths)
        val whole = ArrayList<SocConsumptionFitter.Segment>()
        SocConsumptionFitter().collect(gauge(1f), whole)

        // Cut at two kilometres, the shortest segment a stretch may be, and still near 15.
        assertEquals(4, tenths.size)
        assertTrue("$tenths", tenths.all { it.distanceKm >= 2.0 && it.percentPer100Km in 10.0..20.0 })
        assertTrue(whole.isEmpty())
    }

    @Test
    fun `describe tells the two INSUFFICIENT_SAMPLES gates apart`() {
        // The reason code is the same word for both, and the two ask the driver for opposite
        // things. Two validation bundles reported that word without saying which.
        val fitter = SocConsumptionFitter()

        // Segments enough, one speed band. The answer is "drive a motorway leg".
        val oneSpeed = listOf(
            trip(drive(90.0, 20.0, 16.0, startAtMs = 0L)),
            trip(drive(90.0, 0.0, 21.0, startAtMs = 100_000_000L)),
        )
        assertEquals(
            SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES),
            fitter.fit(oneSpeed, generation),
        )
        val narrow = fitter.describe(oneSpeed, generation)
        assertTrue(narrow, narrow.contains("used=2/2"))
        assertTrue(narrow, narrow.contains("span=0/20 km/h"))

        // Every trip skipped for a conversion no longer believed: no segments at all, and
        // nothing about speed to say. The answer is "these kilometres do not count".
        val staleHistory = oneSpeed.map { trip(it.samples!!, evidence = stale) }
        assertEquals(
            SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES),
            fitter.fit(staleHistory, generation),
        )
        val skipped = fitter.describe(staleHistory, generation)
        assertTrue(skipped, skipped.contains("used=0/2"))
        assertTrue(skipped, skipped.contains("segments=0/"))
        assertTrue(skipped, !skipped.contains("span="))
    }

    @Test
    fun `describe says whether the solve past the gates produced a model`() {
        // Past both gates the line used to stop, so a bundle could not tell a trained model
        // from a solve that refused one.
        val ready = SocConsumptionFitter().describe(history(), generation)
        assertTrue(ready, ready.startsWith("used=4/4 segments="))
        assertTrue(ready, Regex(""" fit=ready rmse=\d+\.\d{2} rolling=""").containsMatchIn(ready))

        // Faster costs less: a negative aero term, which the fit refuses by name.
        val backwards = listOf(
            trip(drive(90.0, 20.0, 26.0, distanceKm = 120.0, startAtMs = 0L)),
            trip(drive(130.0, 20.0, 16.0, distanceKm = 120.0, startAtMs = 100_000_000L)),
        )
        assertEquals(
            SocConsumptionFitResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED),
            SocConsumptionFitter().fit(backwards, generation),
        )
        val refused = SocConsumptionFitter().describe(backwards, generation)
        assertTrue(refused, refused.endsWith("span=40/20 km/h fit=refused(aero)"))

        val strict = SocConsumptionFitter(maxResidualRmse = -1.0).describe(history(), generation)
        assertTrue(strict, Regex(""" fit=refused\(rmse=\d+\.\d{2}\)$""").containsMatchIn(strict))
    }

    /**
     * The 2026-10-22 refusal, reproduced: a history mostly in town, where heating and the
     * electronics cost more per kilometre the slower the car goes. Fitted as rolling + aero
     * alone, these points give a negative aero coefficient and the fit was refused. With the
     * auxiliary term the true shape comes back, and faster still costs more on the motorway.
     */
    @Test
    fun `a town-heavy history fits once the auxiliary load has its own term`() {
        val truth = { v: Double -> 9.0 + 0.0006 * v * v + 300.0 / v }
        val history = listOf(25.0, 30.0, 40.0, 50.0, 90.0).mapIndexed { index, speed ->
            trip(drive(speed, 20.0, truth(speed), startAtMs = index * 100_000_000L))
        }

        val result = SocConsumptionFitter().fit(history, generation)
        assertTrue("$result", result is SocConsumptionFitResult.Ready)
        val model = (result as SocConsumptionFitResult.Ready).model
        assertTrue(model.aeroPercentPer100KmPerSpeedSquared >= 0.0)
        assertEquals(300.0, model.auxPercentPer100KmTimesKmh, 30.0)
        assertEquals(truth(30.0), model.predict(30.0, 20.0).value!!, 0.5)
        assertTrue(model.predict(90.0, 20.0).value!! > model.predict(60.0, 20.0).value!!)
        assertTrue(SocConsumptionFitter().describe(history, generation).contains("fit=ready"))
    }

    @Test
    fun `a term that only fits negative is left out, not believed`() {
        // Pure speed² data: the auxiliary term has nothing to explain and must not go negative.
        val history = listOf(40.0, 70.0, 100.0, 130.0).mapIndexed { index, speed ->
            trip(drive(speed, 20.0, 12.0 + 0.0008 * speed * speed, startAtMs = index * 100_000_000L))
        }
        val model = (SocConsumptionFitter().fit(history, generation) as SocConsumptionFitResult.Ready).model
        assertTrue(model.auxPercentPer100KmTimesKmh >= 0.0)
        assertEquals(12.0 + 0.0008 * 100.0 * 100.0, model.predict(100.0, 20.0).value!!, 0.5)
    }
}
