package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEvidenceTest {

    @Test fun `a signal the vehicle never answers has nothing, not zero`() {
        val recorder = TelemetryEvidenceRecorder()
        repeat(10) { recorder.record(snapshot(at = it * 1_000L, power = null)) }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(0, power.samples)
        assertEquals(10, power.nulls)
        assertNull(power.min)
        assertNull(power.max)
        assertNull(power.mean)
        assertTrue(!power.available)
    }

    @Test fun `counts, range and mean come from the samples that existed`() {
        val recorder = TelemetryEvidenceRecorder()
        listOf(10f, null, 20f, 30f).forEachIndexed { index, value ->
            recorder.record(snapshot(at = index * 1_000L, power = value))
        }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(3, power.samples)
        assertEquals(1, power.nulls)
        assertEquals(10.0, power.min!!, 1e-9)
        assertEquals(30.0, power.max!!, 1e-9)
        assertEquals(20.0, power.mean!!, 1e-9)
    }

    @Test fun `the sign split is what proves a power convention`() {
        val recorder = TelemetryEvidenceRecorder()
        listOf(42f, -28f, 0f, 15f).forEachIndexed { index, value ->
            recorder.record(snapshot(at = index * 1_000L, power = value))
        }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(2, power.positive)
        assertEquals(1, power.negative)
        assertEquals(1, power.zero)
    }

    @Test fun `a flag carries no sign split`() {
        val recorder = TelemetryEvidenceRecorder()
        recorder.record(snapshot(at = 0L, power = null, parked = true))

        val parked = recorder.evidence().single { it.signal == TelemetryEvidenceRecorder.PARKED }
        assertEquals(SignalKind.BOOLEAN, parked.kind)
        assertNull(parked.positive)
        assertNull(parked.negative)
        assertEquals(1, parked.samples)
    }

    @Test fun `the update period follows value changes, not sampling ticks`() {
        val recorder = TelemetryEvidenceRecorder()
        // One second between reads, five seconds between the values the vehicle publishes.
        repeat(30) { tick ->
            val value = (tick / 5) * 10f
            recorder.record(snapshot(at = tick * 1_000L, power = value))
        }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(5, power.changes)
        assertEquals(5_000L, power.updatePeriodMedianMs)
        assertEquals(5_000L, power.updatePeriodMinMs)
        assertEquals(5_000L, power.updatePeriodMaxMs)
    }

    @Test fun `a constant signal has no update period rather than a zero one`() {
        val recorder = TelemetryEvidenceRecorder()
        repeat(10) { recorder.record(snapshot(at = it * 1_000L, power = 12f)) }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(0, power.changes)
        assertNull(power.updatePeriodMedianMs)
    }

    @Test fun `an irregular sampler still reports the periods it observed`() {
        val recorder = TelemetryEvidenceRecorder()
        listOf(0L to 1f, 1_000L to 2f, 9_000L to 3f, 11_000L to 4f).forEach { (at, value) ->
            recorder.record(snapshot(at = at, power = value))
        }

        val power = recorder.evidence().single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(3, power.changes)
        assertEquals(1_000L, power.updatePeriodMinMs)
        assertEquals(8_000L, power.updatePeriodMaxMs)
        assertEquals(2_000L, power.updatePeriodMedianMs)
    }

    @Test fun `the capture round-trips through its schema`() {
        val recorder = TelemetryEvidenceRecorder()
        repeat(4) { recorder.record(snapshot(at = it * 1_000L, power = it.toFloat())) }

        val capture = recorder.capture()
        val restored = TelemetryEvidenceFormat.fromJson(TelemetryEvidenceFormat.toJson(capture))
        assertNotNull(restored)
        assertEquals(capture.snapshots, restored!!.snapshots)
        assertEquals(capture.firmware, restored.firmware)
        assertEquals(capture.signals.size, restored.signals.size)
        val power = restored.signals.single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertEquals(4, power.samples)
        assertEquals(3.0, power.max!!, 1e-9)
    }

    @Test fun `an absent statistic stays absent through the schema, never a zero`() {
        val recorder = TelemetryEvidenceRecorder()
        recorder.record(snapshot(at = 0L, power = null))

        val json = TelemetryEvidenceFormat.toJson(recorder.capture())
        val restored = TelemetryEvidenceFormat.fromJson(json)!!
        val power = restored.signals.single {
            it.signal == TelemetryEvidenceRecorder.BATTERY_POWER_KW
        }
        assertNull(power.min)
        assertNull(power.mean)
    }

    @Test fun `a capture written by a different schema version is refused`() {
        val json = TelemetryEvidenceFormat.toJson(
            EvidenceCapture(
                schemaVersion = 99, firmware = "SWI68", startedAtMs = 0L, endedAtMs = 1L,
                snapshots = 1, signals = emptyList(),
            )
        )
        assertNull(TelemetryEvidenceFormat.fromJson(json))
    }

    @Test fun `a probed candidate property is recorded beside the snapshot signals`() {
        val recorder = TelemetryEvidenceRecorder()
        recorder.record(snapshot(at = 0L, power = 1f))
        recorder.record("candidate.hvacPowerKw", 0.8, 0L)
        recorder.record("candidate.hvacPowerKw", null as Double?, 1_000L)

        val candidate = recorder.evidence().single { it.signal == "candidate.hvacPowerKw" }
        assertEquals(1, candidate.samples)
        assertEquals(1, candidate.nulls)
    }

    @Test fun `the markdown rendering names every signal and dashes what is missing`() {
        val recorder = TelemetryEvidenceRecorder()
        recorder.record(snapshot(at = 0L, power = null))

        val markdown = TelemetryEvidenceFormat.toMarkdown(recorder.capture())
        assertTrue(markdown.contains("`${TelemetryEvidenceRecorder.BATTERY_POWER_KW}`"))
        assertTrue(markdown.contains("| — | — | — |"))
    }

    private fun snapshot(at: Long, power: Float?, parked: Boolean? = null) = EnergySnapshot(
        timestampMs = at, firmware = FirmwareInfo.Gen.SWI68, socPercent = null,
        rangeKm = null, speedKmh = null, batteryPowerKw = power,
        outsideTempCelsius = null, cabinTempCelsius = null, batteryTempCelsius = null,
        batteryEnergyKwh = null, batteryCapacityKwh = null, odometerKm = null,
        chargePortConnected = null, chargingStatus = null, parked = parked,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
