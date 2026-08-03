package com.mg4.hardware.catalog

import androidx.annotation.StringRes
import com.mg4.hardware.R
import com.mg4.hardware.catalog.SnapshotKeys
import com.mg4.hardware.catalog.VehicleEnums
import com.mg4.hardware.SupportedOn
import com.mg4.hardware.FirmwareGen.SWI131
import com.mg4.hardware.FirmwareGen.SWI132
import com.mg4.hardware.FirmwareGen.SWI133
import com.mg4.hardware.FirmwareGen.SWI165
import com.mg4.hardware.FirmwareGen.SWI68
import com.mg4.hardware.FirmwareGen.SWI69
import com.mg4.hardware.catalog.ValueSpec.Companion.enum
import com.mg4.hardware.catalog.ValueSpec.Companion.number

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
 * [snapshotKey] is the key read from the snapshot MG4Control returns. When it is absent
 * from the snapshot the condition is UNAVAILABLE — not false. The distinction is
 * deliberate: on a firmware that does not expose outside temperature, a weather rule must
 * show as "cannot be evaluated" and do nothing, never behave as if it were 0 °C.
 *
 * [snapshotKey] is null for conditions computed locally (time, day, Bluetooth), which do
 * not depend on the vehicle.
 *
 * The [SupportedOn] annotation on each vehicle entry declares which firmware generations
 * it works on, derived from MG4Control's `FirmwareInfo` and `MG4Hardware` routing. It
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

    // ── Energy (vendor charging service — see com.mg4.hardware.saic.SaicCharging) ──
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

    // ── Climate + windows (read only, unverified on MG4 — see MG4Hardware) ────
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
