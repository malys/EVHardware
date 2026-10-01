package com.evsuite.hardware

import java.lang.reflect.Method

/**
 * The three settings the car exposes only in [com.evsuite.hardware.model.DriveMode.CUSTOM]:
 * powertrain response, steering weight and brake-pedal feel (CR-040).
 *
 * Callers speak in an **index** — 0 Eco/Comfort, 1 Normal, 2 Sport — and never in vehicle
 * codes. The codes differ per setting and per platform, and one of them is a trap: the pedal's
 * Normal is `0`, not the middle value. The conversion lives in [code] / [index] and nowhere else.
 *
 * **Per generation.** VSM-based firmwares only, through the same VSM instance [VsmGlass] uses:
 *
 *   • [Family.VSM] — SWI68 / SWI165, `VehicleSettingManager` `…Level` methods
 *   • [Family.A9]  — SWI69 / SWI131 / SWI132, `CarVehicleSettingClient` `…Mode` methods
 *
 * SWI133 and unknown firmware answer [isAvailable] false. Their route is a raw property id, and
 * no id for these settings is verified on this project — writing an unverified id is exactly
 * what the library refuses to do. A method name is different in kind: if it is wrong it does
 * not resolve, and the setting reports unavailable instead of writing somewhere else.
 *
 * **Evidence.** The VSM read/write codes (powertrain and steering 1/2/3, pedal 0/1/2) agree
 * with RI-006. Which pedal code is Comfort and Sport, and the A9 powertrain offset (2/3/4,
 * the drive-mode scale), are community leads (MG4Control 2.6.7, behaviour reference only) and
 * stay unproven until an on-vehicle read-after-write confirms them per generation.
 */
object CustomDrive {

    enum class Setting { POWER, STEERING, PEDAL }

    enum class Family { VSM, A9 }

    /** Positions a caller may ask for. */
    val INDICES = 0..2

    /** Pedal codes by index: Comfort 1, Normal **0**, Sport 2. */
    private val PEDAL_CODES = intArrayOf(1, 0, 2)

    fun familyOf(gen: FirmwareInfo.Gen): Family? = when (gen) {
        FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI165 -> Family.VSM
        FirmwareInfo.Gen.SWI69, FirmwareInfo.Gen.SWI131, FirmwareInfo.Gen.SWI132 -> Family.A9
        else -> null
    }

    /** Vehicle code for [index], or null when [index] is not a position. */
    fun code(setting: Setting, index: Int, family: Family): Int? {
        if (index !in INDICES) return null
        return when (setting) {
            Setting.POWER -> index + if (family == Family.A9) 2 else 1
            Setting.STEERING -> index + 1
            Setting.PEDAL -> PEDAL_CODES[index]
        }
    }

    /** Index for a code read from the car, or null when the code is no known position. */
    fun index(setting: Setting, code: Int, family: Family): Int? =
        INDICES.firstOrNull { code(setting, it, family) == code }

    fun methodName(setting: Setting, family: Family, write: Boolean): String {
        val stem = when (family) {
            Family.VSM -> when (setting) {
                Setting.POWER -> "ElectricPowertrainLevel"
                Setting.STEERING -> "SteeringLevel"
                Setting.PEDAL -> "BrakePedalLevel"
            }
            Family.A9 -> when (setting) {
                Setting.POWER -> "DrivingPowerTrainMode"
                Setting.STEERING -> "SteeringMode"
                Setting.PEDAL -> "BrakePedalMode"
            }
        }
        return (if (write) "set" else "get") + stem
    }

    private val INT = Int::class.javaPrimitiveType!!

    // Resolved once per VSM class, like VsmGlass: a profile apply reads and writes all three.
    private var owner: Class<*>? = null
    private val methods = HashMap<String, Method?>()

    @Synchronized
    private fun method(vsm: Any, name: String, vararg types: Class<*>): Method? {
        if (owner !== vsm.javaClass) {
            owner = vsm.javaClass
            methods.clear()
        }
        return methods.getOrPut(name) {
            try {
                vsm.javaClass.getMethod(name, *types)
            } catch (e: Exception) {
                AppLogger.w(TAG, "  VSM: $name absent — ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun family(): Family? = familyOf(FirmwareInfo.getGeneration())

    /** True when this firmware's VSM carries the setter for [setting]. */
    fun isAvailable(setting: Setting): Boolean {
        val family = family() ?: return false
        val vsm = EVHardware.vsmInstance() ?: return false
        return method(vsm, methodName(setting, family, write = true), INT) != null
    }

    /**
     * The car's current position for [setting], or null when it cannot tell.
     *
     * Null covers an absent method, a failed call, and a code outside the scale — the last one
     * is logged raw, because it is the evidence a wrong scale would leave behind.
     */
    fun read(setting: Setting): Int? {
        val family = family() ?: return null
        val vsm = EVHardware.vsmInstance() ?: return null
        val name = methodName(setting, family, write = false)
        val get = method(vsm, name) ?: return null
        val raw = try {
            (get.invoke(vsm) as? Number)?.toInt()
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VSM: $name() exc: ${e.message}")
            null
        } ?: return null
        return index(setting, raw, family).also {
            if (it == null && raw >= 0) AppLogger.w(TAG, "  $name() = $raw — outside the $family scale")
        }
    }

    /**
     * Sets [setting] to position [index]. The car obeys only while it is in CUSTOM; the caller
     * decides when that is (EVProfile shows the controls only then).
     */
    @RequiresStandstill
    fun write(setting: Setting, index: Int): Boolean {
        val family = family() ?: return false
        val value = code(setting, index, family) ?: return false
        val vsm = EVHardware.vsmInstance() ?: return false
        val name = methodName(setting, family, write = true)
        val set = method(vsm, name, INT) ?: return false
        // [T-904] Vehicle write: allowed only when stopped, refused if speed unreadable.
        if (!VehicleWriteGate.allow("VSM $name")) return false
        return try {
            set.invoke(vsm, value)
            AppLogger.i(TAG, "  $name($value) ← index $index ($family)")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VSM: $name($value) exc: ${e.message}")
            false
        }
    }

    private const val TAG = "EV_CUSTOM"
}
