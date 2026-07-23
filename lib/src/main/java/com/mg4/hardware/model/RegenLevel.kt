package com.mg4.hardware.model

enum class RegenLevel(val value: Int, val label: String) {
    LOW(0, "Low"),
    MEDIUM(1, "Medium"),
    HIGH(2, "High"),
    ADAPTIVE(3, "Adaptive"),
    OFF(5, "Off"),
    ONE_PEDAL(6, "One Pedal");

    companion object {
        fun fromValue(v: Int): RegenLevel = values().firstOrNull { it.value == v } ?: MEDIUM
    }
}
