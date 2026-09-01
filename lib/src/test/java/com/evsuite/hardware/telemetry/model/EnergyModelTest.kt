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

    @Test fun `sparse data and an unvalidated production firmware stay unavailable`() {
        val sparse = syntheticTrip().copy(samples = syntheticTrip().samples?.take(10))

        assertEquals(
            UnavailableReason.INSUFFICIENT_SAMPLES,
            (trainer.fit(listOf(sparse), evidence) as EnergyModelTrainingResult.Unavailable).reason,
        )
        assertEquals(
            UnavailableReason.UNVALIDATED_FIRMWARE,
            (trainer.train(listOf(syntheticTrip()), FirmwareInfo.Gen.SWI68)
                as EnergyModelTrainingResult.Unavailable).reason,
        )
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
        val base = syntheticTrip().samples.orEmpty()
        val samples = List(EnergyModelTrainer.MAX_TRAINING_SAMPLES) { base[it % base.size] }
        val trip = storedTrip(samples)
        lateinit var result: EnergyModelTrainingResult

        val elapsedMs = measureTimeMillis { result = trainer.fit(listOf(trip), evidence) }

        assertNotNull((result as EnergyModelTrainingResult.Ready).model)
        assertEquals(
            EnergyModelTrainer.MAX_TRAINING_SAMPLES,
            (result as EnergyModelTrainingResult.Ready).model.sampleCount,
        )
        assertTrue("fit took ${elapsedMs}ms", elapsedMs < HOST_TRAINING_BUDGET_MS)
    }

    private fun syntheticTrip(): StoredTrip {
        val samples = buildList {
            repeat(2) { repetition ->
                for (temp in listOf(-5.0, 5.0, 15.0, 25.0, 35.0)) {
                    for (speed in 30..130 step 10) {
                        val shiftedSpeed = speed + repetition * 0.1
                        add(sample(shiftedSpeed, temp))
                    }
                }
            }
        }
        return storedTrip(samples)
    }

    private fun storedTrip(samples: List<TripSample>) = StoredTrip(
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

    private fun sample(speedKmh: Double, tempCelsius: Double): TripSample {
        val powerKw = consumption(speedKmh, tempCelsius) * speedKmh / 100.0
        return TripSample(
            atMs = speedKmh.toLong(),
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
    }
}
