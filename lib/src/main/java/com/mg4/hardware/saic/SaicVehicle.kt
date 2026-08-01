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

/**
 * Windows and door locks, through the same hub as climate and charging.
 *
 * Positions are a **percentage, 0 closed to 100 open**. The scale is not documented in the
 * SDK, but the launcher writes `TAILGATE_LOCK_ON = 100.0f` on the same float signal family
 * and the service rounds rear-door status to two decimals of a 0–100 value
 * (`VehicleControlBinder.isInvalidRearDoorSts`). What makes it safe to act on: the service
 * validates every value against its own maximum and **silently drops anything out of
 * range** — a wrong scale means the window does not move, not that it moves unexpectedly.
 *
 * No standstill gate here. The vehicle enforces its own speed limit on glass and locks, and
 * duplicating it in the app would refuse the legitimate case — closing the windows when it
 * starts raining on the motorway. A write the car declines is reported, not hidden.
 *
 * The electric tailgate is deliberately absent: the launcher defines OPEN and CLOSE as the
 * same value (1.0f), so it is a pulse whose direction depends on state this cannot read.
 * Firing it blind could open a boot at the wrong moment.
 *
 * Source: `apks/vehiclesettingservice_eh32_eu_p` — `IVehicleControlService` (codes) and
 * `VehicleControlBinder` (the CarCabinManager properties behind them);
 * `apks/launcher_eh32_eu_p` — `VehicleConstant` (value semantics).
 */
object SaicVehicleControl {

    private const val NAME = "vehiclecontrol"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleControlService"

    private const val TX_GET_DOOR_LOCK = 3
    private const val TX_SET_DOOR_LOCK = 4
    private const val TX_GET_DRIVER_WINDOW = 5
    private const val TX_SET_DRIVER_WINDOW = 6
    private const val TX_GET_PASSENGER_WINDOW = 7
    private const val TX_SET_PASSENGER_WINDOW = 8
    private const val TX_GET_LEFT_REAR_WINDOW = 9
    private const val TX_SET_LEFT_REAR_WINDOW = 10
    private const val TX_GET_RIGHT_REAR_WINDOW = 11
    private const val TX_SET_RIGHT_REAR_WINDOW = 12

    /** `VehicleConstant.DoorLockStateItem` — 1 locked, 2 unlocked. */
    private const val DOOR_LOCKED = 1
    private const val DOOR_UNLOCKED = 2

    const val WINDOW_CLOSED = 0
    const val WINDOW_OPEN = 100

    private val WINDOW_READS = listOf(
        TX_GET_DRIVER_WINDOW, TX_GET_PASSENGER_WINDOW,
        TX_GET_LEFT_REAR_WINDOW, TX_GET_RIGHT_REAR_WINDOW
    )
    private val WINDOW_WRITES = listOf(
        TX_SET_DRIVER_WINDOW, TX_SET_PASSENGER_WINDOW,
        TX_SET_LEFT_REAR_WINDOW, TX_SET_RIGHT_REAR_WINDOW
    )

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /**
     * The widest-open window, in percent — the one number a rule about glass wants. "Any
     * window open" is `> 0`, and a rule closing them cares about the worst case.
     */
    fun widestWindowPercent(): Int? =
        WINDOW_READS.mapNotNull { code -> SaicAidl.callFloat(binder(), DESCRIPTOR, code) }
            .takeIf { it.isNotEmpty() }
            ?.max()
            ?.toInt()
            ?.takeIf { it >= 0 }

    /** Each window individually, for the diagnostic — this is what confirms the scale. */
    fun windowPercents(): List<Float?> =
        WINDOW_READS.map { code -> SaicAidl.callFloat(binder(), DESCRIPTOR, code) }

    /** Moves all four windows. @return true when every one of them was accepted. */
    fun setAllWindows(percent: Int): Boolean {
        val value = percent.coerceIn(WINDOW_CLOSED, WINDOW_OPEN).toFloat()
        // Every window attempted even if one refuses: three closed beats none closed, and
        // the caller still learns that it was not complete.
        return WINDOW_WRITES.map { code -> SaicAidl.callVoid(binder(), DESCRIPTOR, code, value) }
            .all { it }
    }

    fun doorsLocked(): Boolean? =
        SaicAidl.callInt(binder(), DESCRIPTOR, TX_GET_DOOR_LOCK)
            ?.takeIf { it == DOOR_LOCKED || it == DOOR_UNLOCKED }
            ?.let { it == DOOR_LOCKED }

    fun setDoorsLocked(locked: Boolean): Boolean =
        SaicAidl.callVoid(
            binder(), DESCRIPTOR, TX_SET_DOOR_LOCK,
            if (locked) DOOR_LOCKED else DOOR_UNLOCKED
        )
}

/**
 * The gear, read from the same hub as climate, charging and glass.
 *
 * A second source for something [com.mg4.hardware.MG4Hardware.isVehicleInPark] already
 * reads. It exists because the primary path on SWI68/165 goes through the vendor
 * `VehicleConditionManager` object held in-process, and when that object never arrived the
 * gear was simply unknown — while the very same service was sitting on the hub, answering.
 *
 * Only gear 1 is named, and only because three independent places agree on it: MG4Hardware's
 * own `GEAR_PARK_VALUE`, two head-unit apps using `carGear == 1` as the condition for playing
 * video on the centre screen (`UsbVideoView`, `VideoListFragment`), and
 * `IBackUpBinder.isSecureForBackUp`, which accepts gears 1 and 3 alongside `speed == 0`.
 * Nothing pins 2, 3 and 4, so nothing here names them.
 *
 * `getCarSpeed` is on this interface too and is deliberately unused: the binder returns a
 * float with no unit in any source, and a speed read in the wrong unit would open the write
 * gate at 50 km/h believing it was 14. Speed keeps coming from the AOSP property, whose unit
 * is specified.
 *
 * Source: `apks/vehiclesettingservice_eh32_eu_p` — `IVehicleConditionService` (codes) and
 * `VehicleConditionBinder` (the CarSensorManager signals behind them).
 */
object SaicVehicleCondition {

    private const val NAME = "vehiclecondition"
    private const val DESCRIPTOR = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService"

    private const val TX_GET_CAR_GEAR = 5

    val isAvailable: Boolean get() = binder() != null

    private fun binder(): IBinder? = SaicHub.service(NAME)

    /** Raw gear, or null when unreadable — the binder answers -1 when the signal is absent. */
    fun gearOrNull(): Int? = SaicAidl.callInt(binder(), DESCRIPTOR, TX_GET_CAR_GEAR)?.takeIf { it > 0 }
}
