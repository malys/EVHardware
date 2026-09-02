# AGENTS.md — EVHardware

Shared vehicle-access library for the SAIC MG4 (AAOS 9, MT2712), consumed by EVProfile,
EVTasker, EVABRPUploader and EVChargePilot via git submodule + Gradle subproject.

EVHardware is the shared vehicle layer for **EVSuite**. The workspace `AGENTS.md` and
normative workspace `DESIGN.md` apply; this file defines library-specific invariants.

Commit author: malys.training@gmail.com

License exception: EVHardware's own sources use PolyForm Noncommercial 1.0.0, not the
workspace MIT default.

## Why this exists

One implementation of the reflection hardware layer, the 0 km/h gate, the models, and the
condition/action catalogue — so the apps do not each re-derive vehicle access and drift.
The apps are thin; the vehicle lives here.

## Non-negotiables

- **The 0 km/h gate is here.** `VehicleWriteGate` decides every vehicle-setting write inside
  the low-level write primitives. Every setter carries `@RequiresStandstill`; coverage keeps
  the set honest. Unknown or negative speed fails closed, and a moving reading is never
  rescued by anything.
  The threshold is fixed at a *readable* 0 km/h, with no caller override and no park rescue.
- **Per-firmware routing, never universal.** SWI68/69/131/132/133/165 differ; dispatch
  through `FirmwareInfo`. Any new vehicle call must branch per generation.
- **Unreadable ≠ a value.** Getters return null / -1 when the layer is not ready or the
  property is absent; callers must treat that as "unknown", never as 0/false.
- **Energy semantics are shared.** Property ids, units, sign convention, physical validation,
  fallback order, trip integration and charging-session integration belong in `telemetry/`.
  Consumers own their UI, sampling cadence and network payload only.
- **`@SupportedOn` is the source of truth** for `docs/firmware-matrix.md` (generated, never
  hand-edited) and for consumers' runtime filters. A vehicle catalogue entry without it
  fails the tests.
- **Never write an unverified AOSP id.** The climate and window ids currently known are
  used for reads only. Writes go through `saic.*`, with every constant documented and
  validated per supported firmware generation.
- **English only** — code, comments, commits, docs. User strings in `values/` (English) +
  `values-fr/`.

## Consuming it

Submodule at `./EVHardware`; `settings.gradle.kts` includes `EVHardware/lib` as
`:evhardware`; app depends on `project(":evhardware")`. The library declares no
permissions and no `sharedUserId` — vehicle-write capability is the app's platform-signing
decision, not the library's.

**Every consuming app tracks the HEAD of `master`.** Never pin an older commit, and never
commit on a detached submodule HEAD: the catalogue is shared, so an entry removed here must
disappear from every app at once, and two apps on two lines of this library means the same
change written twice and reconciled by hand afterwards. Land library work in the standalone
checkout, push it, then move each app's pointer forward and run that app's tests.

## Reflection + R8

All vehicle access reflects `android.car` (absent from the compile SDK). `consumer-rules.pro`
keeps the reflected names (android.car / SAIC / models / catalogue enums / `@SupportedOn`)
so a consumer's R8 does not strip them. Verify a minified consumer on a vehicle.

## Build

`mise run test | build | check`. JDK 17. Tests are JVM-only (no emulator): gate decision,
firmware annotations, catalogue consistency, matrix generation.
