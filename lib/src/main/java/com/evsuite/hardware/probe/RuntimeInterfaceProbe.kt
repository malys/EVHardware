package com.evsuite.hardware.probe

import android.content.Context
import android.os.IBinder
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicAidl
import com.evsuite.hardware.saic.SaicHub
import com.evsuite.hardware.saic.SaicService

/**
 * What happened when we asked a vendor interface whether it exists.
 *
 * The three outcomes are not interchangeable and the `RI-` survey turns on telling them apart.
 * A service that is not on this head unit is a closed question. A service that is there and
 * refuses an unprivileged caller is an open one with a different answer — the capability exists
 * and EVSuite is not allowed it, worth knowing before anyone spends a drive on it. Only a
 * service that answers is worth reading values from.
 */
enum class BindVerdict { ABSENT, DENIED, ANSWERED }

/**
 * One interface, surveyed.
 *
 * [values] holds raw reads keyed by the method that produced them, as strings, because a probe
 * that converts before it records has already made the assumption it exists to test. A null
 * value means the call reached the service and came back empty — a reading, and a different one
 * from the method not being called at all.
 */
data class InterfaceProbe(
    val name: String,
    val descriptor: String?,
    val verdict: BindVerdict,
    val values: Map<String, String?> = emptyMap(),
    val note: String? = null,
)

/**
 * The `RI-` survey: which of the interfaces the tickets name does this head unit publish, and
 * what do they say on this car.
 *
 * Nothing here is production. Every entry point returns a record rather than a value a caller
 * could act on, and none is referenced from any production path in this library — the isolation
 * CP-004 settled on for exactly this. Call it only from an app's unstable capture. A probe
 * result must never become a fallback: the point is to find out whether a value is real, and
 * code consuming it before the answer arrives has skipped the question.
 *
 * **No setter is called from this file, on any interface.** Several of these binders carry
 * writes that would move a car — charge control, screen sleep, focus requests, a vehicle power
 * switch. None is referenced here, so no later edit reaches one by accident from a probe path.
 */
object RuntimeInterfaceProbe {

    /**
     * Bind both hubs, then let them come up before surveying.
     *
     * Without this every hub name reads [BindVerdict.ABSENT], which is the one false negative
     * this exercise cannot afford: it looks exactly like "this head unit does not publish the
     * interface" and would close questions that were never asked. Binding is asynchronous, so
     * a survey run in the same breath as this call still reads absent — leave a moment, or run
     * it from a capture that has been up for a while.
     */
    fun connect(context: Context) {
        SaicHub.connect(context)
        engMode.connect(context)
    }

    /**
     * `queryClient(code)` across [CODE_SWEEP], recording the interface descriptor of whatever
     * comes back.
     *
     * This is the prerequisite the whole series shares. EVHardware uses a handful of codes
     * because a handful were needed and the rest were never established, which is why RI-002
     * knows what it wants to ask the battery service and has nowhere to send it. Asking each
     * code for a binder and reading back its descriptor settles the map on the actual car.
     *
     * Safe in a way a transaction sweep would not be: `queryClient` hands back a handle and
     * [IBinder.getInterfaceDescriptor] asks that handle its own name. Neither is a call *into*
     * the interface, so nothing of unknown meaning is invoked. That distinction is why a sweep
     * is acceptable here and was refused in CP-004.
     */
    fun adapterClientMap(): List<InterfaceProbe> {
        // Asked once, ahead of the sweep: without an adapter client every code below fails
        // before it reaches a binder, and nineteen ABSENT entries then look exactly like a head
        // unit that publishes nothing. That is the false negative RI-002 was nearly closed on.
        val note = if (EVHardware.hasCarAdapterClient()) null else NO_ADAPTER_CLIENT
        return CODE_SWEEP.map { code ->
            describe("queryClient(0x${code.toString(16)})", EVHardware.a9ClientBinder(code), note)
        }
    }

    /**
     * The same question of the vehicle-service hub, which resolves sub-services by name.
     *
     * EVHardware already uses `vehiclesetting` and `aircondition`. `vehiclescreen` is the one
     * RI-004 wants and that nothing in EVSuite has ever asked for.
     */
    fun hubServiceMap(): List<InterfaceProbe> = HUB_NAMES.map { name ->
        describe("hub:$name", SaicHub.service(name))
    }

