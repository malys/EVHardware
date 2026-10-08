package com.evsuite.hardware.probe

import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicCharging
import java.lang.reflect.InvocationTargetException

/**
 * Two read-only diagnostic tools, for an app's unstable capture only (same isolation as
 * [RuntimeInterfaceProbe]): nothing in production calls this, and **no setter is called from
 * this file** — only the getters [VendorSurfaceRules.readableGetters] admits.
 *
 * 1. [socSourcesReport] (CR-044): every place state of charge might come from, side by side,
 *    for the generations where the uploader reads none.
 * 2. [capture] + [VendorSurfaceRules.diff] (CR-045): read everything, let the driver flip a
 *    setting on the car's own screen at standstill, read again, list what changed. That is how
 *    an unknown setting (Automatic High Beam) is found on a given firmware without taking an
 *    identifier from anybody else's code. It works for any setting, and across a SOC change
 *    too: capture, drive a few kilometres, capture again.
 *
 * Every value printed is a candidate. Only a lead proven per generation becomes a read path.
 */
object VendorSurfaceProbe {

    private const val TAG = "EV_SURVEY"

    /** Longest a capture may take before it stops and says so. */
    const val BUDGET_MS = 20_000L

    /** Getter-name words that may carry battery state, for the SOC survey. */
    val SOC_WORDS = listOf(
        "soc", "battery", "batt", "bms", "energy", "endurance", "range", "electric",
        "charge", "mileage",
    )

    /**
     * Everything readable, keyed by source: every CarPropertyManager property (when
     * [words] is empty) and every value getter of the vendor setting objects EVHardware holds.
     * Call off the main thread: these are binder calls.
     */
    fun capture(words: List<String> = emptyList()): Map<String, String> {
        val deadline = System.currentTimeMillis() + BUDGET_MS
        val out = sortedMapOf<String, String>()
        if (words.isEmpty()) out += EVHardware.readAllCarProperties(deadline)
        for ((name, handle) in EVHardware.vendorSettingHandles()) {
            for (getter in VendorSurfaceRules.readableGetters(handle.javaClass, words)) {
                if (System.currentTimeMillis() > deadline) {
                    out["$name ~"] = "time budget spent"
                    break
                }
                out["$name.${getter.name}"] = try {
                    VendorSurfaceRules.render(getter.invoke(handle))
                } catch (e: InvocationTargetException) {
                    "ERR ${e.targetException?.javaClass?.simpleName}"
                } catch (e: Exception) {
                    "ERR ${e.javaClass.simpleName}"
                }
            }
        }
        AppLogger.i(TAG, "capture(${words.joinToString()}) → ${out.size} values")
        return out
    }

    /** CR-044: where state of charge can come from on this car, and what each source says. */
    fun socSourcesReport(): String = buildString {
        appendLine("─── SOC sources (CR-044) — ${FirmwareInfo.getGeneration()} ───")
        appendLine("SaicCharging hub: ${if (SaicCharging.isAvailable) "bound" else "absent or not bound"}" +
            " → SOC ${SaicCharging.stateOfChargePercent() ?: "null"}")
        appendLine("Vendor SOC property (SWI68 path) → ${EVHardware.getVendorBatterySocPercent() ?: "null"}")
        EVHardware.probeTelemetryProperties()
            .filter { it.name in setOf("EV_BATTERY_LEVEL", "INFO_EV_BATTERY_CAPACITY", "RANGE_REMAINING") }
            .forEach { appendLine(it.toString()) }
        val getters = capture(SOC_WORDS)
        appendLine("Vendor getters with battery words: ${getters.size}")
        getters.forEach { (key, value) -> appendLine("  $key = $value") }
        appendLine("Candidates only. To find SOC among unnamed properties, use the before/after")
        appendLine("capture across a SOC change (a few km, or a charge).")
    }
}
