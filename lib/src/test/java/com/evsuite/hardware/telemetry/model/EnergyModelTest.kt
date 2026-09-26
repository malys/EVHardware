package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenance
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {
    private val evidence = BatteryPowerEvidence(
        FirmwareInfo.Gen.SWI68,
        BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
    )
    private val trainer = EnergyModelTrainer()

    @Test fun `synthetic data recovers known coefficients`() {
        val model = ready(trainer.fit(listOf(syntheticTrip()), evidence))

        assertEquals(10.0, model.rollingKwhPer100Km, 1e-6)
        assertEquals(0.0005, model.aeroKwhPer100KmPerSpeedSquared, 1e-9)
        assertEquals(0.10, model.thermalKwhPer100KmPerDegree, 1e-6)
        assertEquals(Provenance.ESTIMATED, model.predict(80.0, 10.0).provenance)
    }

    @Test fun `sparse data and a firmware with no power interpretation stay unavailable`() {
        val sparse = syntheticTrip().copy(samples = syntheticTrip().samples?.take(10))

        assertEquals(
            UnavailableReason.INSUFFICIENT_SAMPLES,
            (trainer.fit(listOf(sparse), evidence) as EnergyModelTrainingResult.Unavailable).reason,
        )
        // A generation whose battery power has neither a validated conversion nor a pack pair to
        // derive one from: nothing to train on, and it says so rather than fitting a guess.
        assertEquals(
            UnavailableReason.UNVALIDATED_FIRMWARE,
            (trainer.train(listOf(syntheticTrip()), FirmwareInfo.Gen.SWI69)
                as EnergyModelTrainingResult.Unavailable).reason,
        )
    }

    @Test fun `SWI68 trains on the pack pair rather than refusing`() {
        // The car this project runs on has no validated power conversion and will not get one
        // from a desk. Refusing to train left the speed comparison permanently empty; the model
        // is stamped with the derived interpretation instead, so a validated one supersedes it.
        val trip = storedTrip(
            syntheticTrip().samples,
            BatteryPowerEvidence(FirmwareInfo.Gen.SWI68, BatteryPowerEvidence.PACK_PAIR_DERIVED_V2),
        )
        val model = ready(trainer.train(listOf(trip), FirmwareInfo.Gen.SWI68))

        assertEquals(BatteryPowerEvidence.PACK_PAIR_DERIVED_V2, model.evidence.conversionVersion)
        assertEquals(Provenance.ESTIMATED, model.predict(80.0, 10.0).provenance)
    }

    @Test fun `prediction band covers held-out truth and extrapolation is refused`() {
        val model = ready(trainer.fit(listOf(syntheticTrip()), evidence))
        val prediction = model.predict(85.0, 12.0)
        val truth = consumption(85.0, 12.0)

        assertTrue(truth in prediction.bandLow!!..prediction.bandHigh!!)
        val outside = model.predict(model.envelope.maxSpeedKmh + 1.0, 12.0)
        assertEquals(Provenance.UNAVAILABLE, outside.provenance)
        assertEquals(UnavailableReason.MODEL_NOT_TRAINED, outside.reason)
    }

    @Test fun `model persistence round trips one bounded atomic file`() {
        val directory = Files.createTempDirectory("energy-model").toFile()
        try {
            val target = directory.resolve("model.json")
            val store = EnergyModelStore(target)
            val model = ready(trainer.fit(listOf(syntheticTrip()), evidence))

            assertTrue(store.write(model))
            assertEquals(model, store.read())
            assertTrue(target.length() in 1..EnergyModelStore.MAX_MODEL_BYTES.toLong())
            assertEquals(1, directory.listFiles().orEmpty().size)

            target.writeBytes(ByteArray(EnergyModelStore.MAX_MODEL_BYTES + 1))
            assertEquals(null, store.read())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `bounded month-sized fit completes inside host budget`() {
        val trip = syntheticTrip()
        val trips = List(EnergyModelTrainer.MAX_TRAINING_SAMPLES / SYNTHETIC_WINDOWS + 1) { trip }
        lateinit var result: EnergyModelTrainingResult

        val elapsedMs = measureTimeMillis { result = trainer.fit(trips, evidence) }

        assertNotNull((result as EnergyModelTrainingResult.Ready).model)
        assertEquals(
            EnergyModelTrainer.MAX_TRAINING_SAMPLES,
            (result as EnergyModelTrainingResult.Ready).model.sampleCount,
        )
        assertTrue("fit took ${elapsedMs}ms", elapsedMs < HOST_TRAINING_BUDGET_MS)
    }

    @Test fun `stop-and-go town driving trains once a dozen kilometres are recorded`() {
        // Every sample of an acceleration costs several times what a cruise does, and a fit
        // made per sample judged that spread as model error and refused the town for good.
        // Two afternoons two degrees apart: no evidence about weather, which must not stop it.
        val trips = listOf(24.0, 26.0).map { temp ->
            storedTrip(urbanTrack(listOf(25.0, 35.0, 45.0, 55.0), temp))
        }
        val model = ready(trainer.fit(trips, evidence))

        assertEquals(0.0, model.thermalKwhPer100KmPerDegree, 0.0)
        assertTrue(model.envelope.minSpeedKmh < 30.0 && model.envelope.maxSpeedKmh > 50.0)
        // Town consumption carries the accelerations: above the cruise-only truth, still sane.
        val town = model.predict(40.0, 25.0).value!!
        assertTrue("predicted $town", town in consumption(40.0, 25.0)..40.0)
    }

    /**
     * Stop, accelerate for 15 s, cruise for a minute, brake for 15 s, repeated at each speed,
     * sampled every 5 s as the recorder does. Acceleration pays the kinetic energy of a
     * 1700 kg car at 90 % efficiency; braking returns 60 % of it.
     */
    private fun urbanTrack(speeds: List<Double>, tempCelsius: Double): List<TripSample> {
        val out = ArrayList<TripSample>()
        var atMs = 0L
        fun add(speedKmh: Double, powerKw: Double) {
            out += TripSample(
                atMs, speedKmh.toFloat(), powerKw.toFloat(), 80f, tempCelsius.toFloat(),
                null, null, null, null, null,
            )
            atMs += 5_000L
        }
        for (cruise in speeds) {
            val cruiseMs = cruise / 3.6
            val accel = cruiseMs / 15.0
            val cruisePower = consumption(cruise, tempCelsius) * cruise / 100.0
            // Enough cycles for five kilometres at this speed.
            val cycleKm = cruise * (60.0 + 15.0) / 3600.0
            repeat(kotlin.math.ceil(5.0 / cycleKm).toInt()) {
                repeat(4) { add(0.0, 1.0) }
                for (step in 1..3) {
                    val v = cruiseMs * step / 3.0
                    add(v * 3.6, cruisePower * step / 3.0 + MASS_KG * accel * v / 900.0)
                }
                repeat(12) { add(cruise, cruisePower) }
                for (step in 2 downTo 0) {
                    val v = cruiseMs * step / 3.0
                    add(v * 3.6, -0.6 * MASS_KG * accel * v / 1000.0)
                }
            }
        }
        return out
    }

    /** One steady kilometre per speed and temperature, each after a hole that ends a window. */
    private fun syntheticTrip(): StoredTrip {
        var atMs = 0L
        val samples = buildList {
            repeat(2) { repetition ->
                for (temp in listOf(-5.0, 5.0, 15.0, 25.0, 35.0)) {
                    for (speed in 30..130 step 10) {
                        val shiftedSpeed = speed + repetition * 0.1
                        val intervals = kotlin.math.ceil(3_600.0 / (5.0 * shiftedSpeed)).toInt()
                        // One interval spare, so float rounding cannot leave the window short.
                        repeat(intervals + 2) {
                            add(sample(atMs, shiftedSpeed, temp))
                            atMs += 5_000L
                        }
                        atMs += 600_000L
                    }
                }
            }
        }
        return storedTrip(samples)
    }

    private fun storedTrip(
        samples: List<TripSample>?,
        evidence: BatteryPowerEvidence = this.evidence,
    ) = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = 1L,
            endedAtMs = 2L,
            durationMs = 1L,
            distanceKm = 1.0,
            startSocPercent = 80f,
            endSocPercent = 79f,
            consumedKwh = 1.0,
            regeneratedKwh = 0.0,
            distanceAvailable = true,
            batteryPowerEvidence = evidence,
        ),
        samples = samples,
    )

    private fun sample(atMs: Long, speedKmh: Double, tempCelsius: Double): TripSample {
        val powerKw = consumption(speedKmh, tempCelsius) * speedKmh / 100.0
        return TripSample(
            atMs = atMs,
            speedKmh = speedKmh.toFloat(),
            batteryPowerKw = powerKw.toFloat(),
            socPercent = 80f,
            outsideTempCelsius = tempCelsius.toFloat(),
            cabinTempCelsius = null,
            batteryTempCelsius = null,
            climatePowerOn = null,
            climateAcOn = null,
            climateFanLevel = null,
        )
    }

    private fun consumption(speedKmh: Double, tempCelsius: Double): Double =
        10.0 + 0.0005 * speedKmh * speedKmh +
            0.10 * kotlin.math.abs(tempCelsius - EnergyModel.COMFORT_TEMP_CELSIUS)

    private fun ready(result: EnergyModelTrainingResult): EnergyModel =
        (result as EnergyModelTrainingResult.Ready).model

    companion object {
        private const val HOST_TRAINING_BUDGET_MS = 2_000L
        private const val MASS_KG = 1_700.0

        /** Two passes over five temperatures and eleven speeds. */
        private const val SYNTHETIC_WINDOWS = 110
    }
}
