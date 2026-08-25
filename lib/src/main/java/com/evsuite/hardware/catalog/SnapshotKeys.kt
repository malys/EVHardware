package com.evsuite.hardware.catalog

/**
 * Stable string keys identifying each vehicle signal a [ConditionType] reads.
 *
 * These are the canonical identifiers for the catalogue, owned by this library. A consumer
 * that snapshots the vehicle (e.g. over IPC) must key its bundle with exactly these
 * strings; keeping them here — not in an app — is what lets the catalogue live in the
 * shared library without depending on any app's transport.
 *
 * A key absent from a snapshot means "unreadable", never a value: the whole evaluation
 * model rests on that distinction.
 */
object SnapshotKeys {
    const val KEY_PHYSICAL_BUTTON_EVENT = "physicalButtonEvent"
    const val KEY_FIRMWARE_GEN     = "firmwareGen"
    const val KEY_STAR_LEFT_SHORT  = "starLeftShortPress"
    const val KEY_STAR_LEFT_LONG   = "starLeftLongPress"
    const val KEY_STAR_RIGHT_SHORT = "starRightShortPress"
    const val KEY_STAR_RIGHT_LONG  = "starRightLongPress"
    const val KEY_ASSISTANT_SHORT  = "assistantShortPress"
    const val KEY_ASSISTANT_LONG   = "assistantLongPress"
    const val KEY_PHONE_SHORT = "phoneShortPress"
    const val KEY_PHONE_LONG = "phoneLongPress"
    const val KEY_CENTER_SHORT = "centerShortPress"
    const val KEY_CENTER_LONG = "centerLongPress"
    const val KEY_VOLUME_UP_SHORT = "volumeUpShortPress"
    const val KEY_VOLUME_UP_LONG = "volumeUpLongPress"
    const val KEY_VOLUME_DOWN_SHORT = "volumeDownShortPress"
    const val KEY_VOLUME_DOWN_LONG = "volumeDownLongPress"
    const val KEY_MEDIA_NEXT_SHORT = "mediaNextShortPress"
    const val KEY_MEDIA_NEXT_LONG = "mediaNextLongPress"
    const val KEY_MEDIA_PREVIOUS_SHORT = "mediaPreviousShortPress"
    const val KEY_MEDIA_PREVIOUS_LONG = "mediaPreviousLongPress"
    const val KEY_MUTE_SHORT = "muteShortPress"
    const val KEY_MUTE_LONG = "muteLongPress"
    const val KEY_UP_SHORT = "upShortPress"; const val KEY_UP_LONG = "upLongPress"
    const val KEY_DOWN_SHORT = "downShortPress"; const val KEY_DOWN_LONG = "downLongPress"
    const val KEY_OK_SHORT = "okShortPress"; const val KEY_OK_LONG = "okLongPress"
    const val KEY_LEFT_SHORT = "leftShortPress"; const val KEY_LEFT_LONG = "leftLongPress"
    const val KEY_RIGHT_SHORT = "rightShortPress"; const val KEY_RIGHT_LONG = "rightLongPress"
    const val KEY_SOURCE_SHORT = "sourceShortPress"; const val KEY_SOURCE_LONG = "sourceLongPress"
    const val KEY_OUTSIDE_TEMP     = "outsideTempC"
    const val KEY_IGNITION         = "ignition"
    const val KEY_IN_PARK          = "inPark"
    const val KEY_SPEED_KMH        = "speedKmh"
    const val KEY_SPEED_READABLE   = "speedReadable"
    const val KEY_DRIVE_MODE       = "driveMode"
    const val KEY_REGEN_LEVEL      = "regenLevel"
    const val KEY_ENERGY_SAVING    = "energySaving"
    const val KEY_AC_ON            = "acOn"
    const val KEY_HVAC_AUTO        = "hvacAuto"
    const val KEY_RECIRC           = "recirc"
    const val KEY_FAN_SPEED        = "fanSpeed"
    const val KEY_TEMPERATURE_SET  = "temperatureSetC"
    const val KEY_WINDOW_OPEN      = "windowOpen"
    const val KEY_SEAT_HEAT_L      = "seatHeatLeft"
    const val KEY_SEAT_HEAT_R      = "seatHeatRight"
    const val KEY_STEERING_HEAT    = "steeringHeat"
    const val KEY_MEDIA_VOLUME     = "mediaVolume"
    const val KEY_MEDIA_VOLUME_MAX = "mediaVolumeMax"
    const val KEY_BRIGHTNESS       = "brightnessPct"
    const val KEY_AEB_ENABLED      = "aebEnabled"
    const val KEY_AEB_MODE         = "aebMode"
    const val KEY_AEB_SENSITIVITY  = "aebSensitivity"
    const val KEY_ELK_MODE         = "elkMode"
    const val KEY_ELK_SENSITIVITY  = "elkSensitivity"
    const val KEY_ACC_TJA_MODE     = "accTjaMode"
    const val KEY_LIMITER_MODE     = "limiterMode"
    const val KEY_TSR              = "tsr"
    const val KEY_OVERSPEED_ALARM  = "overspeedAlarm"
    const val KEY_SPEED_LIMIT_TONE = "speedLimitTone"
    const val KEY_SOUND_WARNING    = "soundWarning"

    // ── Vendor vehicle service (com.evsuite.hardware.saic) ───────────────────────
    // Read from the same service the car's own HVAC and charging screens use, so unlike
    // the AOSP climate ids above these are confirmed rather than inferred.
    const val KEY_CLIMATE_ON       = "climateOn"
    const val KEY_BATTERY_PERCENT  = "batteryPct"
    const val KEY_CHARGING         = "charging"
    const val KEY_CHARGE_LIMIT     = "chargeLimitPct"
    /** Widest-open window, 0–100 %. Replaces the unverified AOSP window read where it answers. */
    const val KEY_WINDOW_PERCENT   = "windowPct"
    const val KEY_DOORS_LOCKED     = "doorsLocked"
    const val KEY_ECON             = "econOn"
    const val KEY_PASSENGER_TEMP   = "passengerTempC"
    const val KEY_FRONT_DEFROST    = "frontDefrost"
    const val KEY_REAR_DEFROST     = "rearDefrost"
    /**
     * The vendor charging state, kept as its own number next to [KEY_CHARGING].
     *
     * [KEY_CHARGING] is a boolean and has to stay one — every rule already written against it
     * reads it as such. But "plugged in" and "charging" are not the same state, and the
     * boolean cannot tell a driver which of the two the car is in. This is the state itself,
     * so a rule can ask.
     */
    const val KEY_CHARGING_STATUS  = "chargingStatus"
    const val KEY_CHARGE_SCHEDULE  = "chargeScheduleOn"
    /** Scheduled charging window, minutes since midnight. */
    const val KEY_CHARGE_WINDOW_START = "chargeWindowStart"
    const val KEY_CHARGE_WINDOW_STOP  = "chargeWindowStop"
    const val KEY_BATTERY_PREHEAT  = "batteryPreheat"

    // ── AOSP door status (SWI133) ────────────────────────────────────────────
    /** True while either front door reads as open. Absent where the property is unreadable. */
    const val KEY_FRONT_DOOR_OPEN  = "frontDoorOpen"
}
