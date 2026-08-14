package com.evsuite.hardware

/**
 * MG4 firmware generations, mirroring `FirmwareInfo.Gen` in EVProfile.
 *
 * The names match the strings EVProfile sends in the snapshot
 * ([com.evsuite.hardware.catalog.SnapshotKeys.KEY_FIRMWARE_GEN]), so
 * `FirmwareGen.valueOf(snapshotString)` round-trips.
 */
enum class FirmwareGen { SWI133, SWI132, SWI68, SWI69, SWI131, SWI165 }
