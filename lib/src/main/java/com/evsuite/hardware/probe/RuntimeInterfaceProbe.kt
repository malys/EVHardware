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
    fun adapterClientMap(): List<InterfaceProbe> = CODE_SWEEP.map { code ->
        describe("queryClient(0x${code.toString(16)})", EVHardware.a9ClientBinder(code))
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
    private fun describe(label: String, binder: IBinder?): InterfaceProbe {
        val descriptor = binder?.let { runCatching { it.interfaceDescriptor }.getOrNull() }
        return InterfaceProbe(label, descriptor, verdictFor(binder != null, listOf(descriptor)))
    }

    /** How a reply is laid out. A wrong choice here reads a valid parcel as nonsense. */
    internal enum class ReadAs { INT, FLOAT, STRING }

    /** One transaction worth calling, and how to read what it sends back. */
    internal data class Call(val code: Int, val readAs: ReadAs = ReadAs.INT)

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
            when (call.readAs) {
                ReadAs.INT -> SaicAidl.callInt(target, descriptor, call.code)?.toString()
                ReadAs.FLOAT -> SaicAidl.callFloat(target, descriptor, call.code)?.toString()
                ReadAs.STRING -> SaicAidl.callString(target, descriptor, call.code)
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
    private const val CODE_CONFIG = 0x2
    private const val CODE_EVS = 0x3

    private const val DESC_CONFIG = "com.saicmotor.carapi.config.ICarConfigService"
    private const val DESC_CAR_POWER = "com.saicmotor.sdk.vehiclesettings.ICarPowerService"
    private const val DESC_ENG_MODE = "com.saicmotor.sdk.engmode.IEngineeringMode"
    private const val DESC_DID = "com.saicmotor.sdk.engmode.IDIDManager"
    private const val DESC_EVS = "com.saicmotor.carapi.evs.ICarEvsService"

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
