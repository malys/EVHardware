# AGENTS.md — MG4Hardware

Shared vehicle-access library for the SAIC MG4 (AAOS 9, MT2712), consumed by MG4Control,
MG4Tasker and MG4ABRPUploader via git submodule + Gradle subproject.

Commit author: malys.training@gmail.com

## Why this exists

One implementation of the reflection hardware layer, the 0 km/h gate, the models, and the
condition/action catalogue — so the apps do not each re-derive vehicle access and drift.
The apps are thin; the vehicle lives here.

## Non-negotiables

- **The 0 km/h gate is here.** `VehicleWriteGate` refuses road-behaviour writes above
  0 km/h and fails closed on unreadable speed, inside the low-level write primitives. Every
  gated setter carries `@RequiresStandstill`; `RequiresStandstillTest`-style coverage keeps
  the set honest. Comfort writes are not gated. Park rescues the unreadable-speed refusal
  and nothing else — it must never override a speed that did read.
- **Per-firmware routing, never universal.** SWI68/69/131/132/133/165 differ; dispatch
  through `FirmwareInfo`. Any new vehicle call must branch per generation.
- **Unreadable ≠ a value.** Getters return null / -1 when the layer is not ready or the
  property is absent; callers must treat that as "unknown", never as 0/false.
- **`@SupportedOn` is the source of truth** for `docs/firmware-matrix.md` (generated, never
  hand-edited) and for consumers' runtime filters. A vehicle catalogue entry without it
  fails the tests.
- **Never write an unverified AOSP id.** The climate and window ids currently known are
  used for reads only. Writes go through `saic.*`, with every constant documented and
  validated per supported firmware generation.
- **English only** — code, comments, commits, docs. User strings in `values/` (English) +
  `values-fr/`.

## Consuming it

Submodule at `./MG4Hardware`; `settings.gradle.kts` includes `MG4Hardware/lib` as
`:mg4hardware`; app depends on `project(":mg4hardware")`. The library declares no
permissions and no `sharedUserId` — vehicle-write capability is the app's platform-signing
decision, not the library's.

## Reflection + R8

All vehicle access reflects `android.car` (absent from the compile SDK). `consumer-rules.pro`
keeps the reflected names (android.car / SAIC / models / catalogue enums / `@SupportedOn`)
so a consumer's R8 does not strip them. Verify a minified consumer on a vehicle.

## Build

`mise run test | build | check`. JDK 17. Tests are JVM-only (no emulator): gate decision,
firmware annotations, catalogue consistency, matrix generation.