    /**
     * RI-001: the vehicle option getters, on the config client.
     *
     * Raw integers and nothing else. `-1` comes back from these getters when the call itself
     * fails, so it cannot be read as a not-fitted code — a probe that mapped these to booleans
     * would turn a dead service into "no options fitted".
     */
    fun carConfig(): InterfaceProbe = read(
        label = "ICarConfigService",
        descriptor = DESC_CONFIG,
        binder = EVHardware.a9ClientBinder(CODE_CONFIG),
        calls = CONFIG_CALLS,
        note = "queryClient(0x2); -1 is the vendor wrapper's error value, not a not-fitted code",
    )

    /**
     * RI-004: the head unit's own power state, on the hub's `carPower` sub-service.
     *
     * Three reads and no more. This binder also carries a shutdown request, a restart request
     * and a backlight switch; none is named here. The backlight *status* is a boolean over the
     * wire and arrives as `0` or `1`, which is why it reads through the same integer path as
     * the other two.
     *
     * The screen sub-service is deliberately not probed. It publishes no getter at all — every
     * transaction it answers either moves the screen or registers a callback — so there is
     * nothing on it a read-only survey may call, and guessing a code to find out is the exact
     * thing this file must not do. Observing screen state needs a listener, which is a larger
     * change than a survey and belongs to RI-004's follow-up rather than here.
     */
    fun carPower(): InterfaceProbe = read(
        label = "ICarPowerService",
        descriptor = DESC_CAR_POWER,
        binder = SaicHub.service(HUB_CAR_POWER),
        calls = POWER_CALLS,
        note = "hub:carPower; reads only, the shutdown, restart and backlight writes are not called",
    )

    /**
     * RI-005: what the head unit says its own part numbers are.
     *
     * `FirmwareGen` keys every branch in this library off a generation the project inferred
     * from a build string. These four are identity the vehicle asserts instead, and the reason
     * to read them is not to replace the generation model but to find out whether the two move
     * together. Two cars on one inferred generation with different software part numbers would
     * mean the label is coarser than the behaviour it gates.
     *
     * Reached through the engineering-mode hub, which is a plausible place for an unprivileged
     * caller to be refused. [BindVerdict.DENIED] is a legitimate answer here, not a failure.
     */
    fun deviceIdentity(): InterfaceProbe = read(
        label = "IDIDManager",
        descriptor = DESC_DID,
        binder = engModeService(ENG_DID),
        calls = DID_CALLS,
        note = "engmode hub:did; an engineering interface may legitimately refuse us",
    )

    /**
     * RI-008: a second opinion on vehicle speed.
     *
     * [VehicleWriteGate][com.evsuite.hardware.VehicleWriteGate] refuses writes on a moving car
     * and fails closed when speed is unreadable, off one source whose scale CP-003 settled. One
     * settled source is not a corroborated one. This one reports speed as a float where the
     * existing source is integral, which either confirms the scale independently or exposes a
     * rounding assumption nobody has had to question.
     *
     * The other three reads come along because they cost one transaction each and describe the
     * same moment the speed was taken in — a speed without its gear and power state is harder
     * to interpret afterwards than one with them.
     */
    fun evsVehicleState(): InterfaceProbe = read(
        label = "ICarEvsService",
        descriptor = DESC_EVS,
        binder = EVHardware.a9ClientBinder(CODE_EVS),
        calls = EVS_CALLS,
        note = "queryClient(0x3); speed is a float here and integral in the existing source",
    )

