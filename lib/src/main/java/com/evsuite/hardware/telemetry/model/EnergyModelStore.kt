package com.evsuite.hardware.telemetry.model

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/** One small fitted model, replaced atomically; no history or raw samples are duplicated. */
class EnergyModelStore(
    private val target: File,
    private val gson: Gson = Gson(),
) {
    fun read(): EnergyModel? {
        if (!target.isFile || target.length() !in 1..MAX_MODEL_BYTES.toLong()) return null
        val model = runCatching { gson.fromJson(target.readText(), EnergyModel::class.java) }
            .getOrNull() ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (model.evidence == null || model.envelope == null || !model.isValid()) return null
        return model
    }

    fun write(model: EnergyModel): Boolean {
        if (!model.isValid()) return false
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val bytes = gson.toJson(model).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MODEL_BYTES) return false
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            temp.renameTo(target)
        } catch (_: Exception) {
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        const val MAX_MODEL_BYTES = 16 * 1024
    }
}
