package com.evsuite.hardware

/**
 * Exterior lights (CR-046): the standard AAOS ids, their values, and what a rule may do with them.
 *
 * Kept free of Android so the policy is pinned by JVM tests. The ids are the public
 * `VehicleProperty` ones (types.hal 2.0, available on AAOS 9); nothing here comes from a vendor
 * catalogue. Whether the MG4 VHAL publishes them is a per-generation question: a generation joins
 * [PROVEN] only with a capture showing the state follow the stalk — EVTasker's Diagnostic
 * `EXTERIOR_LIGHTS` row ([EVHardware.probeExteriorLights]), exported off and on — in the same commit as the
 * `@SupportedOn` of the light entries and their `writeProven`.
 *
 * Owner decisions (2026-10-08): writes only at 0 km/h like every vehicle write, and a rule may
 * never switch the low beams OFF. Same reasoning for the high beam: a rule may set it OFF or
 * AUTOMATIC, never force it ON — that dazzles, and the stalk stays the driver's.
 */
object ExteriorLights {

    // VehicleProperty — SYSTEM | GLOBAL | INT32 | 0x0E0x.
    const val PROP_HEADLIGHTS_STATE = 0x11400E00
    const val PROP_HIGH_BEAM_LIGHTS_STATE = 0x11400E01
    const val PROP_FOG_LIGHTS_STATE = 0x11400E02
    const val PROP_HEADLIGHTS_SWITCH = 0x11400E10
    const val PROP_HIGH_BEAM_LIGHTS_SWITCH = 0x11400E11
    const val PROP_FOG_LIGHTS_SWITCH = 0x11400E12

    // VehicleLightState
    const val STATE_OFF = 0
    const val STATE_ON = 1
    const val STATE_DAYTIME_RUNNING = 2

    // VehicleLightSwitch
    const val SWITCH_OFF = 0
    const val SWITCH_ON = 1
    const val SWITCH_DAYTIME_RUNNING = 2
    const val SWITCH_AUTOMATIC = 0x100

    /** Generations where the ids above are proven on the car. Empty until a capture says so. */
    val PROVEN: Set<FirmwareInfo.Gen> = emptySet()

    /** Names for the capture diff, so a changed light property reads as itself. */
    val NAMES: Map<Int, String> = mapOf(
        PROP_HEADLIGHTS_STATE to "HEADLIGHTS_STATE",
        PROP_HIGH_BEAM_LIGHTS_STATE to "HIGH_BEAM_LIGHTS_STATE",
        PROP_FOG_LIGHTS_STATE to "FOG_LIGHTS_STATE",
        PROP_HEADLIGHTS_SWITCH to "HEADLIGHTS_SWITCH",
        PROP_HIGH_BEAM_LIGHTS_SWITCH to "HIGH_BEAM_LIGHTS_SWITCH",
        PROP_FOG_LIGHTS_SWITCH to "FOG_LIGHTS_SWITCH",
    )

    /** Low beams: ON or AUTOMATIC. Never OFF. */
    val HEADLIGHT_SWITCH_ALLOWED = setOf(SWITCH_ON, SWITCH_AUTOMATIC)

    /** High beam: OFF or AUTOMATIC. Never forced ON. */
    val HIGH_BEAM_SWITCH_ALLOWED = setOf(SWITCH_OFF, SWITCH_AUTOMATIC)

    /** Fog lights: on or off. */
    val FOG_SWITCH_ALLOWED = setOf(SWITCH_OFF, SWITCH_ON)

    fun isProven(gen: FirmwareInfo.Gen): Boolean = gen in PROVEN

    /**
     * Why a light write must not be sent, or null when it may go on to the speed gate.
     * Policy first, so a forbidden value is refused on every car, proven or not.
     */
    fun writeRefusal(
        gen: FirmwareInfo.Gen,
        allowed: Set<Int>,
        value: Int,
        proven: Set<FirmwareInfo.Gen> = PROVEN,
    ): String? = when {
        value !in allowed -> "value 0x${Integer.toHexString(value)} not allowed for a rule"
        gen !in proven    -> "exterior lights not proven on $gen"
        else              -> null
    }

    /** A light state read as "on"; daytime running lights are not headlights on. */
    fun isOn(state: Int?): Boolean? = state?.let { it == STATE_ON }
}
