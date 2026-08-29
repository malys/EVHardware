package com.evsuite.hardware.catalog

import com.evsuite.hardware.R

/**
 * Numeric values the vehicle expects, copied from EVProfile.
 *
 * These are protocol values, not display indices: reordering or renumbering them changes
 * the setting applied to the car. Sources in EVProfile: `model/DriveMode.kt`,
 * `model/RegenLevel.kt`, and `hardware/EVHardware.kt` (objects `AebMode`, `Swi68Mode`,
 * plus the ELK / SAS comments).
 */
object VehicleEnums {

    /** DriveMode.value — EVProfile model/DriveMode.kt */
    val DRIVE_MODES = listOf(
        EnumOption(2, R.string.drive_eco),
        EnumOption(3, R.string.drive_normal),
        EnumOption(4, R.string.drive_sport),
        EnumOption(6, R.string.drive_snow),
        EnumOption(7, R.string.drive_custom)
    )

    /** RegenLevel.value — EVProfile model/RegenLevel.kt */
    val REGEN_LEVELS = listOf(
        EnumOption(0, R.string.regen_low),
        EnumOption(1, R.string.regen_medium),
        EnumOption(2, R.string.regen_high),
        EnumOption(3, R.string.regen_adaptive),
        EnumOption(5, R.string.regen_off),
        EnumOption(6, R.string.regen_one_pedal)
    )

    /** EVHardware.AebMode */
    val AEB_MODES = listOf(
        EnumOption(1, R.string.aeb_alert_only),
        EnumOption(2, R.string.aeb_alert_brake)
    )

    /** AEB / ELK sensitivity — 1 low, 2 standard, 3 high (0 means "not configured"). */
    val SENSITIVITIES = listOf(
        EnumOption(1, R.string.sensitivity_low),
        EnumOption(2, R.string.sensitivity_standard),
        EnumOption(3, R.string.sensitivity_high)
    )

    /** ELK — see EVHardware.getElkMode / setElkMode. */
    val ELK_MODES = listOf(
        EnumOption(1, R.string.elk_off),
        EnumOption(2, R.string.elk_warn),
        EnumOption(3, R.string.elk_assist),
        EnumOption(5, R.string.elk_full)
    )

    /** EVHardware.Swi68Mode — ACC/TJA. OFF is 0x4, not 0. */
    val ACC_TJA_MODES = listOf(
        EnumOption(4, R.string.acc_off),
        EnumOption(1, R.string.acc_acc),
        EnumOption(2, R.string.acc_tja)
    )

    /** SAS speed limiter — 0 off, 2 manual, 3 intelligent. */
    val LIMITER_MODES = listOf(
        EnumOption(0, R.string.limiter_off),
        EnumOption(2, R.string.limiter_manual),
        EnumOption(3, R.string.limiter_smart)
    )

    /** VehicleIgnitionState (standard AAOS) — useful values only. */
    val IGNITION_STATES = listOf(
        EnumOption(1, R.string.ignition_lock),
        EnumOption(2, R.string.ignition_off),
        EnumOption(3, R.string.ignition_acc),
        EnumOption(4, R.string.ignition_on),
        EnumOption(5, R.string.ignition_start)
    )

    /** Firmware generations — FirmwareInfo.Gen in EVProfile. */
    val FIRMWARE_GENS = listOf("SWI133", "SWI132", "SWI68", "SWI69", "SWI131", "SWI165")

    // Audio bounds — EVHardware AUDIO_TYPE_MIN/MAX and AUDIO_LEVEL_MIN/MAX.
    const val AUDIO_TYPE_MIN  = 0
    const val AUDIO_TYPE_MAX  = 3
    const val AUDIO_LEVEL_MIN = -9
    const val AUDIO_LEVEL_MAX = 9

    /** EVProfile's brightness floor: never black out the vehicle screen. */
    const val BRIGHTNESS_MIN = 5
    const val BRIGHTNESS_MAX = 100

    /** Fallback when the vehicle does not report its real maximum volume. */
    const val MEDIA_VOLUME_FALLBACK_MAX = 30

