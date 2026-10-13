package com.evsuite.hardware.saic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The charge limit read refuses what the car could never have been set to. */
class SaicChargeLimitTest {

    @Test fun `the code SWI68 answers is not a percent`() {
        assertNull(SaicCharging.chargeLimitOrNull(5))
    }

    @Test fun `the range the car accepts reads through, ends included`() {
        assertEquals(40, SaicCharging.chargeLimitOrNull(40))
        assertEquals(80, SaicCharging.chargeLimitOrNull(80))
        assertEquals(100, SaicCharging.chargeLimitOrNull(100))
    }

    @Test fun `outside it, and unread, is unknown`() {
        assertNull(SaicCharging.chargeLimitOrNull(39))
        assertNull(SaicCharging.chargeLimitOrNull(101))
        assertNull(SaicCharging.chargeLimitOrNull(null))
    }
}
