package com.evsuite.hardware.model

import java.util.UUID

data class DrivingProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val driveMode: DriveMode,
    val regenLevel: RegenLevel,
    val steeringHeat: Boolean = false,
    val seatHeatLeft: Int = 0,        // 0=off, 1, 2, 3
    val seatHeatRight: Int = 0,
    // ADAS SWI133 (Katman4) — default OFF for compatibility with existing profiles
    val overspeedAlarm: Boolean = false,
    val speedLimitTone: Boolean = false,
    val adasMode: Int = 0,            // 0=Off, 1=Limiter, 2=Auto, 3=ACC, 4=ICA
    // ADAS SWI68 — separate fields to isolate per-firmware configurations
    val soundWarning: Boolean = false,
    val swi68AdasMode: Int = 0x4,     // ACC/TJA mode (CarAccTja): 0x4=Off, 0x1=ACC, 0x2=TJA/ICA
    // Speed limiter (SAS) — INDEPENDENT of the ACC/TJA mode (SWI132).
    // swi132LimiterConfigured=false (default + profiles created before this feature) → the
    // limiter is NOT touched when the profile is applied (no regression on car state).
    val swi132LimiterConfigured: Boolean = false,
    val swi132SasMode: Int = 0,       // SAS: 0=Disabled, 2=Manual, 3=Intelligent
    // AEB — front collision avoidance system (common to SWI133 + SWI68)
    val aebEnabled: Boolean = false,   // false=OFF, true=ON
    val aebMode: Int = 1,              // 1=Alert only, 2=Alert + auto braking
    val aebSensitivity: Int = 0,       // 0=not configured, 1=Low, 2=Standard, 3=High (SWI133)
    // ELK — lane departure assist
    val elkMode: Int = 0,              // 0=not configured, 1=OFF, 2=Warn(LDW), 3=Assist(LDP), 5=ELK
    val elkSensitivity: Int = 0,       // 0=not configured, 1=Low, 2=Standard, 3=High
    // ELK SWI132 — sound alert + vibration (SWI132-specific)
    val lasAudibleWarning: Boolean = true,    // true=ON (default ON in the car)
    val lasVibrationReminder: Boolean = true, // true=ON (default ON in the car)
    // Energy saving + TSR
    val energySaving: Boolean = false,
    val tsrEnabled: Boolean = false,
    val isDefault: Boolean = false,
    // [BT-PROFILES] MAC of the Bluetooth device tied to this profile (null = none)
    val btDeviceMac: String? = null
)
