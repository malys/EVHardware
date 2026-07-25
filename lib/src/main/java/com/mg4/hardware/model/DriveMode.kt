package com.mg4.hardware.model

import androidx.annotation.StringRes
import com.mg4.hardware.R

enum class DriveMode(val value: Int, val label: String, @StringRes val labelRes: Int) {
    ECO(2, "Eco", R.string.drive_eco),
    NORMAL(3, "Normal", R.string.drive_normal),
    SPORT(4, "Sport", R.string.drive_sport),
    SNOW(6, "Snow", R.string.drive_snow),
    CUSTOM(7, "Custom", R.string.drive_custom);

    companion object {
        fun fromValue(v: Int): DriveMode = values().firstOrNull { it.value == v } ?: NORMAL
    }
}
