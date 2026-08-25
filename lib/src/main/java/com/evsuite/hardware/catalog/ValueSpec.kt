package com.evsuite.hardware.catalog

import androidx.annotation.StringRes

/**
 * The kind of value a condition or action manipulates.
 *
 * This is what keeps the UI small: the editor knows no individual condition or action, it
 * only knows how to draw one control per [ValueKind]. Adding a catalogue entry is
 * therefore one enum line, not another screen.
 */
enum class ValueKind {
    /** On/off switch. */
    BOOL,
    /** Bounded slider (see [ValueSpec.min] / [ValueSpec.max]). */
    NUMBER,
    /** Closed list of named values ([ValueSpec.options]). */
    ENUM,
    /** Paired Bluetooth device, identified by MAC address. */
    BT_DEVICE,
    /** Start → end time range. */
    TIME_RANGE,
    /**
     * One time of day, stored as minutes since midnight in `number`.
     *
     * Not a [NUMBER] with a 0…1439 range: the charging window the car answers is a clock
     * time, and a slider asking for "1 380" is unanswerable at the wheel. The editor draws
     * the same picker [TIME_RANGE] uses, minus the second half.
     */
    TIME,
    /** Day-of-week selection. */
    DAYS,
    /** One exact local calendar date, stored as ISO-8601 (yyyy-MM-dd). */
    DATE,
    /** EVProfile driving profile, identified by id. */
    PROFILE,
    /**
     * Another of the user's own rules, identified by id.
     *
     * Not [APP] with a rule id in it: the chooser has to list rules, and a rule that names a
     * rule which no longer exists must be reported as such rather than silently doing nothing.
     */
    RULE,
    /** Installed application, identified by package name. */
    APP,
    /** Phone-book entry; the rule stores the selected number, not a mutable contact id. */
    CONTACT,
    /** Free text (notification message). */
    TEXT,
    /**
     * A navigation destination: an address or "latitude,longitude", in `text`.
     *
     * Free text would have been enough to store it, and was — but nobody types coordinates
     * at the wheel. The kind exists so the editor can offer the two things that make the
     * field answerable there: the car's own position, and the places already saved.
     */
    DESTINATION,
    /** Webhook URL plus an optional POST body. */
    WEBHOOK,
    /**
     * A recipient and a message: the number in `text`, the contact it was picked from in
     * `displayName`, and the message itself in the field a webhook body already uses.
     *
     * Two controls rather than one, and no new model field — a message has an addressee, and
     * it is the same phone-book field [CONTACT] offers, so a number can still be typed where
     * no contact exists.
     */
    SMS,
    /**
     * A yes/no question in `text`, and how long it waits for an answer in `number` seconds.
     *
     * The wait belongs to the rule, not to the app: a question asked before the doors unlock
     * is answered at once or not at all, while one asked at the end of a drive has to survive
     * the driver looking away. `0` — every rule saved before the field existed — means the
     * default wait.
     */
    CONFIRM,
    /**
     * A point and a radius: `text` holds "latitude,longitude", `number` the radius in
     * metres. Two controls rather than one, but no new model field — the flat union in
     * `Condition` already has both.
     */
    LOCATION,
    /** Physical button + short/long event, edited with two dropdowns. */
    PHYSICAL_BUTTON,
    /** Nothing to enter. */
    NONE
}

/** One named value of a [ValueKind.ENUM]. */
data class EnumOption(val value: Int, @StringRes val labelRes: Int)

/**
 * Description of the input control.
 *
 * [max] is -1 when the bound is only known at runtime — the maximum media volume depends
 * on the firmware and is readable only from the vehicle snapshot. The editor substitutes
 * the real value, falling back to [fallbackMax] when the car does not answer.
 */
data class ValueSpec(
    val kind: ValueKind,
    val min: Int = 0,
    val max: Int = 0,
    @StringRes val unitRes: Int = 0,
    val options: List<EnumOption> = emptyList(),
    val fallbackMax: Int = 0,
    /**
     * Placeholder for a free-text field, when the field alone does not say what to type.
     * "103.5" is obvious once you know the box wants a frequency and impossible to guess
     * before that.
     */
    @StringRes val hintRes: Int = 0
) {
    companion object {
        val NONE = ValueSpec(ValueKind.NONE)
        val BOOL = ValueSpec(ValueKind.BOOL)

        fun number(min: Int, max: Int, @StringRes unitRes: Int = 0) =
            ValueSpec(ValueKind.NUMBER, min = min, max = max, unitRes = unitRes)

        /** Upper bound resolved at runtime from the vehicle snapshot. */
        fun dynamicNumber(min: Int, fallbackMax: Int, @StringRes unitRes: Int = 0) =
            ValueSpec(ValueKind.NUMBER, min = min, max = -1, unitRes = unitRes, fallbackMax = fallbackMax)

        fun enum(vararg options: EnumOption) =
            ValueSpec(ValueKind.ENUM, options = options.toList())
    }
}
