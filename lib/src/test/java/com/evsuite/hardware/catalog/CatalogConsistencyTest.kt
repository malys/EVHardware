package com.evsuite.hardware.catalog

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.ValueKind
import com.evsuite.hardware.saic.SaicRadio
import com.evsuite.hardware.saic.SaicVehicleControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fous sur le catalogue. Les erreurs visées ne cassent pas la compilation : elles
 * produisent une entrée d'interface qui ne fait rien, ou pire, une écriture véhicule qui
 * contourne le verrou de vitesse.
 */
class CatalogConsistencyTest {

    @Suppress("DEPRECATION")
    @Test
    fun `call is one public action accepting numbers or contacts`() {
        val publicActions = ActionType.byGroup().values.flatten()

        assertEquals(ValueKind.CONTACT, ActionType.CALL_NUMBER.spec.kind)
        assertTrue(ActionType.CALL_NUMBER in publicActions)
        assertFalse(ActionType.CALL_CONTACT in publicActions)
    }

    @Test
    fun `la destination et le point utilisent des controles distincts`() {
        // Un point a un rayon, une destination n'en a pas : deux ValueKind, sinon l'éditeur
        // demanderait un rayon pour une adresse.
        assertEquals(ValueKind.DESTINATION, ActionType.NAVIGATE_TO.spec.kind)
        assertEquals(ValueKind.LOCATION, ConditionType.LOCATION_WITHIN.spec.kind)
        assertEquals(0, ActionType.NAVIGATE_TO.spec.max)
    }

    @Test
    fun `le sms porte un destinataire et un message`() {
        // Le destinataire est un numéro (ou un contact du répertoire PBAP) et le message est
        // du texte libre : un seul contrôle ne pourrait pas porter les deux.
        assertEquals(ValueKind.SMS, ActionType.SEND_SMS.spec.kind)
        assertTrue(ActionType.SEND_SMS.spec.hintRes != 0)
        assertFalse("le SMS n'écrit rien dans le véhicule", ActionType.SEND_SMS.gated)
    }

    @Test
    fun `la demande de confirmation porte sa question et son delai`() {
        // Le texte EST la question posée au conducteur ; un exemple est indispensable, la
        // formulation par défaut d'un champ vide n'apprend rien. Le nombre est l'attente en
        // secondes, bornée pour qu'une question reste lisible sans bloquer le cycle.
        assertEquals(ValueKind.CONFIRM, ActionType.ASK_CONFIRM.spec.kind)
        assertTrue(ActionType.ASK_CONFIRM.spec.hintRes != 0)
        assertTrue(ActionType.ASK_CONFIRM.spec.min > 0)
        assertTrue(ActionType.ASK_CONFIRM.spec.max > ActionType.ASK_CONFIRM.spec.min)
        // Le défaut des règles enregistrées sans délai doit rester dans les bornes offertes.
        assertTrue(ActionType.ASK_CONFIRM_DEFAULT_SECONDS in
            ActionType.ASK_CONFIRM.spec.min..ActionType.ASK_CONFIRM.spec.max)
        assertFalse("la question ne touche pas le véhicule", ActionType.ASK_CONFIRM.gated)
    }

    @Test
    fun `toute action vehicule cible une action du pont`() {
        // bridgeAction=null est réservé aux actions locales (lancer une app, notifier).
        // Une action véhicule sans cible serait silencieusement ignorée à l'exécution.
        val localOnly = setOf(
            ActionType.LAUNCH_APP, ActionType.SHOW_NOTIFICATION, ActionType.SPEAK_TEXT,
            ActionType.NAVIGATE_TO, ActionType.WEBHOOK, ActionType.ASK_CONFIRM,
            ActionType.DELAY, ActionType.SEND_SMS,
            ActionType.ENABLE_RULE, ActionType.DISABLE_RULE, ActionType.MEDIA_CONTROL,
            ActionType.SET_BLUETOOTH, ActionType.SET_WIFI
        )

        ActionType.entries.filterNot { it in localOnly }.forEach { type ->
            assertNotNull("${type.name} n'a pas de bridgeAction", type.bridgeAction)
        }
    }

