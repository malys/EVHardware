package com.evsuite.hardware

import android.content.Context

/**
 * Detects the firmware generation from ro.build.mt2712.version.
 *
 * SWI133 : "SWI133-xxxxx" — ADAS via getMixProperty(0x32), 5 modes, 2 alerts, seat + steering heating
 * SWI132 : "SWI132-xxxxx" — ADAS via CarVehicleSettingClient (acc/tja getAccTjaState), alerts via direct
 *                            IVehicleSettingService binder (TX 0x128/0x12a), same UI section as SWI68
 * SWI68  : "SWI68-xxxxx"  — ADAS via VehicleSettingManager (acc/tja), seat + steering heating
 * SWI69  : "SWI69-xxxxx"  — ADAS via "new-gen" VehicleSettingManager, no seat/steering heating
 * SWI131 : "SWI131-xxxxx" — Same as SWI69 (same package, same API), no seat/steering heating
 * SWI165 : "SWI165-xxxxx" — ADAS via VehicleSettingManager (same SDK as SWI68), AEB via setFcwAlarmMode,
 *                            seat + steering heating available
 * UNKNOWN : unrecognised firmware — the user can force a compatibility mode
 */
object FirmwareInfo {

    enum class Gen { SWI133, SWI132, SWI68, SWI69, SWI131, SWI165, UNKNOWN }

    private const val PREF_NAME       = "ev_settings"
    private const val PREF_FORCED_GEN = "forced_firmware_gen"

    @Volatile private var cached:         Gen?    = null
    @Volatile private var detectedString: String? = null

    /**
     * Call first in MainActivity.onCreate(), before any fragment inflation.
     * Reads the forced choice (if any) from SharedPreferences and applies it to the cache.
     */
    fun initWithContext(context: Context) {
        val forced = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_FORCED_GEN, null) ?: return
        cached = runCatching { Gen.valueOf(forced) }.getOrDefault(Gen.UNKNOWN)
    }

    /** Returns the active generation (forced or auto-detected). */
    fun getGeneration(): Gen {
        cached?.let { return it }
        val version = readProp("ro.build.mt2712.version")
            ?: readProp("ro.build.version.incremental")
        detectedString = version
        val gen = generationOf(version)
        cached = gen
        return gen
    }

    /** Pure generation parser, shared by detection and the fail-closed write policy. */
    internal fun generationOf(version: String?): Gen =
        when {
            version == null               -> Gen.UNKNOWN
            version.startsWith("SWI165")  -> Gen.SWI165
            version.startsWith("SWI133")  -> Gen.SWI133
            version.startsWith("SWI132")  -> Gen.SWI132   // before SWI131 — startsWith("SWI13") would be ambiguous
            version.startsWith("SWI131")  -> Gen.SWI131
            version.startsWith("SWI68")   -> Gen.SWI68
            version.startsWith("SWI69")   -> Gen.SWI69
            else                          -> Gen.UNKNOWN
        }

    /**
     * True only when the firmware string read from the vehicle identifies a supported
     * generation. A forced compatibility choice does not turn an unknown vehicle into a
     * verified one and therefore cannot open the write gate.
     */
    fun isDetectedGenerationSupported(): Boolean =
        generationOf(getDetectedString().takeUnless { it == "?" }) != Gen.UNKNOWN

    /**
     * Raw string read from system properties (e.g. "SWI131-12345-xxx").
     * Useful to show the exact version to the user in the warning dialog.
     *
     * Read here rather than through [getGeneration], which returns early on a forced
     * generation and so never reaches the property. Forcing a compatibility mode changes
     * which code path runs; it does not change which firmware the head unit is running,
     * and that is the one thing this function exists to report.
     */
    fun getDetectedString(): String {
        detectedString?.let { return it }
        val version = readProp("ro.build.mt2712.version")
            ?: readProp("ro.build.version.incremental")
        detectedString = version
        return version ?: "?"
    }

    /**
     * Force the compatibility mode manually.
     * The choice is persisted in SharedPreferences and survives reboots.
     */
    fun forceGeneration(context: Context, gen: Gen) {
        cached = gen
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_FORCED_GEN, gen.name).apply()
    }

    /**
     * Returns true if the compatibility mode was forced manually (as opposed to a
     * successful auto-detection).
     */
    fun isForced(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .contains(PREF_FORCED_GEN)

    // ── Capability helpers ────────────────────────────────────────────────────

    /**
     * SWI69 and SWI131 use the same "new generation" VSM API:
     *   package com.saicmotor.vehiclesetting.service
     *   direct-IBinder constructor
     *   methods: setAccTjaState, setFcwState, setLasWarningSound…
     */
    fun isNewGenVsm(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131
    }

    /**
     * SWI68, SWI69, SWI131 and SWI165 all use the SAIC VehicleSettingManager for ADAS
     * (ACC/TJA) and sound alerts. SWI133 uses VehiclePropertyManager (getMixProperty).
     */
    fun isVsmBased(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI68 || gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132 || gen == Gen.SWI165
    }

    /**
     * SWI133, SWI68 and SWI165 have heated seats and a heated steering wheel.
     * SWI69 and SWI131 are Standard/SE trims without that equipment.
     */
    fun hasHeatFeatures(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI133 || gen == Gen.SWI68 || gen == Gen.SWI165
    }

    /**
     * SWI165 uses the same VehicleSettingManager SDK as SWI68.
     * AEB is controlled by setFcwAlarmMode() (same API as SWI68).
     * setAutoEmergencyBraking() exists in the SDK but is not used by the official app.
     */
    fun isSWI165(): Boolean = getGeneration() == Gen.SWI165

    private fun readProp(key: String): String? = try {
        val sp  = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    } catch (_: Exception) { null }
}
