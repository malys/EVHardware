package com.evsuite.hardware.saic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The consumption history arrives as the JSON array the charging service persists, so what is
 * tested here is that format rather than this app's idea of it.
 */
class SaicChargingHistoryTest {

    @Test fun `the car's own array is read oldest first`() {
        assertEquals(
            listOf(15.2f, 16.0f, 14.8f),
            SaicCharging.parseHistory("[15.2,16.0,14.8]"),
        )
    }

    @Test fun `a car that has never counted answers nothing, not zero`() {
        assertEquals(emptyList<Float>(), SaicCharging.parseHistory(""))
        assertEquals(emptyList<Float>(), SaicCharging.parseHistory(null))
        assertEquals(emptyList<Float>(), SaicCharging.parseHistory("[]"))
    }

    @Test fun `a reading the service never wrote as a number is dropped, not guessed`() {
        assertEquals(listOf(15.2f, 14.8f), SaicCharging.parseHistory("[15.2,null,14.8]"))
    }
}