    @Test
    fun `les actions locales ne passent pas par le pont`() {
        listOf(
            ActionType.LAUNCH_APP, ActionType.SHOW_NOTIFICATION, ActionType.SPEAK_TEXT,
            ActionType.NAVIGATE_TO, ActionType.WEBHOOK, ActionType.ASK_CONFIRM,
            ActionType.DELAY, ActionType.SEND_SMS,
            ActionType.ENABLE_RULE, ActionType.DISABLE_RULE, ActionType.MEDIA_CONTROL,
            ActionType.SET_BLUETOOTH, ActionType.SET_WIFI
        ).forEach { type ->
            assertTrue(
                "${type.name} ne touche pas le véhicule et ne doit pas avoir de bridgeAction",
                type.bridgeAction == null
            )
        }
    }

    @Test
    fun `les ecritures de comportement routier sont marquees gated`() {
        // Le marquage ne remplace PAS le verrou côté EVProfile, qui reste l'autorité.
        // Il conditionne l'avertissement affiché à l'utilisateur au moment du choix :
        // une action gated non marquée se présenterait comme applicable en roulant.
        val mustBeGated = setOf(
            ActionType.APPLY_PROFILE,
            ActionType.SET_DRIVE_MODE, ActionType.SET_REGEN_LEVEL, ActionType.SET_ONE_PEDAL,
            ActionType.SET_ENERGY_SAVING,
            ActionType.SET_AEB_ENABLED, ActionType.SET_AEB_MODE, ActionType.SET_AEB_SENSITIVITY,
            ActionType.SET_ELK_MODE, ActionType.SET_ELK_SENSITIVITY,
            ActionType.SET_ACC_TJA_MODE, ActionType.SET_LIMITER_MODE,
            ActionType.SET_TSR, ActionType.SET_OVERSPEED_ALARM,
            ActionType.SET_SPEED_LIMIT_TONE, ActionType.SET_SOUND_WARNING
        )

        mustBeGated.forEach { type ->
            assertTrue("${type.name} modifie le comportement routier et doit être gated", type.gated)
        }
    }

