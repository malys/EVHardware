package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeDurationTest {

    /** A watched charge from [from] to [to] %, one step per point, [minutesOf] minutes for each. */
    private fun charge(from: Int, to: Int, outside: Double? = 15.0, minutesOf: (Int) -> Double) = ChargeEnergy(
        session = BatteryChargeSession(0L, 1L, from.toDouble(), to.toDouble(), 0.0, 0.0, outside),
        watched = true,
        maxEntryGapMs = 0L,
        packDeltaKwh = null,
        meanPowerKw = null,
        counterBehaviour = ChargeCounterBehaviour.RESET,
        consumedDeltaKwh = null,
        regeneratedDeltaKwh = null,
        movedKm = 0.0,
        chargingStatuses = emptyList(),
        steps = (from until to).map {
            ChargePowerStep(it.toDouble(), it + 1.0, (minutesOf(it) * 60_000).toLong(), 7.0)
        },
        minOutsideTempCelsius = outside,
        maxOutsideTempCelsius = outside,
    )

    /** Three minutes a point below 80 %, six above: the taper. */
    private val tapered = { soc: Int -> if (soc < 80) 3.0 else 6.0 }

    @Test
    fun `duration sums the bands on the path, and the last stretch is its own figure`() {
        val history = listOf(charge(20, 100, minutesOf = tapered))
        val estimate = (ChargeDuration.estimate(history, 50.0, 100, null) as ChargeDurationResult.Ready).estimate
        assertEquals(30 * 3.0 + 20 * 6.0, estimate.minutes, 1e-6)
        assertEquals(120.0, estimate.lastStretchMinutes!!, 1e-6)
        assertFalse(estimate.similarTemperature)
    }

    @Test
    fun `the range is the fastest and the slowest past charge`() {
        val history = listOf(
            charge(40, 80) { 2.0 },
            charge(40, 80) { 3.0 },
            charge(40, 80) { 5.0 },
        )
        val estimate = (ChargeDuration.estimate(history, 60.0, 80, null) as ChargeDurationResult.Ready).estimate
        assertEquals(60.0, estimate.minutes, 1e-6)
        assertEquals(40.0, estimate.lowMinutes, 1e-6)
        assertEquals(100.0, estimate.highMinutes, 1e-6)
        assertNull(estimate.lastStretchMinutes)
    }

    @Test
    fun `a band nobody watched is refused, never extrapolated`() {
        val refused = ChargeDuration.estimate(listOf(charge(20, 80, minutesOf = tapered)), 50.0, 100, null)
        assertEquals(ChargeDurationResult.Refused(ChargeDurationRefusal.MISSING_BAND, 80), refused)
        assertEquals(
            ChargeDurationResult.Refused(ChargeDurationRefusal.NO_WATCHED_CHARGE),
            ChargeDuration.estimate(emptyList(), 50.0, 100, null),
        )
        assertEquals(
            ChargeDurationResult.Refused(ChargeDurationRefusal.AT_TARGET),
            ChargeDuration.estimate(emptyList(), 80.0, 80, null),
        )
    }

    @Test
    fun `a similar outside temperature is preferred, and all charges are the fallback`() {
        val history = listOf(
            charge(40, 80, outside = 0.0) { 5.0 },
            charge(40, 80, outside = 20.0) { 3.0 },
        )
        val cold = (ChargeDuration.estimate(history, 60.0, 80, 2.0) as ChargeDurationResult.Ready).estimate
        assertTrue(cold.similarTemperature)
        assertEquals(100.0, cold.minutes, 1e-6)
        val hot = (ChargeDuration.estimate(history, 60.0, 80, 35.0) as ChargeDurationResult.Ready).estimate
        assertFalse(hot.similarTemperature)
        assertEquals(20 * 5.0, hot.minutes, 1e-6) // the median of two is the upper one
    }

    @Test
    fun `the curve check needs three past charges and flags only slower than all of them`() {
        val two = listOf(charge(80, 90) { 6.0 }, charge(80, 90) { 7.0 })
        assertNull(ChargeDuration.curveCheck(two, 80, 20.0))
        val three = two + charge(80, 90) { 8.0 }
        assertTrue(ChargeDuration.curveCheck(three, 80, 9.0)!!.slower)
        val usual = ChargeDuration.curveCheck(three, 80, 7.5)!!
        assertFalse(usual.slower)
        assertEquals(6.0, usual.usualLowMinutesPerPoint, 1e-9)
        assertEquals(3, usual.pastCharges)
    }
}
