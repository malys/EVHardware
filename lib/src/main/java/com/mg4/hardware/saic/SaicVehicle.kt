package com.mg4.hardware.saic

import android.content.Context
import android.os.IBinder

/**
 * The vendor vehicle service the head unit's HVAC and charging screens talk to.
 *
 * One bound service exposes a *hub*: `getService(name)` hands back the binder for a named
 * sub-service. Both consumers below go through it, so the bind happens once.
 *
 * Source: `apks/hvac_eh32_eu_p` — `com.saicmotor.sdk.vehiclesettings.manager.BaseManager`
 * (bind target), `IHubService` (the lookup), `AirConditionManager` and
 * `VehicleChargingManager` (the two names used here).
 */
object SaicHub {

    private const val PACKAGE = "com.saicmotor.service.vehicle"
    private const val ACTION = "com.saicmotor.service.vehicle.VehicleService"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IHubService"
    private const val TX_GET_SERVICE = 1

    private val service = SaicService(PACKAGE, ACTION, "vehicle-hub")
    private val resolved = HashMap<String, IBinder>()

    val isReady: Boolean get() = service.isReady

    fun connect(context: Context) = service.connect(context)

    /** Sub-service binder, resolved once and re-resolved after the hub reconnects. */
    @Synchronized
    fun service(name: String): IBinder? {
        val hub = service.binder() ?: run { resolved.clear(); return null }
        resolved[name]?.takeIf { it.isBinderAlive }?.let { return it }
        val sub = SaicAidl.callBinder(hub, DESCRIPTOR, TX_GET_SERVICE, name) ?: return null
        resolved[name] = sub
        return sub
    }
}

/**
 * Climate control, as the stock HVAC app performs it.
 *
 * This is the write path the project deliberately did without until now: MG4Hardware could
 * only *read* climate through standard AOSP property ids that the R69 sources name but that
 * no MG4 confirmed, so there was no honest way to change anything. These calls are the ones
 * the car's own HVAC screen makes, so what they do is not in doubt — only whether the
 * service answers on a given firmware, which the Diagnostic tab now reports.
 *
 * Temperatures are whole degrees Celsius. The stock UI clamps to 17–33, where the ends read
 * as LO and HI (`HvacActivity.onLongTouch`), so the same bounds apply here.
 */
object SaicClimate {

    private const val NAME = "aircondition"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IAirConditionService"

    const val TEMP_MIN = 17
    const val TEMP_MAX = 33

    // Transaction codes from IAirConditionService.Stub.
    private const val TX_SET_POWER = 4
    private const val TX_OPEN_POWER = 5
    private const val TX_CLOSE_POWER = 6
    private const val TX_SET_AUTO = 7
    private const val TX_SET_AC = 8
    private const val TX_SET_LOOP_MODE = 9
    private const val TX_SET_ECON = 13
    private const val TX_SET_AIR_VOLUME = 15
    private const val TX_SET_DRV_TEMP = 41
    private const val TX_SET_PSG_TEMP = 42
    private const val TX_OPEN_FRONT_DEFROST = 48
    private const val TX_CLOSE_FRONT_DEFROST = 49
    private const val TX_OPEN_BACK_DEFROST = 50
    private const val TX_CLOSE_BACK_DEFROST = 51
    private const val TX_GET_POWER = 53
    private const val TX_GET_AUTO = 54
    private const val TX_GET_AC = 55
    private const val TX_GET_LOOP_MODE = 56
    private const val TX_GET_ECON = 57
    private const val TX_GET_AIR_VOLUME = 59
    private const val TX_GET_MAX_AIR_VOLUME = 60
    private const val TX_GET_DRV_TEMP = 81
    private const val TX_GET_PSG_TEMP = 82
    private const val TX_GET_OUT_CAR_TEMP = 88
    private const val TX_GET_FRONT_DEFROST = 92
    private const val TX_GET_BACK_DEFROST = 93

    /** Loop mode: 0 = fresh air, 1 = recirculation (`openLoopInner` / `openLoopOutside`). */
    const val LOOP_OUTSIDE = 0
    const val LOOP_INNER = 1

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    // ── Writes ───────────────────────────────────────────────────────────────
    fun setPower(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_POWER else TX_CLOSE_POWER)

    fun setAc(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AC, if (on) 1 else 0)

    fun setAuto(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AUTO, if (on) 1 else 0)

    fun setEcon(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_ECON, if (on) 1 else 0)

    fun setRecirculation(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_LOOP_MODE, if (on) LOOP_INNER else LOOP_OUTSIDE)

    fun setFanLevel(level: Int): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_AIR_VOLUME, level)

    /** Driver-side target. The passenger side follows unless dual zone is on. */
    fun setDriverTemp(celsius: Int): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_DRV_TEMP, celsius.coerceIn(TEMP_MIN, TEMP_MAX))

    fun setPassengerTemp(celsius: Int): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_PSG_TEMP, celsius.coerceIn(TEMP_MIN, TEMP_MAX))

    fun setFrontDefrost(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_FRONT_DEFROST else TX_CLOSE_FRONT_DEFROST)

    fun setRearDefrost(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, if (on) TX_OPEN_BACK_DEFROST else TX_CLOSE_BACK_DEFROST)

    // ── Reads (null = the service did not answer) ────────────────────────────
    // The service answers -1 for a signal it holds no value for, which is not a state.
    fun powerOn(): Boolean? = flag(TX_GET_POWER)
    fun acOn(): Boolean? = flag(TX_GET_AC)
    fun autoOn(): Boolean? = flag(TX_GET_AUTO)
    fun econOn(): Boolean? = flag(TX_GET_ECON)
    fun recirculationOn(): Boolean? = level(TX_GET_LOOP_MODE)?.let { it == LOOP_INNER }
    fun fanLevel(): Int? = level(TX_GET_AIR_VOLUME)
    fun fanLevelMax(): Int? = level(TX_GET_MAX_AIR_VOLUME)
    fun driverTemp(): Int? = level(TX_GET_DRV_TEMP)
    fun passengerTemp(): Int? = level(TX_GET_PSG_TEMP)
    fun frontDefrostOn(): Boolean? = flag(TX_GET_FRONT_DEFROST)
    fun rearDefrostOn(): Boolean? = flag(TX_GET_BACK_DEFROST)
    fun outsideTempCelsius(): Float? = SaicAidl.callFloat(binder(), DESCRIPTOR, TX_GET_OUT_CAR_TEMP)

    private fun level(code: Int): Int? =
        SaicAidl.callInt(binder(), DESCRIPTOR, code)?.takeIf { it >= 0 }

    private fun flag(code: Int): Boolean? = level(code)?.let { it == 1 }
}

