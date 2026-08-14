package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class FirmwareWritePolicyTest {

    @Test
    fun `known generations are recognized`() {
        FirmwareInfo.Gen.values()
            .filterNot { it == FirmwareInfo.Gen.UNKNOWN }
            .forEach { generation ->
                assertEquals(generation, FirmwareInfo.generationOf("${generation.name}-build"))
            }
    }

    @Test
    fun `missing and unrecognized firmware stay unknown`() {
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.generationOf(null))
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.generationOf(""))
        assertEquals(FirmwareInfo.Gen.UNKNOWN, FirmwareInfo.generationOf("SWI999-test"))
    }
}
