package com.evsuite.hardware.catalog

import androidx.annotation.StringRes
import com.evsuite.hardware.R
import com.evsuite.hardware.catalog.SnapshotKeys
import com.evsuite.hardware.catalog.VehicleEnums
import com.evsuite.hardware.SupportedOn
import com.evsuite.hardware.FirmwareGen.SWI131
import com.evsuite.hardware.FirmwareGen.SWI132
import com.evsuite.hardware.FirmwareGen.SWI133
import com.evsuite.hardware.FirmwareGen.SWI165
import com.evsuite.hardware.FirmwareGen.SWI68
import com.evsuite.hardware.FirmwareGen.SWI69
import com.evsuite.hardware.catalog.ValueSpec.Companion.enum
import com.evsuite.hardware.catalog.ValueSpec.Companion.number

/** Grouping in the condition picker. */
enum class ConditionGroup(@StringRes val labelRes: Int) {
    CONTEXT(R.string.group_context),
    ENVIRONMENT(R.string.group_environment),
    DRIVING(R.string.group_driving),
    ENERGY(R.string.group_energy),
    CLIMATE(R.string.group_climate),
    COMFORT(R.string.group_comfort),
    ADAS(R.string.group_adas)
}

/**
 * Catalogue of evaluable conditions.
 *
 * [snapshotKey] is the key read from the snapshot EVProfile returns. When it is absent
 * from the snapshot the condition is UNAVAILABLE — not false. The distinction is
 * deliberate: on a firmware that does not expose outside temperature, a weather rule must
 * show as "cannot be evaluated" and do nothing, never behave as if it were 0 °C.
 *
 * [snapshotKey] is null for conditions computed locally (time, day, Bluetooth), which do
 * not depend on the vehicle.
 *
 * The [SupportedOn] annotation on each vehicle entry declares which firmware generations
 * it works on, derived from EVProfile's `FirmwareInfo` and `EVHardware` routing. It
 * drives the README matrix (generated) and the editor's runtime filter. Context entries
 * carry no annotation — they are firmware-independent.
 */
