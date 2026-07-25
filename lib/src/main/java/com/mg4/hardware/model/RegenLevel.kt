package com.mg4.hardware.model

import androidx.annotation.StringRes
import com.mg4.hardware.R

enum class RegenLevel(val value: Int, val label: String, @StringRes val labelRes: Int) {
    LOW(0, "Low", R.string.regen_low),
    MEDIUM(1, "Medium", R.string.regen_medium),
    HIGH(2, "High", R.string.regen_high),
    ADAPTIVE(3, "Adaptive", R.string.regen_adaptive),
    OFF(5, "Off", R.string.regen_off),
    ONE_PEDAL(6, "One Pedal", R.string.regen_one_pedal);

    companion object {
        fun fromValue(v: Int): RegenLevel = values().firstOrNull { it.value == v } ?: MEDIUM
    }
}
