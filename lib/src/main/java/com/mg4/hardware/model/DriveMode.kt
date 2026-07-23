package com.mg4.hardware.model

enum class DriveMode(val value: Int, val label: String) {
    ECO(2, "Eco"),
    NORMAL(3, "Normal"),
    SPORT(4, "Sport"),
    SNOW(6, "Snow"),
    CUSTOM(7, "Custom");

    companion object {
        fun fromValue(v: Int): DriveMode = values().firstOrNull { it.value == v } ?: NORMAL
    }
}