enum class ConditionType(
    @StringRes val labelRes: Int,
    val group: ConditionGroup,
    val spec: ValueSpec,
    val snapshotKey: String?,
    /** true when </> comparison is meaningful (otherwise only equality is offered). */
    val comparable: Boolean = false,
    /** True when the condition itself supplies the event that addresses the rule. */
    val eventDriven: Boolean = false
) {

    // ── Context (firmware-independent) ───────────────────────────────────────
    BT_DEVICE_CONNECTED(
        R.string.cond_bt_device, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.BT_DEVICE), snapshotKey = null
    ),
    ANY_BT_CONNECTED(
        R.string.cond_bt_any, ConditionGroup.CONTEXT,
        ValueSpec.BOOL, snapshotKey = null
    ),
    /**
     * A phone that is *in the car*, not merely within radio range of it.
     *
     * [BT_DEVICE_CONNECTED] answers "the link is up", which a phone left in the house
     * answers just as well when the car is parked ten metres away — and it is what made
     * arrival rules fire on the driveway. This one is only true of a device still connected
     * once the car has actually moved, so a phone that stayed behind drops out of it.
     *
     * Unknowable until the car has driven: before that the condition is UNAVAILABLE, never
     * false, and rules that must act at ignition should gate on [BT_DEVICE_CONNECTED] plus
     * a vehicle signal instead.
     */
    BT_DEVICE_ONBOARD(
        R.string.cond_bt_onboard, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.BT_DEVICE), snapshotKey = null
    ),
    /**
     * The phone the head unit has made its hands-free device.
     *
     * The head unit picks one, whatever the number of phones in range, and that choice is
     * available from the first second of the drive — which is what [BT_DEVICE_ONBOARD]
     * cannot offer. With two phones both in range it is the head unit's answer, not a
     * measurement, so it is a hint about which phone is driving, not proof.
     */
    BT_DEVICE_HANDSFREE(
        R.string.cond_bt_handsfree, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.BT_DEVICE), snapshotKey = null
    ),
    TIME_OF_DAY(
        R.string.cond_time, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.TIME_RANGE), snapshotKey = null
    ),
    DAY_OF_WEEK(
        R.string.cond_day, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.DAYS), snapshotKey = null
    ),
    DATE(
        R.string.cond_date, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.DATE), snapshotKey = null
    ),
    FIRMWARE_GEN(
        R.string.cond_firmware, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.ENUM), snapshotKey = SnapshotKeys.KEY_FIRMWARE_GEN
    ),
    /**
     * Inside a radius of a saved point. Context, not a vehicle signal: the fix comes from
     * the platform location provider, so it works the same whatever the firmware exposes.
     */
    LOCATION_WITHIN(
        R.string.cond_location, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.LOCATION, min = 50, max = 2000, unitRes = R.string.unit_metre),
        snapshotKey = null
    ),
    PHYSICAL_BUTTON(
        R.string.cond_physical_button, ConditionGroup.CONTEXT,
        ValueSpec(ValueKind.PHYSICAL_BUTTON), SnapshotKeys.KEY_PHYSICAL_BUTTON_EVENT,
        eventDriven = true
    ),

    // ── Environment ──────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    OUTSIDE_TEMP(
        R.string.cond_outside_temp, ConditionGroup.ENVIRONMENT,
        number(-30, 50, R.string.unit_celsius),
        SnapshotKeys.KEY_OUTSIDE_TEMP, comparable = true
    ),

    // ── Driving ──────────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    IGNITION_STATE(
        R.string.cond_ignition, ConditionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.IGNITION_STATES),
        SnapshotKeys.KEY_IGNITION
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    IN_PARK(
        R.string.cond_in_park, ConditionGroup.DRIVING,
        ValueSpec.BOOL, SnapshotKeys.KEY_IN_PARK
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SPEED(
        R.string.cond_speed, ConditionGroup.DRIVING,
        number(0, 200, R.string.unit_kmh),
        SnapshotKeys.KEY_SPEED_KMH, comparable = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    DRIVE_MODE(
        R.string.cond_drive_mode, ConditionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.DRIVE_MODES),
        SnapshotKeys.KEY_DRIVE_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    REGEN_LEVEL(
        R.string.cond_regen, ConditionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.REGEN_LEVELS),
        SnapshotKeys.KEY_REGEN_LEVEL
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    ENERGY_SAVING(
        R.string.cond_energy_saving, ConditionGroup.DRIVING,
        ValueSpec.BOOL, SnapshotKeys.KEY_ENERGY_SAVING
    ),

    // ── Energy (vendor charging service — see com.evsuite.hardware.saic.SaicCharging) ──
    @SupportedOn(SWI68, SWI165)
    BATTERY_LEVEL(
        R.string.cond_battery, ConditionGroup.ENERGY,
        number(0, 100, R.string.unit_percent),
        SnapshotKeys.KEY_BATTERY_PERCENT, comparable = true
    ),
    @SupportedOn(SWI68, SWI165)
    CHARGING(
        R.string.cond_charging, ConditionGroup.ENERGY,
        ValueSpec.BOOL, SnapshotKeys.KEY_CHARGING
    ),
    @SupportedOn(SWI68, SWI165)
    CHARGE_LIMIT(
        R.string.cond_charge_limit, ConditionGroup.ENERGY,
        number(0, 100, R.string.unit_percent),
        SnapshotKeys.KEY_CHARGE_LIMIT, comparable = true
    ),
    /**
     * The charging state itself, where [CHARGING] is only "current is flowing".
     *
     * The two answer different questions and both are worth asking: a rule that pre-heats the
     * cabin wants "charging", while a rule that warns before the driver walks away wants
     * "plugged in but not charging" — a state the boolean cannot name.
     */
    @SupportedOn(SWI68, SWI165)
    CHARGING_STATUS(
        R.string.cond_charging_status, ConditionGroup.ENERGY,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.CHARGING_STATUSES),
        SnapshotKeys.KEY_CHARGING_STATUS
    ),
    @SupportedOn(SWI68, SWI165)
    CHARGE_SCHEDULE_ENABLED(
        R.string.cond_charge_schedule, ConditionGroup.ENERGY,
        ValueSpec.BOOL, SnapshotKeys.KEY_CHARGE_SCHEDULE
    ),
    /**
     * The scheduled window's two ends, read back from the car.
     *
     * Two conditions rather than the one range [ActionType.SET_CHARGE_WINDOW] writes: the
     * write has to move both ends together or the car is left with a window nobody intended,
     * while a rule reading them asks about one end at a time ("charging starts after
     * midnight"). Comparable, so "before" and "after" are available and not only equality.
     */
    @SupportedOn(SWI68, SWI165)
    CHARGE_WINDOW_START(
        R.string.cond_charge_window_start, ConditionGroup.ENERGY,
        ValueSpec(ValueKind.TIME), SnapshotKeys.KEY_CHARGE_WINDOW_START, comparable = true
    ),
    @SupportedOn(SWI68, SWI165)
    CHARGE_WINDOW_STOP(
        R.string.cond_charge_window_stop, ConditionGroup.ENERGY,
        ValueSpec(ValueKind.TIME), SnapshotKeys.KEY_CHARGE_WINDOW_STOP, comparable = true
    ),
    @SupportedOn(SWI68, SWI165)
    BATTERY_PREHEAT(
        R.string.cond_battery_preheat, ConditionGroup.ENERGY,
        ValueSpec.BOOL, SnapshotKeys.KEY_BATTERY_PREHEAT
    ),

    // ── Climate + windows (read only, unverified on MG4 — see EVHardware) ────
    /**
     * Whether the climate system is running, from the vendor service rather than an AOSP
     * property id — the same source the car's own HVAC screen reads.
     */
    @SupportedOn(SWI68, SWI165)
    CLIMATE_ON(
        R.string.cond_climate_on, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_CLIMATE_ON
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    AC_ON(
        R.string.cond_ac, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_AC_ON
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    HVAC_AUTO(
        R.string.cond_hvac_auto, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_HVAC_AUTO
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    RECIRC(
        R.string.cond_recirc, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_RECIRC
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    // Bounds from the vendor HMI (HvacConst.AIR_VOLUME_MAX, and the 17…33 clamp in
    // HvacActivity where the ends read as LO and HI), not from the AOSP property range.
    FAN_SPEED(
        R.string.cond_fan_speed, ConditionGroup.CLIMATE,
        number(0, VehicleEnums.FAN_LEVEL_MAX), SnapshotKeys.KEY_FAN_SPEED, comparable = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    TEMPERATURE_SET(
        R.string.cond_temperature_set, ConditionGroup.CLIMATE,
        number(VehicleEnums.CABIN_TEMP_MIN, VehicleEnums.CABIN_TEMP_MAX, R.string.unit_celsius),
        SnapshotKeys.KEY_TEMPERATURE_SET, comparable = true
    ),
    /**
     * The passenger side's own target, where [TEMPERATURE_SET] is the driver's.
     *
     * The two are the same number until dual zone is on, which is exactly when a rule needs
     * to tell them apart — and there is no readable "dual zone" flag, so the honest way to
     * ask is to compare the two targets.
     */
    @SupportedOn(SWI68, SWI165)
    PASSENGER_TEMP(
        R.string.cond_passenger_temp, ConditionGroup.CLIMATE,
        number(VehicleEnums.CABIN_TEMP_MIN, VehicleEnums.CABIN_TEMP_MAX, R.string.unit_celsius),
        SnapshotKeys.KEY_PASSENGER_TEMP, comparable = true
    ),
    @SupportedOn(SWI68, SWI165)
    ECON_MODE(
        R.string.cond_econ, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_ECON
    ),
    @SupportedOn(SWI68, SWI165)
    FRONT_DEFROST(
        R.string.cond_front_defrost, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_FRONT_DEFROST
    ),
    @SupportedOn(SWI68, SWI165)
    REAR_DEFROST(
        R.string.cond_rear_defrost, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_REAR_DEFROST
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    WINDOW_OPEN(
        R.string.cond_window_open, ConditionGroup.CLIMATE,
        ValueSpec.BOOL, SnapshotKeys.KEY_WINDOW_OPEN
    ),
    /**
     * How far the widest-open window is, in percent — the vendor read, unlike [WINDOW_OPEN]
     * which is an unverified AOSP property. "Any window open" is `> 0`; a rule that closes
     * them cares about the worst case, which is what this reports.
     */
    @SupportedOn(SWI68, SWI165)
    WINDOW_POSITION(
        R.string.cond_window_position, ConditionGroup.CLIMATE,
        number(0, 100, R.string.unit_percent),
        SnapshotKeys.KEY_WINDOW_PERCENT, comparable = true
    ),
    @SupportedOn(SWI68, SWI165)
    DOORS_LOCKED(
        R.string.cond_doors_locked, ConditionGroup.CONTEXT,
        ValueSpec.BOOL, SnapshotKeys.KEY_DOORS_LOCKED
    ),
    /**
     * Either front door standing open.
     *
     * Front doors only, and only where the door status property answers: those are the two
     * the library reads today, for the volume-drop feature. The rear doors are not claimed
     * here rather than guessed at, and a firmware that does not answer leaves the condition
     * UNAVAILABLE instead of reporting the doors shut.
     */
    @SupportedOn(SWI133)
    FRONT_DOOR_OPEN(
        R.string.cond_front_door_open, ConditionGroup.CONTEXT,
        ValueSpec.BOOL, SnapshotKeys.KEY_FRONT_DOOR_OPEN
    ),

    // ── Comfort ──────────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI68, SWI165)
    SEAT_HEAT_LEFT(
        R.string.cond_seat_heat_l, ConditionGroup.COMFORT,
        number(0, 3), SnapshotKeys.KEY_SEAT_HEAT_L, comparable = true
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    SEAT_HEAT_RIGHT(
        R.string.cond_seat_heat_r, ConditionGroup.COMFORT,
        number(0, 3), SnapshotKeys.KEY_SEAT_HEAT_R, comparable = true
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    STEERING_HEAT(
        R.string.cond_steering_heat, ConditionGroup.COMFORT,
        ValueSpec.BOOL, SnapshotKeys.KEY_STEERING_HEAT
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    MEDIA_VOLUME(
        R.string.cond_media_volume, ConditionGroup.COMFORT,
        ValueSpec.dynamicNumber(0, VehicleEnums.MEDIA_VOLUME_FALLBACK_MAX),
        SnapshotKeys.KEY_MEDIA_VOLUME, comparable = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SCREEN_BRIGHTNESS(
        R.string.cond_brightness, ConditionGroup.COMFORT,
        number(VehicleEnums.BRIGHTNESS_MIN, VehicleEnums.BRIGHTNESS_MAX, R.string.unit_percent),
        SnapshotKeys.KEY_BRIGHTNESS, comparable = true
    ),

    // ── Driver assistance ────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    AEB_ENABLED(
        R.string.cond_aeb, ConditionGroup.ADAS,
        ValueSpec.BOOL, SnapshotKeys.KEY_AEB_ENABLED
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    AEB_MODE(
        R.string.cond_aeb_mode, ConditionGroup.ADAS,
        enum(*VehicleEnums.AEB_MODES.toTypedArray()), SnapshotKeys.KEY_AEB_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    AEB_SENSITIVITY(
        R.string.cond_aeb_sensitivity, ConditionGroup.ADAS,
        enum(*VehicleEnums.SENSITIVITIES.toTypedArray()), SnapshotKeys.KEY_AEB_SENSITIVITY
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    ELK_MODE(
        R.string.cond_elk_mode, ConditionGroup.ADAS,
        enum(*VehicleEnums.ELK_MODES.toTypedArray()), SnapshotKeys.KEY_ELK_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    ELK_SENSITIVITY(
        R.string.cond_elk_sensitivity, ConditionGroup.ADAS,
        enum(*VehicleEnums.SENSITIVITIES.toTypedArray()), SnapshotKeys.KEY_ELK_SENSITIVITY
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    ACC_TJA_MODE(
        R.string.cond_acc_tja, ConditionGroup.ADAS,
        enum(*VehicleEnums.ACC_TJA_MODES.toTypedArray()), SnapshotKeys.KEY_ACC_TJA_MODE
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    LIMITER_MODE(
        R.string.cond_limiter, ConditionGroup.ADAS,
        enum(*VehicleEnums.LIMITER_MODES.toTypedArray()), SnapshotKeys.KEY_LIMITER_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    TSR(
        R.string.cond_tsr, ConditionGroup.ADAS,
        ValueSpec.BOOL, SnapshotKeys.KEY_TSR
    ),
    @SupportedOn(SWI133, SWI132)
    OVERSPEED_ALARM(
        R.string.cond_overspeed, ConditionGroup.ADAS,
        ValueSpec.BOOL, SnapshotKeys.KEY_OVERSPEED_ALARM
    ),
    @SupportedOn(SWI133, SWI132)
    SPEED_LIMIT_TONE(
        R.string.cond_speed_limit_tone, ConditionGroup.ADAS,
        ValueSpec.BOOL, SnapshotKeys.KEY_SPEED_LIMIT_TONE
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SOUND_WARNING(
        R.string.cond_sound_warning, ConditionGroup.ADAS,
        ValueSpec.BOOL, SnapshotKeys.KEY_SOUND_WARNING
    );

    companion object {
        fun byGroup(): Map<ConditionGroup, List<ConditionType>> = entries.groupBy { it.group }
    }
}
