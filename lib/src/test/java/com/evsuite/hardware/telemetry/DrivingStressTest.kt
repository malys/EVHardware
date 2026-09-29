package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingStressTest {

    /**
     * Ten minutes at one sample every ten seconds, the charge falling two points. Minutes 0, 3
     * and 6 pull 60 kW, minute 9 regenerates 10 kW, the rest cruise at 20 kW.
     */
    private fun track(
        soc: Float?,
        outside: Float?,
        sign: Float = 1f,
        power: (Int) -> Float? = { i ->
            when (i / 6) {
                0, 3, 6 -> 60f
                9 -> -10f
                else -> 20f
            }
        },
    ) = (0 until 60).map { i ->
        TripSample(
            atMs = i * 10_000L,
            speedKmh = 50f,
            batteryPowerKw = power(i)?.times(sign),
            socPercent = soc?.minus(i / 30f),
            outsideTempCelsius = outside,
            cabinTempCelsius = null,
            batteryTempCelsius = null,
            climatePowerOn = null,
            climateAcOn = null,
            climateFanLevel = null,
        )
    }

    @Test
    fun `hard power in the cold is counted, and cold regeneration summed`() {
        val stress = DrivingStress.of(track(soc = 60f, outside = -5f))!!
        assertEquals(3.0, stress.stressedMinutes, 1e-9)
        assertEquals(50 * 10.0 / 3600.0, stress.coldRegeneratedKwh!!, 1e-9)
    }

    @Test
    fun `the trip orients its own power sign`() {
        assertEquals(DrivingStress.of(track(soc = 60f, outside = -5f)), DrivingStress.of(track(60f, -5f, sign = -1f)))
    }

    @Test
    fun `a warm drive at a good charge is a measured zero`() {
        assertEquals(DrivingStress(0.0, 0.0), DrivingStress.of(track(soc = 60f, outside = 15f)))
    }

    @Test
    fun `low charge alone is enough, and an unread temperature leaves the regeneration unknown`() {
        val stress = DrivingStress.of(track(soc = 19f, outside = null))!!
        assertEquals(3.0, stress.stressedMinutes, 1e-9)
        assertNull(stress.coldRegeneratedKwh)
    }

    @Test
    fun `unknown is never zero`() {
        // Hard moments at a good charge with no temperature: they cannot be decided.
        assertNull(DrivingStress.of(track(soc = 60f, outside = null)))
        assertNull(DrivingStress.of(track(soc = 60f, outside = -5f) { null }))
        assertNull(DrivingStress.of(track(soc = null, outside = -5f)))
        // A third of the power unread.
        assertNull(DrivingStress.of(track(soc = 60f, outside = -5f) { i -> if (i % 3 == 0) null else 20f }))
    }
}
