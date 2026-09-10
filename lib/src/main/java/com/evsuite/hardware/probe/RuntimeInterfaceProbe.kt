package com.evsuite.hardware.probe

import android.content.Context
import android.os.IBinder
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicAidl
import com.evsuite.hardware.saic.SaicHub

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
     * Bind the vehicle hub, then let it come up before surveying.
     *
     * Without this every hub name reads [BindVerdict.ABSENT], which is the one false negative
     * this exercise cannot afford: it looks exactly like "this head unit does not publish the
     * interface" and would close questions that were never asked. Binding is asynchronous, so
     * a survey run in the same breath as this call still reads absent — leave a moment, or run
     * it from a capture that has been up for a while.
     */
    fun connect(context: Context) = SaicHub.connect(context)

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
    fun carConfig(): InterfaceProbe = readInts(
        label = "ICarConfigService",
        descriptor = DESC_CONFIG,
        binder = EVHardware.a9ClientBinder(CODE_CONFIG),
        calls = CONFIG_CALLS,
        note = "queryClient(0x2); -1 is the vendor wrapper's error value, not a not-fitted code",
    )

    /**
     * RI-004, the half that has a host: `getCurrentPowerMode` on the hub's screen sub-service.
     *
     * Only the read. The sleep and wake transactions live on this same binder and are
     * deliberately absent. RI-004's other half, the head unit's own power mode, has no host we
     * can name: the hub does not publish it and no service on this platform has been found to
     * answer for it, so there is nothing to bind. That is the ticket's answer for the power
     * half, not a gap in this probe.
     */
    fun vehicleScreen(): InterfaceProbe = readInts(
        label = "IScreenManagerService",
        descriptor = DESC_SCREEN,
        binder = SaicHub.service(HUB_SCREEN),
        calls = mapOf("getCurrentPowerMode" to TX_SCREEN_POWER_MODE),
        note = "hub:vehiclescreen; read only, the sleep and wake transactions are not called",
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
        add(carConfig())
        add(vehicleScreen())
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

    /** A binder that will not name itself was reached and refused, which is not the same as absent. */
    private fun describe(label: String, binder: IBinder?): InterfaceProbe {
        val descriptor = binder?.let { runCatching { it.interfaceDescriptor }.getOrNull() }
        return InterfaceProbe(label, descriptor, verdictFor(binder != null, listOf(descriptor)))
    }

    /** Call each named transaction for an int and record what came back. */
    private fun readInts(
        label: String,
        descriptor: String,
        binder: IBinder?,
        calls: Map<String, Int>,
        note: String,
    ): InterfaceProbe {
        val target = binder
            ?: return InterfaceProbe(label, descriptor, BindVerdict.ABSENT, note = note)
        val values = calls.mapValues { (_, code) ->
            SaicAidl.callInt(target, descriptor, code)?.toString()
        }
        return InterfaceProbe(label, descriptor, verdictFor(true, values.values), values, note)
    }

    /**
     * Adapter service codes to sweep. The low range, because the map is the point — kept
     * explicit and short rather than open-ended.
     */
    private val CODE_SWEEP = (0x0..0x12).toList()

    private val HUB_NAMES = listOf(
        "aircondition", "vehiclecharging", "vehiclecondition",
        "vehiclecontrol", "vehiclesetting", "vehiclescreen",
    )

    private const val HUB_SCREEN = "vehiclescreen"
    private const val CODE_CONFIG = 0x2

    private const val DESC_CONFIG = "com.saicmotor.carapi.config.ICarConfigService"
    private const val DESC_SCREEN = "com.saicmotor.sdk.screen.IScreenManagerService"

    private const val TX_SCREEN_POWER_MODE = 6

    /** The option getters RI-001 names as decisive, with their transaction codes. */
    val CONFIG_CALLS = mapOf(
        "getTpmsConfigData" to 1,
        "getHvacConfigData" to 7,
        "getCarModelConfigData" to 8,
        "getWheelPosition" to 9,
        "getSeatHeatingConfigData" to 23,
        "getCarRegionConfig" to 28,
        "getAmbientLightConfig" to 41,
        "getOnePedalConfig" to 43,
    )
}