    /**
     * RI-003: the typed property channel, reached by name instead of by reflection.
     *
     * The ticket opens by asking whether the SWI133 acquisition sequence works on SWI68. The
     * hub answers sub-services by name, `vehicleproperty` is one of the names it resolves, and
     * this asks that way round — if it answers here, the reflection path was never the question.
     *
     * The ids are chosen so the capture can be read without a second drive. Four are values the
     * app already reads through `CarPropertyManager`, so agreement or disagreement is checkable
     * against a known good number rather than taken on faith. `EV_BATTERY_AVG_TEMP` is the one
     * SWI68 declares and has never published — if it answers *here*, the battery temperature the
     * energy screen leaves blank has a source. `POWER_MODE_SWITCH` is the SWI133 id, read to find
     * out whether the numbering carries over, and read only: no setter on this interface is
     * named anywhere in this file. [PROP_NOT_PUBLISHED] is an id nothing publishes, and exists
     * to answer scope item 4 — whether absence is distinguishable from a property reading zero.
     *
     * The batch `getPropertys` is not covered: its argument is an int array and its reply a typed
     * list, neither of which the vararg convention carries, and one transaction per id settles
     * the same questions.
     */
    fun vehicleProperty(): InterfaceProbe = read(
        label = "IVehiclePropertyService",
        descriptor = DESC_VEHICLE_PROPERTY,
        binder = SaicHub.service(HUB_VEHICLE_PROPERTY),
        calls = VEHICLE_PROPERTY_CALLS,
        note = "hub:vehicleproperty; getters only, and 0x7FFFFFFF is an id nothing publishes",
    )

    /**
     * RI-002: the charging service's own energy counters, with the validity flags beside them.
     *
     * RI-002 stalled on a unit nobody could prove: a counter read 25.1 where the state of charge
     * said about 18.4, and a number whose unit is a guess must not reach a screen. The interface
     * publishes a `…V` flag next to several of these values, which is the vehicle's own statement
     * about whether the number beside it means anything — reading the pair is what turns the
     * question from an argument into a measurement.
     *
     * Voltage and current come along because the energy screen now derives battery power from the
     * property pair, and this service publishes the same two quantities through a different path.
     * Two routes to one number is how a sign convention gets settled without a lab.
     *
     * Reads only. This binder also carries charge start and stop, the charge current limit and
     * the reservation schedule — a driver could be left plugged in and not charging by a careless
     * transaction code — and none of them is named here.
     */
    fun vehicleCharging(): InterfaceProbe = read(
        label = "IVehicleChargingService",
        descriptor = DESC_VEHICLE_CHARGING,
        binder = SaicHub.service(HUB_VEHICLE_CHARGING),
        calls = CHARGING_CALLS,
        note = "hub:vehiclecharging; the V suffixes are the vehicle's own validity flags",
    )

    /**
     * Can this head unit read the car's diagnostics — the OBD question, asked of the platform.
     *
     * There is no OBD or UDS service anywhere in the head unit's own applications; the vendor
     * side of that question is closed. What is left is `CarDiagnosticManager`, the only
     * OBD-shaped surface AAOS itself defines, and it is gated behind a privileged permission
     * this app will not hold. The expected answer is therefore a refusal — and a recorded
     * refusal is worth more than an assumed one, because it is the difference between "we
     * checked" and "we decided not to look".
     */
    fun carDiagnostics(): InterfaceProbe {
        val bound = EVHardware.isCarBound()
        val manager = EVHardware.carManagerNamed(CAR_DIAGNOSTIC_SERVICE)?.javaClass?.name
        return InterfaceProbe(
            name = "CarDiagnosticManager",
            descriptor = DESC_CAR_DIAGNOSTIC,
            verdict = verdictFor(bound, listOf(manager)),
            values = mapOf("car_bound" to bound.toString(), "diagnostic_manager" to manager),
            note = "android.car DIAGNOSTIC_SERVICE; a privileged permission we do not hold",
        )
    }

    /**
     * Everything above in one pass, for an unstable capture to write out.
     *
     * The two maps come first: on the first car that runs this they are what make the rest of
     * the series answerable, and they are worth having even if every typed read below them
     * comes back empty.
     */
    fun survey(): List<InterfaceProbe> = buildList {
        add(
            InterfaceProbe(
                "firmware", null, BindVerdict.ANSWERED,
                mapOf("generation" to FirmwareInfo.getGeneration().name),
            )
        )
        addAll(adapterClientMap())
        addAll(hubServiceMap())
        addAll(engModeMap())
        add(carConfig())
        add(carPower())
        add(deviceIdentity())
        add(evsVehicleState())
        add(vehicleProperty())
        add(vehicleCharging())
        add(carDiagnostics())
    }

