package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    @Test fun `a missing reading is unavailable with a reason, not a zero`() {
        val value = Provenanced.measured<Float>(null)
        assertEquals(Provenance.UNAVAILABLE, value.provenance)
        assertEquals(UnavailableReason.SIGNAL_ABSENT, value.reason)
        assertNull(value.value)
    }

    @Test fun `an unsupported firmware says so rather than reporting an absent signal`() {
        val value = Provenanced.measured<Float>(null, UnavailableReason.UNSUPPORTED_FIRMWARE)
        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, value.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an estimate cannot be built without a band`() {
        Provenanced(42.0, Provenance.ESTIMATED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a band must be finite and non-negative`() {
        Provenanced.estimated(42.0, -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a measurement carries no band — the vehicle publishes no error bars`() {
        Provenanced(42.0, Provenance.MEASURED, uncertainty = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unavailable value carries no number`() {
        Provenanced(42.0, Provenance.UNAVAILABLE, reason = UnavailableReason.SIGNAL_ABSENT)
    }

    @Test fun `an estimate exposes its band edges`() {
        val value = Provenanced.estimated(14.2, 1.6)
        assertEquals(12.6, value.bandLow!!, 1e-9)
        assertEquals(15.8, value.bandHigh!!, 1e-9)
    }

    @Test fun `a derivation over an unavailable input is unavailable, never partly computed`() {
        val power = Provenanced.measured(18.0)
        val speed = Provenanced.measured<Double>(null)
        val consumption = Provenanced.derive(power, speed) { p, v -> p * 100.0 / v }
        assertEquals(Provenance.UNAVAILABLE, consumption.provenance)
        assertEquals(UnavailableReason.SIGNAL_ABSENT, consumption.reason)
    }

    @Test fun `a derivation carries the first missing input's reason`() {
        val power = Provenanced.measured<Double>(null, UnavailableReason.UNSUPPORTED_FIRMWARE)
        val speed = Provenanced.measured(90.0)
        val consumption = Provenanced.derive(power, speed) { p, v -> p * 100.0 / v }
        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, consumption.reason)
    }

    @Test fun `a derivation over available inputs is derived, not measured`() {
        val power = Provenanced.measured(18.0)
        val speed = Provenanced.measured(90.0)
        val consumption = Provenanced.derive(power, speed) { p, v -> p * 100.0 / v }
        assertEquals(Provenance.DERIVED, consumption.provenance)
        assertEquals(20.0, consumption.value!!, 1e-9)
        assertNull(consumption.uncertainty)
    }

    @Test fun `a transform that declines to produce a value yields unavailable`() {
        val speed = Provenanced.measured(0.0)
        val consumption = Provenanced.derive(speed) { v -> if (v < 5.0) null else 100.0 / v }
        assertEquals(Provenance.UNAVAILABLE, consumption.provenance)
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, consumption.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriving from an estimate is refused rather than silently dropping its band`() {
        val range = Provenanced.estimated(280.0, 25.0)
        Provenanced.derive(range) { it / 2.0 }
    }

    @Test fun `availability is readable without unwrapping the value`() {
        assertTrue(Provenanced.measured(1.0).isAvailable)
        assertTrue(!Provenanced.unavailable<Double>(UnavailableReason.MODEL_NOT_TRAINED).isAvailable)
    }
}
