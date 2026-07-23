package com.mg4.hardware.model

/**
 * Serialized format of the profile backup (a file in the car's storage).
 *
 * [schemaVersion] lets the format evolve without breaking older backups (Gson ignores
 * unknown fields and applies defaults for missing ones).
 */
data class ProfileBackup(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val defaultId: String? = null,
    val profiles: List<DrivingProfile> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