    /**
     * Reached, and said something?
     *
     * The one rule the enum exists for: a binder that came back and then answered nothing at
     * all is [BindVerdict.DENIED], not [BindVerdict.ANSWERED] with a page of nulls. In practice
     * that is a permission refusal or a descriptor mismatch, and recording it as "answered,
     * empty" would let a later session read absence of data as data. One answer is enough to
     * call an interface alive.
     */
    fun verdictFor(reached: Boolean, answers: Collection<String?>): BindVerdict = when {
        !reached -> BindVerdict.ABSENT
        answers.any { it != null } -> BindVerdict.ANSWERED
        else -> BindVerdict.DENIED
    }

    /**
     * The engineering-mode hub's own sub-services, surveyed the same way as the vehicle hub's.
     *
     * A second hub, with the same shape and a different bind point. Only `did` is asked for a
     * value; the rest are named so that the map records whether they are there, which is what
     * makes a later ticket about any of them answerable without another drive.
     */
    fun engModeMap(): List<InterfaceProbe> = ENG_NAMES.map { name ->
        describe("engmode:$name", engModeService(name))
    }

    /** The engineering hub resolves sub-services by name, exactly as the vehicle hub does. */
    private fun engModeService(name: String): IBinder? =
        SaicAidl.callBinder(engMode.binder(), DESC_ENG_MODE, TX_ENG_GET_SERVICE, name)

    private val engMode = SaicService(ENG_PACKAGE, ENG_ACTION, "EV_RI_ENGMODE")

    /** A binder that will not name itself was reached and refused, which is not the same as absent. */
    private fun describe(label: String, binder: IBinder?, note: String? = null): InterfaceProbe {
        val descriptor = binder?.let { runCatching { it.interfaceDescriptor }.getOrNull() }
        return InterfaceProbe(
            label, descriptor, verdictFor(binder != null, listOf(descriptor)), note = note,
        )
    }

    /** How a reply is laid out. A wrong choice here reads a valid parcel as nonsense. */
    internal enum class ReadAs { INT, FLOAT, STRING }

    /**
     * One transaction worth calling, how to read what it sends back, and what it takes.
     *
     * [args] exists for the property channel, where the transaction is `getIntProperty` and the
     * question is the id written into it. Everywhere else it stays empty.
     */
    internal data class Call(
        val code: Int,
        val readAs: ReadAs = ReadAs.INT,
        val args: List<Any> = emptyList(),
    )

    /**
     * Call each named transaction and record what came back, as text.
     *
     * Booleans go through [ReadAs.INT] on purpose: AIDL puts them on the wire as `0` or `1`,
     * and recording the wire value keeps the capture one step away from an interpretation.
     */
    private fun read(
        label: String,
        descriptor: String,
        binder: IBinder?,
        calls: Map<String, Call>,
        note: String,
    ): InterfaceProbe {
        val target = binder
            ?: return InterfaceProbe(label, descriptor, BindVerdict.ABSENT, note = note)
        val values = calls.mapValues { (_, call) ->
            val args = call.args.toTypedArray()
            when (call.readAs) {
                ReadAs.INT -> SaicAidl.callInt(target, descriptor, call.code, *args)?.toString()
                ReadAs.FLOAT -> SaicAidl.callFloat(target, descriptor, call.code, *args)?.toString()
                ReadAs.STRING -> SaicAidl.callString(target, descriptor, call.code, *args)
            }
        }
        return InterfaceProbe(label, descriptor, verdictFor(true, values.values), values, note)
    }

    /**
     * Adapter service codes to sweep. The low range, because the map is the point — kept
     * explicit and short rather than open-ended.
     */
    private val CODE_SWEEP = (0x0..0x12).toList()

    /**
     * What ABSENT means when there is no adapter client to ask. Carried on every swept entry
     * rather than stated once at the top of the bundle: a later session reads one entry, not the
     * preamble, and closing RI-001, RI-002 or RI-008 on a field that was never set is the exact
     * mistake this sentence exists to prevent.
     */
    internal const val NO_ADAPTER_CLIENT =
        "no CarAdapterClient on this firmware — sCarAdapter is assigned only by " +
            "initKatman4Swi69, so this call failed before it reached a binder. ABSENT here is " +
            "not a reading of the vehicle."

    /**
     * Every sub-service name the hub is known to resolve.
     *
     * `vehicleproperty` is the one to watch: a typed property channel reached by name rather
     * than through the reflection path EVHardware uses on SWI133, which is the question RI-003
     * opens with. Whether it answers on the VSM generations is the whole of that ticket.
     */
    private val HUB_NAMES = listOf(
        "aircondition", "carPower", "vehicleAudio", "vehiclecharging", "vehiclecondition",
        "vehiclecontrol", "vehicleproperty", "vehiclescreen", "vehiclesetting", "vehicleTbox",
    )