/**
 * Charging and battery settings, as the stock charging screen performs them.
 *
 * `ChargingCloseSoc` is the charge limit in percent, `ReserChrg*` the scheduled window, and
 * `DrivingBatteryHeat` the battery pre-heat switch — the three things the vehicle's own
 * screen offers and the only ones exposed here.
 *
 * Source: `apks/hvac_eh32_eu_p` — `VehicleChargingManager` and `IVehicleChargingService`.
 */
object SaicCharging {

    private const val NAME = "vehiclecharging"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleChargingService"

    private const val TX_GET_SOC = 3
    private const val TX_GET_CHARGING_STATUS = 9
    private const val TX_GET_CLOSE_SOC = 13
    private const val TX_SET_CLOSE_SOC = 14
    private const val TX_GET_CONTROL_SWITCH = 17
    private const val TX_SET_CONTROL_SWITCH = 18
    private const val TX_GET_RESERVE_CONTROL = 19
    private const val TX_SET_RESERVE_CONTROL = 20
    private const val TX_GET_RESERVE_START_HOUR = 21
    private const val TX_SET_RESERVE_START_HOUR = 22
    private const val TX_GET_RESERVE_START_MINUTE = 23
    private const val TX_SET_RESERVE_START_MINUTE = 24
    private const val TX_GET_RESERVE_STOP_HOUR = 25
    private const val TX_SET_RESERVE_STOP_HOUR = 26
    private const val TX_GET_RESERVE_STOP_MINUTE = 27
    private const val TX_SET_RESERVE_STOP_MINUTE = 28
    private const val TX_GET_BATTERY_HEAT = 37
    private const val TX_SET_BATTERY_HEAT = 38

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /** State of charge in percent. */
    fun stateOfChargePercent(): Float? =
        SaicAidl.callFloat(binder(), DESCRIPTOR, TX_GET_SOC)?.takeIf { it >= 0f }

    fun chargingStatus(): Int? = read(TX_GET_CHARGING_STATUS)

    /** Charge limit in percent — charging stops here. */
    fun chargeLimitPercent(): Int? = read(TX_GET_CLOSE_SOC)
    fun setChargeLimitPercent(percent: Int): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_CLOSE_SOC, percent.coerceIn(0, 100))

    fun chargingEnabled(): Boolean? = read(TX_GET_CONTROL_SWITCH)?.let { it == 1 }
    fun setChargingEnabled(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_CONTROL_SWITCH, if (on) 1 else 0)

    fun scheduleEnabled(): Boolean? = read(TX_GET_RESERVE_CONTROL)?.let { it == 1 }
    fun setScheduleEnabled(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_RESERVE_CONTROL, if (on) 1 else 0)

    fun scheduleStartMinutes(): Int? = minutes(TX_GET_RESERVE_START_HOUR, TX_GET_RESERVE_START_MINUTE)
    fun scheduleStopMinutes(): Int? = minutes(TX_GET_RESERVE_STOP_HOUR, TX_GET_RESERVE_STOP_MINUTE)

    /** Hour and minute are two separate signals; both must land for the window to change. */
    fun setScheduleStart(minutesOfDay: Int): Boolean =
        setTime(TX_SET_RESERVE_START_HOUR, TX_SET_RESERVE_START_MINUTE, minutesOfDay)

    fun setScheduleStop(minutesOfDay: Int): Boolean =
        setTime(TX_SET_RESERVE_STOP_HOUR, TX_SET_RESERVE_STOP_MINUTE, minutesOfDay)

    fun batteryPreheatOn(): Boolean? = read(TX_GET_BATTERY_HEAT)?.let { it == 1 }
    fun setBatteryPreheat(on: Boolean): Boolean =
        SaicAidl.callVoid(binder(), DESCRIPTOR, TX_SET_BATTERY_HEAT, if (on) 1 else 0)

    private fun read(code: Int): Int? =
        SaicAidl.callInt(binder(), DESCRIPTOR, code)?.takeIf { it >= 0 }

    private fun minutes(hourCode: Int, minuteCode: Int): Int? {
        val hour = read(hourCode) ?: return null
        val minute = read(minuteCode) ?: return null
        return hour * 60 + minute
    }

    private fun setTime(hourCode: Int, minuteCode: Int, minutesOfDay: Int): Boolean {
        val clamped = minutesOfDay.coerceIn(0, 24 * 60 - 1)
        val hourOk = SaicAidl.callVoid(binder(), DESCRIPTOR, hourCode, clamped / 60)
        val minuteOk = SaicAidl.callVoid(binder(), DESCRIPTOR, minuteCode, clamped % 60)
        return hourOk && minuteOk
    }
}
