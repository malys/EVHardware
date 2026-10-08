package com.evsuite.hardware.probe

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The decisions behind [VendorSurfaceProbe], kept free of Android so the JVM tests reach them.
 *
 * Two diagnostic tools stand on these rules (CR-044, CR-045). Both answer "where does this car
 * publish X?" by READING what the runtime already exposes. Neither tool writes anything:
 * - the SOC survey reads every candidate source of state of charge side by side;
 * - the before/after capture reads everything twice — once before the driver flips a setting
 *   on the car's own screen, once after — and lists what changed.
 *
 * Getters are named by the runtime class itself, not by any external catalogue. What changed is
 * a lead to prove per generation before it becomes a supported read or write.
 */
object VendorSurfaceRules {

    /** Read-only name shapes: a getter answers, it does not act. */
    private val GETTER_PREFIXES = listOf("get", "is", "has")

    /**
     * Words that make a "getter" something else: a call that starts, registers, resets or
     * connects can move state even when its name begins with `get`, and one that hands back a
     * service or a listener is plumbing, not a reading. Matched as whole camel-case words, so
     * `getHighBeamSetting` and `isAutoEnabled` stay readings while `getAndReset` does not.
     */
    private val ACTION_WORDS = setOf(
        "set", "reset", "clear", "request", "start", "stop", "connect", "disconnect",
        "register", "unregister", "bind", "unbind", "toggle", "send", "write", "apply", "click",
        "and", "instance", "service", "binder", "context", "listener", "callback", "handler",
        "looper",
    )

    /** `getAndResetCount` → [and, reset, count]. */
    fun words(name: String): List<String> =
        name.split(Regex("(?<=[a-z0-9])(?=[A-Z])|_")).filter { it.isNotEmpty() }
            .map { it.lowercase() }

    /** Only values: anything returning an object could hand back a binder or a context. */
    private val VALUE_TYPES: Set<Class<*>> = setOf(
        Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
        Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!, Short::class.javaPrimitiveType!!,
        Byte::class.javaPrimitiveType!!,
        Int::class.javaObjectType, Long::class.javaObjectType, Float::class.javaObjectType,
        Double::class.javaObjectType, Boolean::class.javaObjectType, Short::class.javaObjectType,
        Byte::class.javaObjectType, String::class.java,
    )

    /** The getters of [cls] safe to call for a reading, sorted by name. */
    fun readableGetters(cls: Class<*>, keywords: List<String> = emptyList()): List<Method> =
        cls.methods
            .filter { isReadableGetter(it) }
            .filter { m -> keywords.isEmpty() || keywords.any { m.name.contains(it, ignoreCase = true) } }
            .distinctBy { it.name }
            .sortedBy { it.name }

    fun isReadableGetter(m: Method): Boolean {
        if (m.parameterCount != 0) return false
        if (!Modifier.isPublic(m.modifiers) || Modifier.isStatic(m.modifiers)) return false
        if (m.declaringClass == Any::class.java) return false
        if (m.returnType !in VALUE_TYPES) return false
        val name = m.name
        val prefix = GETTER_PREFIXES.firstOrNull { name.startsWith(it) && name.length > it.length }
            ?: return false
        if (!name[prefix.length].isUpperCase()) return false
        return words(name.substring(prefix.length)).none { it in ACTION_WORDS }
    }

    data class Change(val key: String, val before: String?, val after: String?)

    /**
     * What differs between two readings. A key present on one side only is a change too: a
     * property that starts answering when a feature is switched on is exactly the lead sought.
     */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<Change> =
        (before.keys + after.keys).sorted()
            .filter { before[it] != after[it] }
            .map { Change(it, before[it], after[it]) }

    /** One line per value, shaped like the rest of the diagnostic report. */
    fun formatDiff(changes: List<Change>, beforeCount: Int, afterCount: Int): String = buildString {
        appendLine("Values read: $beforeCount before, $afterCount after — ${changes.size} changed")
        if (changes.isEmpty()) {
            appendLine("  nothing changed: the setting is not in what this app can read, or the")
            appendLine("  car had not applied it yet when the second capture was taken")
        }
        changes.forEach { appendLine("  ${it.key}: ${it.before ?: "—"} → ${it.after ?: "—"}") }
    }

    /** Arrays print their content; everything else its own text. */
    fun render(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is BooleanArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        is Array<*> -> value.contentDeepToString()
        else -> value.toString()
    }
}
