package com.mg4.hardware.catalog

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
    const val KEY_FIRMWARE_GEN     = "firmwareGen"
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

    // ── Vendor vehicle service (com.mg4.hardware.saic) ───────────────────────
    // Read from the same service the car's own HVAC and charging screens use, so unlike
    // the AOSP climate ids above these are confirmed rather than inferred.
    const val KEY_CLIMATE_ON       = "climateOn"
    const val KEY_BATTERY_PERCENT  = "batteryPct"
    const val KEY_CHARGING         = "charging"
    const val KEY_CHARGE_LIMIT     = "chargeLimitPct"
}
