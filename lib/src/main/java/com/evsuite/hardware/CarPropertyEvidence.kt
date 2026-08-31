package com.evsuite.hardware

/** Exact interpretation used when a trip integrates a validated battery-power signal. */
data class BatteryPowerEvidence(
    val firmware: FirmwareInfo.Gen,
    val conversionVersion: Int,
) {
    init {
        require(firmware != FirmwareInfo.Gen.UNKNOWN) { "power evidence names a known firmware" }
        require(conversionVersion > 0) { "power conversion version is positive" }
    }

    companion object {
        /** EV_INSTANTANEOUS_CHARGE_RATE mW × -1e-6: battery output positive. */
        const val OUTPUT_POSITIVE_MW_V1 = 1
    }
}

/**
 * What a `CarPropertyValue` proves, and what it only appears to prove.
 *
 * `CarPropertyManager.getFloatProperty` hands back a bare number. A generation that declares
 * a property in its VHAL and never publishes a value for it answers that call with a zero
 * shaped exactly like a measured zero — which is how an MG4 reporting 31 °C outside came to
 * show a 0 °C traction battery on the dashboard, and a 0 kW pack current next to it.
 *
 * `getProperty` answers with the value *and* the status the VHAL attaches to it. Only
 * [STATUS_AVAILABLE] means the vehicle stands behind the number; the other two states are the
 * platform saying it has nothing to offer, and this library's contract is that "nothing" stays
 * null all the way to the screen instead of arriving there as a zero.
 *
 * The rule lives apart from the reflection in [EVHardware] so it can be tested on the JVM:
 * `android.car` is not on the compile classpath and cannot be exercised by a unit test.
 */
object CarPropertyEvidence {

    /** Signals whose scale and semantics require per-firmware vehicle evidence. */
    enum class Signal {
        BATTERY_POWER_KW,
        BATTERY_TEMPERATURE_CELSIUS,
    }

    /** `CarPropertyValue.STATUS_AVAILABLE` — the vehicle stands behind this value. */
    const val STATUS_AVAILABLE = 0

    /** `CarPropertyValue.STATUS_UNAVAILABLE` — the property is declared but not published. */
    const val STATUS_UNAVAILABLE = 1

    /** `CarPropertyValue.STATUS_ERROR` — the vehicle reports the signal itself as faulty. */
    const val STATUS_ERROR = 2

    /**
     * The value when the vehicle stands behind it, null otherwise.
     *
     * The publication timestamp is deliberately not part of the decision. Some properties are
     * published once and never again — a battery capacity is the same number at every sample —
     * so an age rule would discard perfectly good readings. The timestamp is carried into the
     * diagnostics report instead, where a person reads it against the rest of the picture.
     */
    fun accept(value: Any?, status: Int): Any? = value?.takeIf { status == STATUS_AVAILABLE }

    fun describe(status: Int): String = when (status) {
        STATUS_AVAILABLE -> "available"
        STATUS_UNAVAILABLE -> "unavailable"
        STATUS_ERROR -> "error"
        else -> "status $status"
    }

    /**
     * True only after CP-003 has captured and reviewed this signal on this exact generation.
     *
     * The catalogue deliberately starts empty. A public property declaration and a finite
     * runtime value do not prove the scale or sign on an MG4, and evidence from one generation
     * must never be generalized to another. CP-003 adds generations here one by one.
     */
    fun isValidated(signal: Signal, firmware: FirmwareInfo.Gen): Boolean = when (signal) {
        Signal.BATTERY_POWER_KW -> batteryPowerEvidence(firmware) != null
        Signal.BATTERY_TEMPERATURE_CELSIUS -> firmware in batteryTemperatureFirmware
    }

    /** Null until CP-003 proves the exact generation and conversion. */
    fun batteryPowerEvidence(firmware: FirmwareInfo.Gen): BatteryPowerEvidence? =
        batteryPowerEvidenceByFirmware[firmware]

    private val batteryPowerEvidenceByFirmware: Map<FirmwareInfo.Gen, BatteryPowerEvidence> =
        emptyMap()

    /** Exact generations whose battery-temperature unit and semantics CP-003 has proved. */
    private val batteryTemperatureFirmware: Set<FirmwareInfo.Gen> = emptySet()
}

/**
 * One line per distinct read failure instead of one per sample.
 *
 * A dashboard samples every second, and a property the firmware does not answer fails the same
 * way every single time. One unreadable speed property wrote two identical lines a second and
 * pushed everything else out of the 400-entry buffer, so the diagnostics screen showed that one
 * failure and nothing else — the log was busiest exactly when it was least useful. A repeat
 * earns a line only when what it says has changed.
 */
object ReadFailureLog {

    private val lock = Any()
    private val lastReason = HashMap<String, String>()

    /** True the first time [key] fails this way, and again whenever the reason changes. */
    fun isNew(key: String, reason: String): Boolean = synchronized(lock) {
        lastReason.put(key, reason) != reason
    }

    /** A property that starts answering again must not silence its next failure. */
    fun clear(key: String) {
        synchronized(lock) { lastReason.remove(key) }
    }

    fun reset() {
        synchronized(lock) { lastReason.clear() }
    }
}
