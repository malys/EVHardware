package com.evsuite.hardware.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

/** Bounded app-private history replaced atomically after every successful write. */
class EnergyTripHistoryStore(
    private val target: File,
    private val maxTrips: Int = 50,
    private val gson: Gson = Gson(),
) {
    fun read(): List<EnergyTripSummary> = runCatching {
        if (!target.exists()) return emptyList()
        val type = object : TypeToken<List<EnergyTripSummary>>() {}.type
        gson.fromJson<List<EnergyTripSummary>>(target.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun append(summary: EnergyTripSummary): Boolean {
        val updated = (listOf(summary) + read()).take(maxTrips.coerceAtLeast(1))
        val bytes = gson.toJson(updated).toByteArray(Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }
}