    /** The engineering hub's sub-services. Only `did` is read; the rest are mapped. */
    private val ENG_NAMES = listOf(
        "avm", "did", "log", "system_hardware", "system_setting", "tuner",
    )

    private const val ENG_PACKAGE = "com.saicmotor.service.engmode"
    private const val ENG_ACTION = "com.saicmotor.service.engmode.EngineeringModeService"
    private const val ENG_DID = "did"
    private const val TX_ENG_GET_SERVICE = 1

    private const val HUB_CAR_POWER = "carPower"
    private const val HUB_VEHICLE_PROPERTY = "vehicleproperty"
    private const val HUB_VEHICLE_CHARGING = "vehiclecharging"
    private const val CODE_CONFIG = 0x2
    private const val CODE_EVS = 0x3
    private const val CAR_DIAGNOSTIC_SERVICE = "DIAGNOSTIC_SERVICE"

    private const val DESC_CONFIG = "com.saicmotor.carapi.config.ICarConfigService"
    private const val DESC_CAR_POWER = "com.saicmotor.sdk.vehiclesettings.ICarPowerService"
    private const val DESC_ENG_MODE = "com.saicmotor.sdk.engmode.IEngineeringMode"
    private const val DESC_DID = "com.saicmotor.sdk.engmode.IDIDManager"
    private const val DESC_EVS = "com.saicmotor.carapi.evs.ICarEvsService"
    private const val DESC_VEHICLE_PROPERTY =
        "com.saicmotor.sdk.vehiclesettings.IVehiclePropertyService"
    private const val DESC_VEHICLE_CHARGING =
        "com.saicmotor.sdk.vehiclesettings.IVehicleChargingService"
    private const val DESC_CAR_DIAGNOSTIC = "android.car.diagnostic.CarDiagnosticManager"

    /** The two typed getters RI-003 needs; every setter on this interface stays unnamed. */
    private const val TX_PROPERTY_GET_INT = 6
    private const val TX_PROPERTY_GET_FLOAT = 5

