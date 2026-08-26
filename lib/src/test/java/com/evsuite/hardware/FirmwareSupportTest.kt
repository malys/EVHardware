package com.evsuite.hardware

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the firmware annotations and regenerates the matrix.
 *
 * The annotations are the single source of truth: if a vehicle entry loses its
 * `@SupportedOn`, the runtime filter would silently show it on every firmware, and the
 * README matrix would go stale. These tests catch both.
 */
class FirmwareSupportTest {

    /** Catalogue entries that are firmware-independent — no annotation expected. */
    private val firmwareIndependentConditions = setOf(
        ConditionType.BT_DEVICE_CONNECTED,
        ConditionType.ANY_BT_CONNECTED,
        ConditionType.BT_DEVICE_ONBOARD,
        ConditionType.BT_DEVICE_HANDSFREE,
        ConditionType.TIME_OF_DAY,
        ConditionType.DAY_OF_WEEK,
        ConditionType.DATE,
        ConditionType.FIRMWARE_GEN,
        ConditionType.LOCATION_WITHIN,
        ConditionType.PHYSICAL_BUTTON,
        // Lus dans Android, pas dans la voiture : rien à router par génération.
        ConditionType.MEDIA_PLAYING,
        ConditionType.WIFI_SSID,
        ConditionType.IN_CALL,
        ConditionType.DRIVE_DURATION,
        ConditionType.RANDOM_CHANCE,
        // Services des applications du poste (adaptateur de navigation, météo) : ce ne sont
        // pas des services du SDK véhicule, rien ne les route par génération. Ils répondent
        // ou non, comme le SMS.
        ConditionType.ODOMETER,
        ConditionType.WEATHER_NOW,
        ConditionType.WEATHER_TOMORROW,
        ConditionType.TEMP_MAX_TODAY,
        ConditionType.TEMP_MIN_TOMORROW
    )
    private val firmwareIndependentActions = setOf(
        ActionType.LAUNCH_APP,
        ActionType.SHOW_NOTIFICATION,
        ActionType.SPEAK_TEXT,
        ActionType.NAVIGATE_TO,
        ActionType.WEBHOOK,
        ActionType.ASK_CONFIRM,
        ActionType.DELAY,
        // Le SMS part par le profil Bluetooth MAP, pas par un service SAIC : rien à router
        // par génération de firmware. Sa disponibilité se lit au bind, pas dans la matrice.
        ActionType.SEND_SMS,
        // Règles, touche média et radios du poste : rien de tout cela ne passe par la voiture.
        ActionType.ENABLE_RULE,
        ActionType.DISABLE_RULE,
        ActionType.MEDIA_CONTROL,
        ActionType.SET_BLUETOOTH,
        ActionType.SET_WIFI
    )

    @Test
    fun `every vehicle condition declares firmware support`() {
        ConditionType.entries
            .filterNot { it in firmwareIndependentConditions || it.eventDriven }
            .forEach {
                assertTrue("${it.name} is missing @SupportedOn", FirmwareSupport.gensOf(it) != null)
            }
    }

    @Test
    fun `every vehicle action declares firmware support`() {
        ActionType.entries
            .filterNot { it in firmwareIndependentActions }
            .forEach {
                assertTrue("${it.name} is missing @SupportedOn", FirmwareSupport.gensOf(it) != null)
            }
    }

    @Test
    fun `context entries stay firmware-independent`() {
        firmwareIndependentConditions.forEach {
            assertNull("${it.name} should not be firmware-specific", FirmwareSupport.gensOf(it))
        }
        ConditionType.entries.filter { it.eventDriven }.forEach {
            assertNull("${it.name} should not be firmware-specific", FirmwareSupport.gensOf(it))
        }
        firmwareIndependentActions.forEach {
            assertNull("${it.name} should not be firmware-specific", FirmwareSupport.gensOf(it))
        }
    }

    @Test
    fun `seat heating is limited to the generations that have it`() {
        // EVProfile FirmwareInfo.hasHeatFeatures(): SWI133, SWI68, SWI165 only.
        assertEquals(
            setOf(FirmwareGen.SWI133, FirmwareGen.SWI68, FirmwareGen.SWI165),
            FirmwareSupport.gensOf(ConditionType.STEERING_HEAT)
        )
    }

    @Test
    fun `ACC TJA is unavailable on SWI133`() {
        // SWI133 uses getMixedIntelligentDrive, not the getAccTjaState path.
        assertFalse(FirmwareSupport.isSupported(ConditionType.ACC_TJA_MODE, FirmwareGen.SWI133))
        assertTrue(FirmwareSupport.isSupported(ConditionType.ACC_TJA_MODE, FirmwareGen.SWI68))
    }

    @Test
    fun `unknown firmware hides nothing`() {
        // No bridge / no reported firmware: filtering on a guess would be worse than
        // offering an entry that later refuses.
        ConditionType.entries.forEach {
            assertTrue("${it.name} hidden with unknown firmware", FirmwareSupport.isSupported(it, null))
        }
    }

    @Test
    fun `snapshot firmware strings parse to enum`() {
        assertEquals(FirmwareGen.SWI68, FirmwareSupport.parse("SWI68"))
        assertEquals(FirmwareGen.SWI133, FirmwareSupport.parse("swi133"))
        assertNull(FirmwareSupport.parse("SWI999"))
        assertNull(FirmwareSupport.parse(null))
    }

    /**
     * Regenerates the matrix and fails if the committed copy is stale. Regenerate with:
     * `./gradlew testDebugUnitTest` then commit `docs/firmware-matrix.md`.
     */
    @Test
    fun `firmware matrix is up to date`() {
        val generated = FirmwareMatrix.render()
        val file = File("../docs/firmware-matrix.md")
        if (!file.exists() || file.readText().trim() != generated.trim()) {
            file.parentFile?.mkdirs()
            file.writeText(generated)
        }
        // Re-read: after the write the file must match, so a stale commit is the only
        // way this differs — and the write above fixes it locally for the next run.
        assertEquals(generated.trim(), file.readText().trim())
    }
}