    @Test
    fun `couper le vehicule n est pas automatisable`() {
        // Une règle ne doit jamais pouvoir éteindre la voiture : c'est irréversible pour
        // le conducteur, et ça reste réservé à un geste humain dans EVProfile.
        assertFalse(
            "aucune action du catalogue ne doit couper le véhicule",
            ActionType.entries.any {
                it.bridgeAction?.contains("POWER_OFF", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `toute condition vehicule lit une cle de l instantane`() {
        // Les conditions calculées localement (heure, jour, Bluetooth) n'ont pas de clé ;
        // toutes les autres en ont besoin, sinon elles seraient toujours indisponibles.
        val localOnly = setOf(
            ConditionType.BT_DEVICE_CONNECTED,
            ConditionType.ANY_BT_CONNECTED,
            ConditionType.BT_DEVICE_ONBOARD,
            ConditionType.BT_DEVICE_HANDSFREE,
            ConditionType.TIME_OF_DAY,
            ConditionType.DAY_OF_WEEK,
            ConditionType.DATE,
            ConditionType.LOCATION_WITHIN,
            // Tiré au sort à l'évaluation : il n'y a rien à lire.
            ConditionType.RANDOM_CHANCE
        )

        ConditionType.entries.filterNot { it in localOnly }.forEach { type ->
            assertNotNull("${type.name} n'a pas de clé d'instantané", type.snapshotKey)
        }
    }

    @Test
    fun `every catalogue entry carries a string label`() {
        // A zero labelRes would crash getString at display time on a device, invisible
        // to these JVM tests — so assert the id is set rather than resolve it.
        ConditionType.entries.forEach { assertTrue("${it.name} has no label", it.labelRes != 0) }
        ActionType.entries.forEach { assertTrue("${it.name} has no label", it.labelRes != 0) }
    }

    @Test
    fun `charging state and the charging flag answer different questions`() {
        // The boolean cannot name "plugged in but not charging", which is the state a rule
        // warning a departing driver is about — so the two must not share a snapshot key.
        assertEquals(ValueKind.ENUM, ConditionType.CHARGING_STATUS.spec.kind)
        assertFalse(
            "the state and the flag must stay separate readings",
            ConditionType.CHARGING_STATUS.snapshotKey == ConditionType.CHARGING.snapshotKey
        )
    }

    @Test
    fun `only the flowing states count as charging`() {
        // `status != 0` was the old derivation, and it made a finished charge, a stopped one
        // and a fault all read as "charging". Whatever else the set gains, those three stay out.
        listOf(
            VehicleEnums.CHARGING_UNPLUGGED,
            VehicleEnums.CHARGING_DONE,
            VehicleEnums.CHARGING_FAULT,
            VehicleEnums.CHARGING_PLUGGED_IDLE
        ).forEach {
            assertFalse("$it must not read as charging", it in VehicleEnums.CHARGING_ACTIVE_STATES)
        }
        assertTrue(VehicleEnums.CHARGING_AC in VehicleEnums.CHARGING_ACTIVE_STATES)
        assertTrue(VehicleEnums.CHARGING_DC in VehicleEnums.CHARGING_ACTIVE_STATES)
        // Every named state must be offered in the picker, or a rule cannot select it.
        val offered = VehicleEnums.CHARGING_STATUSES.map { it.value }.toSet()
        assertTrue(VehicleEnums.CHARGING_ACTIVE_STATES.all { it in offered })
    }

    @Test
    fun `the charge window ends are clock times`() {
        // Minutes since midnight behind a 0…1439 slider is unanswerable at the wheel; the
        // window's two ends are clock times and get the time control.
        listOf(ConditionType.CHARGE_WINDOW_START, ConditionType.CHARGE_WINDOW_STOP).forEach {
            assertEquals(ValueKind.TIME, it.spec.kind)
            assertTrue("${it.name} must offer before/after", it.comparable)
        }
        // The write moves both ends at once — a half-set window is one nobody intended.
        assertEquals(ValueKind.TIME_RANGE, ActionType.SET_CHARGE_WINDOW.spec.kind)
    }

    @Test
    fun `the two cabin zones are separate entries`() {
        // Adding a zone argument to the driver's target would have made every rule saved
        // before it carry a zone it never chose.
        assertFalse(
            ActionType.SET_CABIN_TEMP.currentKey == ActionType.SET_PASSENGER_TEMP.currentKey
        )
        assertEquals(SnapshotKeys.KEY_PASSENGER_TEMP, ConditionType.PASSENGER_TEMP.snapshotKey)
        assertEquals(
            ActionType.SET_PASSENGER_TEMP.spec.min, ConditionType.PASSENGER_TEMP.spec.min
        )
        assertEquals(
            ActionType.SET_PASSENGER_TEMP.spec.max, ConditionType.PASSENGER_TEMP.spec.max
        )
    }

    @Test
    fun `a settable climate switch is readable back`() {
        // The editor opens a control on the value the car reports. A switch the catalogue can
        // set but never read leaves the rule author guessing at the present state.
        mapOf(
            ActionType.SET_ECON to ConditionType.ECON_MODE,
            ActionType.SET_FRONT_DEFROST to ConditionType.FRONT_DEFROST,
            ActionType.SET_REAR_DEFROST to ConditionType.REAR_DEFROST,
            ActionType.SET_PASSENGER_TEMP to ConditionType.PASSENGER_TEMP
        ).forEach { (action, condition) ->
            assertEquals(
                "${action.name} must open on what ${condition.name} reads",
                condition.snapshotKey, action.currentKey
            )
        }
    }

    @Test
    fun `the media commands are the platform key codes`() {
        // The runner passes them straight to the media key dispatch: a value invented here
        // would send a different key, and the mistake would look like an app bug.
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaCommand.PLAY_PAUSE)
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, MediaCommand.NEXT)
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaCommand.PREVIOUS)
        assertEquals(MediaCommand.OPTIONS.size, MediaCommand.OPTIONS.map { it.value }.toSet().size)
    }

    @Test
    fun `rule chaining names a rule and touches nothing else`() {
        // The pair has to stay symmetric: an editor offering "enable" with a rule chooser and
        // "disable" with a text field would store two different things in the same field.
        listOf(ActionType.ENABLE_RULE, ActionType.DISABLE_RULE).forEach {
            assertEquals(ValueKind.RULE, it.spec.kind)
            assertFalse("${it.name} writes nothing to the vehicle", it.gated)
        }
    }

    @Test
    fun `each window is readable and settable, and the group read stays`() {
        // The all-windows action is not replaced by the four: closing the glass is one
        // gesture, and splitting it would let three succeed and one be forgotten.
        assertNotNull(ActionType.SET_WINDOWS.bridgeAction)
        assertEquals(SnapshotKeys.KEY_WINDOW_PERCENT, ConditionType.WINDOW_POSITION.snapshotKey)

        // One action per window, one condition per window: the write is a command and the
        // read is a position, so they are not two ends of the same scale — see the glass test.
        listOf(
            ActionType.SET_WINDOW_DRIVER, ActionType.SET_WINDOW_PASSENGER,
            ActionType.SET_WINDOW_REAR_LEFT, ActionType.SET_WINDOW_REAR_RIGHT
        ).forEach { assertNotNull("${it.name} must reach the bridge", it.bridgeAction) }
        listOf(
            ConditionType.WINDOW_DRIVER, ConditionType.WINDOW_PASSENGER,
            ConditionType.WINDOW_REAR_LEFT, ConditionType.WINDOW_REAR_RIGHT
        ).forEach {
            assertEquals("${it.name} reads a position in percent", 100, it.spec.max)
        }

        // Four windows, four distinct keys — a copy-paste that reused one would make two
        // windows report each other's position.
        val keys = listOf(
            ConditionType.WINDOW_DRIVER, ConditionType.WINDOW_PASSENGER,
            ConditionType.WINDOW_REAR_LEFT, ConditionType.WINDOW_REAR_RIGHT
        ).map { it.snapshotKey }
        assertEquals(4, keys.toSet().size)
        assertFalse(SnapshotKeys.KEY_WINDOW_PERCENT in keys)
    }

    @Test
    fun `the head-unit app services are read-only and unrouted`() {
        // They belong to the head unit's own apps, not to the vehicle SDK, so there is no
        // generation to route them by and nothing to write back.
        listOf(ConditionType.ODOMETER, ConditionType.WEATHER_NOW).forEach {
            assertNotNull("${it.name} must read a snapshot key", it.snapshotKey)
        }
        // A list, not free text: the provider's phrase is the one value nobody can type, so
        // the rule stores a state the catalogue owns and the reader does the classifying.
        assertEquals(ValueKind.ENUM, ConditionType.WEATHER_NOW.spec.kind)
        assertEquals(WeatherConditions.OPTIONS, ConditionType.WEATHER_NOW.spec.options)
        assertTrue("a rule asks for more or fewer kilometres", ConditionType.ODOMETER.comparable)
    }

    @Test
    fun `every glass write is gated, and asks for a state rather than a raw command`() {
        // The service takes a command in 0..7 on the way in and answers a percentage on the
        // way out, so neither a raw command nor a position belongs in a saved rule. A command
        // number is worse than useless in the editor — it is what let a rule ask for "7",
        // which the service accepts and drops — and a percentage would offer a hundred and one
        // values of which ninety-nine the glass cannot reach. Open and closed are the two
        // states that exist.
        val glass = listOf(
            ActionType.SET_WINDOWS, ActionType.SET_WINDOW_DRIVER, ActionType.SET_WINDOW_PASSENGER,
            ActionType.SET_WINDOW_REAR_LEFT, ActionType.SET_WINDOW_REAR_RIGHT
        )
        glass.forEach {
            assertTrue("${it.name} must take the standstill gate", it.gated)
            assertEquals("${it.name} asks for a state", ValueKind.ENUM, it.spec.kind)
            assertEquals(
                "${it.name} offers exactly open and closed",
                VehicleEnums.WINDOW_COMMANDS, it.spec.options
            )
            // No current value to open the editor on: the reading is a position and the
            // control is a state, so seeding one with the other would be a false start.
            assertNull("${it.name} has no position to preselect", it.currentKey)
            // The catalogue still cannot claim the write moves anything. GlassEvidence lifts
            // this per car, once a probe has watched a command move the glass.
            assertFalse("${it.name} effect is not established", it.writeProven)
        }
        // Exactly these: an unproven write is invisible to the user, so adding one elsewhere
        // silently removes an action from every app that carries this catalogue.
        assertEquals(glass.toSet(), ActionType.entries.filter { !it.writeProven }.toSet())
    }

    @Test
    fun `the window states carry no car-specific command number`() {
        // Which raw command opens and which closes is a property of the car, established by
        // GlassProbe and stored in GlassEvidence — never of the catalogue. A rule exported
        // from one car and imported on another must not carry the first car's command codes.
        assertEquals(0, VehicleEnums.WINDOW_CLOSE)
        assertEquals(1, VehicleEnums.WINDOW_OPEN)
        assertEquals(2, VehicleEnums.WINDOW_COMMANDS.size)
        assertEquals(
            setOf(VehicleEnums.WINDOW_CLOSE, VehicleEnums.WINDOW_OPEN),
            VehicleEnums.WINDOW_COMMANDS.map { it.value }.toSet()
        )
    }

    @Test
    fun `the radio bands are the vendor RadioType values, and include DAB`() {
        // Passed straight to tune() as its band argument: a value invented here tunes the
        // wrong band, or none.
        val byValue = VehicleEnums.RADIO_BANDS.associateBy { it.value }
        assertTrue("AM missing", SaicRadio.BAND_AM in byValue)
        assertTrue("FM missing", SaicRadio.BAND_FM in byValue)
        assertTrue("DAB missing — it is the band the tune action cannot reach", SaicRadio.BAND_DAB in byValue)
        assertEquals(3, VehicleEnums.RADIO_BANDS.size)
    }

    @Test
    fun `les enumerations ont des options`() {
        // Une ENUM sans option produit une liste déroulante vide : l'utilisateur ne peut
        // rien choisir et la règle reste inutilisable.
        ConditionType.entries
            .filter { it.spec.kind == ValueKind.ENUM && it != ConditionType.FIRMWARE_GEN }
            .forEach { assertTrue("${it.name} : ENUM sans option", it.spec.options.isNotEmpty()) }

        // RADIO dessine aussi une liste déroulante — celle des bandes — donc la même règle
        // s'applique : sans option, l'action ne peut nommer aucune bande.
        ActionType.entries
            .filter { it.spec.kind == ValueKind.ENUM || it.spec.kind == ValueKind.RADIO }
            .forEach { assertTrue("${it.name} : ENUM sans option", it.spec.options.isNotEmpty()) }
    }

    @Test
    fun `les bornes numeriques sont coherentes`() {
        val specs = ConditionType.entries.map { it.name to it.spec } +
                ActionType.entries.map { it.name to it.spec }

        specs.filter { it.second.kind == ValueKind.NUMBER }.forEach { (name, spec) ->
            if (spec.max >= 0) {
                assertTrue("$name : max doit dépasser min", spec.max > spec.min)
            } else {
                // max = -1 : borne résolue à l'exécution. Le repli doit rester utilisable
                // même si le véhicule ne répond pas.
                assertTrue("$name : fallbackMax doit dépasser min", spec.fallbackMax > spec.min)
            }
        }
    }
}