    /**
     * The transactions on the property interface that write. Named only so a test can prove
     * [VEHICLE_PROPERTY_CALLS] stays clear of them — nothing calls them.
     */
    internal val PROPERTY_WRITES = setOf(1, 2, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

    /**
     * The transactions on the charging interface that change something: charge and discharge
     * control, the current limit, the reservation schedule, the target states of charge and the
     * pile list edits. Named only so a test can prove [CHARGING_CALLS] stays clear of them.
     */
    internal val CHARGING_WRITES = setOf(
        1, 2, 12, 14, 16, 18, 20, 22, 24, 26, 28, 38, 40, 44, 47, 50, 51, 53, 54, 55, 57, 58,
        59, 60, 81,
    )

    /** An id nothing on this firmware publishes, so absence has something to look like. */
    private const val PROP_NOT_PUBLISHED = 0x7FFFFFFF

    /**
     * The transactions on the power interface that change something: the backlight switch, a
     * shutdown request and a restart request. Named here only so a test can prove [POWER_CALLS]
     * stays clear of them — nothing calls them.
     */
    internal val POWER_WRITES = setOf(1, 4, 5)

    /** RI-004's reads. Every other transaction on this interface changes something. */
    internal val POWER_CALLS = mapOf(
        "getBackLightStatus" to Call(2),
        "getBootReason" to Call(3),
        "getCurrentPowerMode" to Call(11),
    )

    /** RI-005's four identity strings. */
    private val DID_CALLS = mapOf(
        "getAssemblyPartNum" to Call(1, ReadAs.STRING),
        "getHardwarePartNum" to Call(2, ReadAs.STRING),
        "getSoftwarePartNum" to Call(3, ReadAs.STRING),
        "getProductSerialNum" to Call(4, ReadAs.STRING),
    )

    /** RI-008's speed, and the three reads that say what moment it was taken in. */
    internal val EVS_CALLS = mapOf(
        "getVehicleSpeed" to Call(38, ReadAs.FLOAT),
        "getReverseReq" to Call(13),
        "getSteeringAngle" to Call(16),
        "getSystemPowerState" to Call(37),
    )

    /**
     * RI-003's reads, keyed by the property each one asks for.
     *
     * The type per id is the one `CarPropertyManager` uses for the same id where the app already
     * reads it, so a disagreement is about the channel and not about how the reply was decoded.
     */
    internal val VEHICLE_PROPERTY_CALLS = mapOf(
        // Four the app already reads another way: the comparison RI-003 scope item 2 asks for.
        "EV_BATTERY_PCT 0x2160F404" to Call(TX_PROPERTY_GET_INT, args = listOf(0x2160F404)),
        "EV_RANGE_KM 0x2140F41C" to Call(TX_PROPERTY_GET_INT, args = listOf(0x2140F41C)),
        "BMS_PACK_VOL 0x2160F406" to Call(TX_PROPERTY_GET_FLOAT, ReadAs.FLOAT, listOf(0x2160F406)),
        "BMS_PACK_CRNT 0x2160F407" to Call(TX_PROPERTY_GET_FLOAT, ReadAs.FLOAT, listOf(0x2160F407)),
        // The two the energy screen has no source for, asked of a channel that has never been tried.
        "EV_BATTERY_AVG_TEMP 0x1160030E" to
            Call(TX_PROPERTY_GET_FLOAT, ReadAs.FLOAT, listOf(0x1160030E)),
        "HVAC_AMBIENT_TEMP 0x1560252A" to
            Call(TX_PROPERTY_GET_FLOAT, ReadAs.FLOAT, listOf(0x1560252A)),
        // Scope item 3: an id that means something on SWI133, read to see what it means here.
        "POWER_MODE_SWITCH 0x6030021" to Call(TX_PROPERTY_GET_INT, args = listOf(0x6030021)),
        // Scope item 4: what absence looks like, so a zero can be told from a silence.
        "unpublished id" to Call(TX_PROPERTY_GET_INT, args = listOf(PROP_NOT_PUBLISHED)),
    )

    /**
     * RI-002's reads: the counters, the validity flag beside each one that has one, and the two
     * quantities the energy screen already derives battery power from.
     */
    internal val CHARGING_CALLS = mapOf(
        "getChargingStatus" to Call(9),
        "getCurrentElectricQuantity" to Call(3, ReadAs.FLOAT),
        "getCurrentElectricQuantityV" to Call(29),
        "getPowerBatteryVol" to Call(64, ReadAs.FLOAT),
        "getPowerBatteryVolV" to Call(65),
        "getChargingCurrent" to Call(11),
        "getAcVoltage" to Call(91, ReadAs.FLOAT),
        "getAcCurrent" to Call(90, ReadAs.FLOAT),
        "getAccConsumptionAfterStart" to Call(74, ReadAs.FLOAT),
        "getTotalConsumptionAfterStart" to Call(76, ReadAs.FLOAT),
        "getTotalRegenEnrgAfterStart" to Call(78, ReadAs.FLOAT),
        "getTotalRegenRngAfterStart" to Call(80, ReadAs.FLOAT),
        "getElecCsumpPerKm" to Call(66, ReadAs.FLOAT),
        "getElecCsumpPerKmV" to Call(67),
        "getElectricityLevel" to Call(92),
        "getDrivingBatteryHeat" to Call(37),
        "getEnergyFlowInfo" to Call(72),
        // RI-009, for the price of one transaction: a typed list puts its element count on the
        // wire first, so reading the reply as an integer says how many charging stations the
        // vehicle's own dataset has here — -1 for a null list, 0 for an empty one. Whether the
        // regional variant answers in Europe at all is the whole of that ticket's first question.
        "getChargingPileList (count)" to Call(61),
    )

    /** The option getters RI-001 names as decisive, with their transaction codes. */
    internal val CONFIG_CALLS = mapOf(
        "getTpmsConfigData" to Call(1),
        "getHvacConfigData" to Call(7),
        "getCarModelConfigData" to Call(8),
        "getWheelPosition" to Call(9),
        "getSeatHeatingConfigData" to Call(23),
        "getCarRegionConfig" to Call(28),
        "getAmbientLightConfig" to Call(41),
        "getOnePedalConfig" to Call(43),
    )
}
