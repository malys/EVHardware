package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripDetectorTest {
    @Test fun `sustained motion starts once after the debounce window`() {
        val detector = TripDetector()

        assertEquals(TripDetector.State.ARMED, detector.add(snapshot(0L, 10f)).state)
        assertNull(detector.add(snapshot(4_999L, 10f)).event)
        val started = detector.add(snapshot(5_000L, 10f))

        assertEquals(TripDetector.State.RECORDING, started.state)
        assertEquals(TripDetector.Event.START, started.event)
        assertNull(detector.add(snapshot(6_000L, 10f)).event)
    }

    @Test fun `a ninety second traffic stop does not split a trip`() {
        val detector = recordingDetector()

        assertEquals(TripDetector.State.ENDING, detector.add(snapshot(6_000L, 0f, parked = false)).state)
        assertNull(detector.add(snapshot(96_000L, 0f, parked = false)).event)
        val movingAgain = detector.add(snapshot(97_000L, 20f, parked = false))

        assertEquals(TripDetector.State.RECORDING, movingAgain.state)
        assertNull(movingAgain.event)
    }

    @Test fun `a genuine confirmed end closes the trip`() {
        val detector = recordingDetector()
        detector.add(snapshot(6_000L, 0f, parked = false))

        val stopped = detector.add(snapshot(126_000L, 0f, parked = true))

        assertEquals(TripDetector.State.IDLE, stopped.state)
        assertEquals(TripDetector.Event.STOP, stopped.event)
    }

    @Test fun `standstill alone closes when confirmation signals do not exist`() {
        val detector = recordingDetector()
        detector.add(snapshot(6_000L, 0f))

        val stopped = detector.add(snapshot(126_000L, 0f))

        assertEquals(TripDetector.Event.STOP, stopped.event)
    }

    @Test fun `available confirmation must become true before ending`() {
        val detector = recordingDetector()
        detector.add(snapshot(6_000L, 0f, parked = false, chargePortConnected = false))

        val unconfirmed = detector.add(
            snapshot(126_000L, 0f, parked = false, chargePortConnected = false)
        )
        val charging = detector.add(
            snapshot(127_000L, 0f, parked = false, chargePortConnected = true)
        )

        assertEquals(TripDetector.State.ENDING, unconfirmed.state)
        assertNull(unconfirmed.event)
        assertEquals(TripDetector.Event.STOP, charging.event)
    }

    @Test fun `null speed mid trip discards stop evidence without ending`() {
        val detector = recordingDetector()
        detector.add(snapshot(6_000L, 0f, parked = true))
        detector.add(snapshot(100_000L, null, parked = true))

        val afterOldDeadline = detector.add(snapshot(130_000L, 0f, parked = true))

        assertEquals(TripDetector.State.ENDING, afterOldDeadline.state)
        assertNull(afterOldDeadline.event)
    }

    @Test fun `bounce around start threshold never flaps into recording`() {
        val detector = TripDetector()

        detector.add(snapshot(0L, 5.1f))
        assertEquals(TripDetector.State.IDLE, detector.add(snapshot(2_000L, 4.9f)).state)
        detector.add(snapshot(3_000L, 5.1f))
        assertEquals(TripDetector.State.IDLE, detector.add(snapshot(7_999L, 4.9f)).state)
    }

    @Test fun `missing speed firmware cannot auto start or auto stop`() {
        val detector = TripDetector()

        repeat(10) { index ->
            val result = detector.add(snapshot(index * 10_000L, null, parked = true))
            assertEquals(TripDetector.State.IDLE, result.state)
            assertNull(result.event)
        }
        detector.markRecording()
        val running = detector.add(snapshot(200_000L, null, parked = true))
        assertEquals(TripDetector.State.RECORDING, running.state)
        assertNull(running.event)
    }

    @Test fun `moving sample never ends even when parked is reported`() {
        val detector = recordingDetector()
        detector.add(snapshot(6_000L, 0f, parked = true))

        val moving = detector.add(snapshot(200_000L, 2f, parked = true))

        assertEquals(TripDetector.State.RECORDING, moving.state)
        assertNull(moving.event)
    }

    private fun recordingDetector() = TripDetector().also { detector ->
        detector.add(snapshot(0L, 10f))
        assertEquals(TripDetector.Event.START, detector.add(snapshot(5_000L, 10f)).event)
    }

    private fun snapshot(
        atMs: Long,
        speedKmh: Float?,
        parked: Boolean? = null,
        chargePortConnected: Boolean? = null,
    ) = EnergySnapshot(
        timestampMs = atMs,
        firmware = FirmwareInfo.Gen.SWI68,
        socPercent = 80f,
        rangeKm = null,
        speedKmh = speedKmh,
        batteryPowerKw = 10f,
        outsideTempCelsius = null,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        batteryEnergyKwh = null,
        batteryCapacityKwh = null,
        odometerKm = null,
        chargePortConnected = chargePortConnected,
        chargingStatus = null,
        parked = parked,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
