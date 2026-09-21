package com.evsuite.hardware.telemetry

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

class EcoDrivingTest {

    private val generation = FirmwareInfo.Gen.SWI68
    private val evidence = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.CURRENT)

    /** Predicts 18.0 % per 100 km at 100 km/h and 20 °C, with a band of 0.98. */
    private val model = SocConsumptionModel(
        speedEvidence = evidence,
        rollingPercentPer100Km = 10.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0008,
        thermalPercentPer100KmPerDegree = 0.2,
        residualRmsePercentPer100Km = 0.5,
        segmentCount = SocConsumptionFitter.MIN_SEGMENTS,
        envelope = EnergyModelEnvelope(20.0, 160.0, -10.0, 35.0),
    )

    /** 50 km in half an hour, so the mean speed the model is asked about is 100 km/h. */
    private fun trip(
        startSoc: Float? = 90f,
        endSoc: Float? = 81f,
        distanceKm: Double = 50.0,
        durationMs: Long = 1_800_000L,
        speedEvidence: VehicleSpeedEvidence? = evidence,
    ) = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = durationMs,
        durationMs = durationMs,
        distanceKm = distanceKm,
        startSocPercent = startSoc,
        endSocPercent = endSoc,
        consumedKwh = null,
        regeneratedKwh = null,
        distanceAvailable = true,
        speedEvidence = speedEvidence,
    )

    private fun verdict(
        monitor: EcoDrivingMonitor = EcoDrivingMonitor(),
        trip: EnergyTripSummary? = trip(),
        model: SocConsumptionModel? = this.model,
        tempCelsius: Float? = 20f,
    ) = monitor.verdict(model, trip, tempCelsius, generation)

    /** A steady cruise, one sample a second, from which no lever can fire. */
    private fun cruise(speedKmh: Float, seconds: Int, fromMs: Long = 0L): EcoDrivingMonitor {
        val monitor = EcoDrivingMonitor()
        (0..seconds).forEach { monitor.add(fromMs + it * 1_000L, speedKmh) }
        return monitor
    }

    @Test
    fun `a drive below the expectation is better than it`() {
        // 7 % over 50 km is 14 per 100 km, below the 18.0 the fit expects by more than its band.
        val result = verdict(trip = trip(endSoc = 83f))
        assertEquals(EcoBand.BETTER, result.band.value)
        assertEquals(14.0, result.observedPercentPer100Km!!, 1e-9)
        assertEquals(18.0, result.expectedPercentPer100Km!!, 1e-9)
        assertTrue(result.deltaPercent!! < 0.0)
        assertEquals(100.0, result.meanSpeedKmh!!, 1e-9)
    }

    @Test
    fun `a drive inside the fit's own band is typical`() {
        assertEquals(EcoBand.TYPICAL, verdict().band.value)
    }

    @Test
    fun `a drive above the expectation is worse than it`() {
        val result = verdict(trip = trip(endSoc = 78f))
        assertEquals(EcoBand.WORSE, result.band.value)
        assertTrue(result.deltaPercent!! > 0.0)
    }

    @Test
    fun `the band widens with the fit's residual rather than a threshold of our own`() {
        // The same 24 per 100 km that is WORSE against a tight fit is TYPICAL against a loose
        // one: a model that knows little must not be allowed to accuse the driver.
        val loose = model.copy(residualRmsePercentPer100Km = 4.0)
        assertEquals(EcoBand.TYPICAL, verdict(trip = trip(endSoc = 78f), model = loose).band.value)
    }

    @Test
    fun `no model is a refusal, not a verdict`() {
        val result = verdict(model = null)
        assertNull(result.band.value)
        assertEquals(UnavailableReason.MODEL_NOT_TRAINED, result.band.reason)
    }

    @Test
    fun `a speed outside the fitted envelope is the model's refusal, carried through`() {
        // 200 km/h over the 160 the envelope saw.
        val result = verdict(trip = trip(distanceKm = 100.0))
        assertNull(result.band.value)
        assertEquals(UnavailableReason.MODEL_NOT_TRAINED, result.band.reason)
        assertEquals(200.0, result.meanSpeedKmh!!, 1e-9)
    }

    @Test
    fun `a distance too short to divide by is refused`() {
        val result = verdict(trip = trip(distanceKm = 1.0, durationMs = 36_000L))
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, result.band.reason)
    }

    @Test
    fun `a charge drop below the gauge's own step is refused`() {
        val result = verdict(trip = trip(endSoc = 89f))
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, result.band.reason)
    }

    @Test
    fun `a distance from the superseded speed conversion is refused`() {
        val stale = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.MPS_TIMES_3_6_V1)
        val result = verdict(trip = trip(speedEvidence = stale))
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, result.band.reason)
    }

    @Test
    fun `no outside temperature is a missing signal, not a comfortable assumption`() {
        val result = verdict(tempCelsius = null)
        assertEquals(UnavailableReason.SIGNAL_ABSENT, result.band.reason)
    }

    @Test
    fun `the cruise lever quotes the fit rather than a rule of thumb`() {
        val advice = verdict(monitor = cruise(110f, 120)).advice
        assertEquals(EcoLever.CRUISE_SPEED, advice?.lever)
        assertEquals(110.0, advice!!.fromSpeedKmh!!, 1e-9)
        assertEquals(100.0, advice.toSpeedKmh!!, 1e-9)
        // predict(110) is 19.68 and predict(100) is 18.0: 8.5 % less.
        assertEquals(8.54, advice.savingPercent!!, 0.01)
        assertNull(advice.harshSharePercent)
    }

    @Test
    fun `a town speed leaves the cruise lever alone`() {
        assertNull(verdict(monitor = cruise(50f, 120)).advice)
    }

    @Test
    fun `the steadiness lever fires from the speed channel alone`() {
        val monitor = EcoDrivingMonitor()
        var atMs = 0L
        var speed = 0f
        // Five stop-and-go cycles: 6 s of hard acceleration, then 18 s of cruising.
        repeat(5) {
            repeat(6) { speed += 8f; monitor.add(atMs, speed); atMs += 1_000L }
            repeat(18) { monitor.add(atMs, speed); atMs += 1_000L }
            speed = 0f
        }
        val advice = verdict(monitor = monitor, model = null).advice
        assertEquals(EcoLever.STEADINESS, advice?.lever)
        assertTrue(advice!!.harshSharePercent!! >= EcoDrivingMonitor.MIN_HARSH_SHARE_PERCENT)
        assertNull(advice.savingPercent)
    }

    @Test
    fun `a sampling gap is a gap, not an acceleration`() {
        val monitor = EcoDrivingMonitor()
        (0..70).forEach { monitor.add(it * 1_000L, 50f) }
        // The app was asleep for half a minute and the car is now at 120: unknown motion, and
        // reading it as one step would invent 3.9 m/s² out of the process having been paused.
        monitor.add(100_000L, 120f)
        assertNull(verdict(monitor = monitor, model = null).advice)
    }

    @Test
    fun `replay accepts the stored track cadence without relaxing live gaps`() {
        val samples = (0..24).map { index ->
            TripSample(
                atMs = index * 5_500L,
                speedKmh = if (index % 2 == 0) 0f else 36f,
                batteryPowerKw = null,
                socPercent = null,
                outsideTempCelsius = null,
                cabinTempCelsius = null,
                batteryTempCelsius = null,
                climatePowerOn = null,
                climateAcOn = null,
                climateFanLevel = null,
            )
        }

        assertEquals(EcoLever.STEADINESS, EcoDrivingMonitor.forReplay().replay(samples).steadiness()?.lever)
        assertNull(EcoDrivingMonitor().replay(samples).steadiness())
    }

    @Test
    fun `a window too short to judge says nothing`() {
        assertNull(verdict(monitor = cruise(110f, 30)).advice)
    }

    @Test
    fun `the cruise lever wins when both are measurable`() {
        val monitor = EcoDrivingMonitor()
        var atMs = 0L
        var speed = 100f
        // Motorway, and driven unevenly: both levers have something to say and only one is said.
        repeat(5) {
            repeat(4) { speed += 8f; monitor.add(atMs, speed); atMs += 1_000L }
            repeat(20) { monitor.add(atMs, speed); atMs += 1_000L }
            speed = 100f
        }
        assertEquals(EcoLever.CRUISE_SPEED, verdict(monitor = monitor).advice?.lever)
    }

    @Test
    fun `replaying a stored track is the same monitor the dashboard fed`() {
        val samples = (0..120).map { index ->
            TripSample(
                atMs = index * 1_000L,
                speedKmh = 110f,
                batteryPowerKw = null,
                socPercent = null,
                outsideTempCelsius = 20f,
                cabinTempCelsius = null,
                batteryTempCelsius = null,
                climatePowerOn = null,
                climateAcOn = null,
                climateFanLevel = null,
            )
        }
        val replayed = EcoDrivingMonitor().replay(samples)
        assertEquals(
            verdict(monitor = cruise(110f, 120)).advice,
            verdict(monitor = replayed).advice,
        )
    }

    @Test
    fun `describe names the refusal it made`() {
        val lines = verdict(model = null).describe()
        assertTrue(lines.any { it == "band=unavailable(MODEL_NOT_TRAINED)" })
        assertTrue(lines.any { it == "observed_percent_per_100km=unavailable" })
        assertTrue(lines.any { it == "advice=none" })
    }

    @Test
    fun `describe states the verdict and its inputs`() {
        val lines = verdict(monitor = cruise(110f, 120)).describe()
        assertTrue(lines.any { it == "band=TYPICAL" })
        assertTrue(lines.any { it == "observed_percent_per_100km=18.00" })
        assertTrue(lines.any { it == "expected_percent_per_100km=18.00" })
        assertNotNull(lines.firstOrNull { it.startsWith("advice=CRUISE_SPEED") })
    }
}