    // ── Climate — from the vendor HMI, not from the AOSP property ranges ─────
    // HvacConst.AIR_VOLUME_MAX and the 17…33 clamp in
    // HvacActivity.onLongTouch where the two ends display as LO and HI.
    const val FAN_LEVEL_MAX  = 11
    const val CABIN_TEMP_MIN = 17
    const val CABIN_TEMP_MAX = 33

    /** Charge limit the vehicle accepts, in percent. */
    const val CHARGE_LIMIT_MIN = 40
    const val CHARGE_LIMIT_MAX = 100

    /**
     * The charging states the vendor service reports, as the car's own charging screen
     * groups them.
     *
     * Only the states a rule can act on are named. The service answers others — bookkeeping
     * values a driver has no rule for — and a condition comparing against one of those simply
     * does not match, which is the honest outcome for a state the catalogue does not claim.
     *
     * [CHARGING_UNPLUGGED] is why this exists as a number rather than as the boolean
     * [SnapshotKeys.KEY_CHARGING]: a cable plugged in with charging stopped is neither
     * "charging" nor "unplugged", and the boolean has to pick one.
     */
    const val CHARGING_UNPLUGGED = 0
    const val CHARGING_AC        = 1
    const val CHARGING_DONE      = 2
    const val CHARGING_FAULT     = 4
    const val CHARGING_PLUGGED_IDLE = 7
    const val CHARGING_DC        = 10

    val CHARGING_STATUSES = listOf(
        EnumOption(CHARGING_UNPLUGGED, R.string.charging_unplugged),
        EnumOption(CHARGING_AC, R.string.charging_ac),
        EnumOption(CHARGING_DC, R.string.charging_dc),
        EnumOption(CHARGING_PLUGGED_IDLE, R.string.charging_plugged_idle),
        EnumOption(CHARGING_DONE, R.string.charging_done),
        EnumOption(CHARGING_FAULT, R.string.charging_fault)
    )

    /**
     * The states in which current is actually flowing into the battery.
     *
     * What [SnapshotKeys.KEY_CHARGING] means, and the reason it is derived from this set
     * rather than from `status != 0`: a cable plugged in with charging stopped
     * ([CHARGING_PLUGGED_IDLE]), a finished charge ([CHARGING_DONE]) and a fault
     * ([CHARGING_FAULT]) are all non-zero, and all three would make a "when charging" rule
     * fire on a car that is not charging.
     */
    val CHARGING_ACTIVE_STATES = setOf(CHARGING_AC, CHARGING_DC)

    /**
     * What a glass action asks for: open the window, or close it. Nothing in between.
     *
     * The vendor service takes a **command**, not a position — it reads a percentage back
     * and accepts nothing above 7 on the way in — so the only two states a rule can reach are
     * fully open and fully closed. A percentage control would offer a hundred and one values
     * of which ninety-nine are unreachable, and the history would report "applied" for a
     * position the glass never went to.
     *
     * Which raw command opens and which closes is **not** encoded here, because it is not a
     * property of the catalogue: it is a property of the car, established by `GlassProbe` and
     * stored in `GlassEvidence`. The executor translates. That is why these values are 0 and 1
     * rather than the observed commands — a rule saved on one car must not carry another car's
     * command numbers.
     */
    const val WINDOW_CLOSE = 0
    const val WINDOW_OPEN  = 1

    val WINDOW_COMMANDS = listOf(
        EnumOption(WINDOW_CLOSE, R.string.window_close),
        EnumOption(WINDOW_OPEN, R.string.window_open)
    )

    /**
     * The tuner's bands, as `RadioType` numbers them.
     *
     * DAB is in the list because stepping already reaches it and the band is what a rule
     * cannot otherwise change: `TUNE_RADIO` takes a frequency, and a DAB service is addressed
     * by ensemble and service id, so there is no DAB station a driver could type.
     */
    val RADIO_BANDS = listOf(
        EnumOption(1, R.string.radio_band_am),
        EnumOption(2, R.string.radio_band_fm),
        EnumOption(4, R.string.radio_band_dab)
    )
}
