package com.evsuite.hardware

import com.evsuite.hardware.CustomDrive.Family
import com.evsuite.hardware.CustomDrive.Setting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomDriveTest {

    private fun codes(setting: Setting, family: Family) =
        CustomDrive.INDICES.map { CustomDrive.code(setting, it, family) }

    @Test fun `VSM scale matches the RI-006 read and write codes`() {
        assertEquals(listOf(1, 2, 3), codes(Setting.POWER, Family.VSM))
        assertEquals(listOf(1, 2, 3), codes(Setting.STEERING, Family.VSM))
        assertEquals(listOf(1, 0, 2), codes(Setting.PEDAL, Family.VSM))
    }

    @Test fun `pedal Normal is zero on every family`() {
        Family.values().forEach { assertEquals(0, CustomDrive.code(Setting.PEDAL, 1, it)) }
    }

    @Test fun `A9 powertrain reuses the drive-mode scale`() {
        assertEquals(listOf(2, 3, 4), codes(Setting.POWER, Family.A9))
        assertEquals(listOf(1, 2, 3), codes(Setting.STEERING, Family.A9))
    }

    @Test fun `every code reads back to its index`() {
        Family.values().forEach { family ->
            Setting.values().forEach { setting ->
                CustomDrive.INDICES.forEach { i ->
                    assertEquals(i, CustomDrive.index(setting, CustomDrive.code(setting, i, family)!!, family))
                }
            }
        }
    }

    @Test fun `out of range is refused both ways`() {
        assertNull(CustomDrive.code(Setting.POWER, 3, Family.VSM))
        assertNull(CustomDrive.code(Setting.PEDAL, -1, Family.A9))
        assertNull(CustomDrive.index(Setting.POWER, 0, Family.VSM))
        assertNull(CustomDrive.index(Setting.POWER, 1, Family.A9))
        assertNull(CustomDrive.index(Setting.STEERING, -1, Family.VSM))
    }

    @Test fun `only VSM generations have a route`() {
        assertEquals(Family.VSM, CustomDrive.familyOf(FirmwareInfo.Gen.SWI68))
        assertEquals(Family.VSM, CustomDrive.familyOf(FirmwareInfo.Gen.SWI165))
        assertEquals(Family.A9, CustomDrive.familyOf(FirmwareInfo.Gen.SWI69))
        assertEquals(Family.A9, CustomDrive.familyOf(FirmwareInfo.Gen.SWI131))
        assertEquals(Family.A9, CustomDrive.familyOf(FirmwareInfo.Gen.SWI132))
        assertNull(CustomDrive.familyOf(FirmwareInfo.Gen.SWI133))
        assertNull(CustomDrive.familyOf(FirmwareInfo.Gen.UNKNOWN))
    }

    @Test fun `method names follow the family`() {
        assertEquals("setBrakePedalLevel", CustomDrive.methodName(Setting.PEDAL, Family.VSM, write = true))
        assertEquals("getDrivingPowerTrainMode", CustomDrive.methodName(Setting.POWER, Family.A9, write = false))
    }
}
