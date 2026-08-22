# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-08-22

### Added

- `SaicRadio.pause`, the vendor service's `srcPauseRadio` — the counterpart of `play`. It
  mutes the tuner and marks the radio stopped; it does not hand the audio focus back, which
  the vendor service only does when it loses it.

### Changed

- `SaicRadio.tune` takes an `andPlay` flag and is responsible for the outcome. 1.1.0 removed
  the `srcPlayRadio` call and assumed that was enough to tune in silence; it is not. The
  vendor's `tune` calls `AudioController.requestMuted(false)` itself, which requests the
  audio focus and unmutes the tuner, so changing station always starts the radio. Not
  playing is therefore something that has to be undone after the fact: `andPlay = false`
  pauses the radio as soon as it is tuned. Callers must pass the flag — there is no default.

### Known limitation

- Tuning with `andPlay = false` leaves the radio holding the audio focus, silent. Whatever
  was playing before does not resume: the vendor service exposes no way to give the focus
  back for AM/FM, only for DAB scanning.

## [1.1.0] - 2026-08-22

### Changed

- `SaicRadio.tune` no longer starts playback. It used to call `srcPlayRadio` after setting
  the frequency, which made the radio the current audio source every time a station was
  tuned, so a caller that wanted the station changed silently had no way to ask for it.
  Tuning and playing are now two calls: `tune` sets the frequency, `play` switches the
  source. Callers that relied on the combined behaviour must call `play` themselves.

## [1.0.1] - 2026-08-21

### Fixed

- `SaicClimate.outsideTempCelsius` no longer returns the vendor's error value as a
  temperature. `AirConditionBinder.getOutCarTemp` answers `TEMPERATURE_ERROR_CONST`
  (-10000f) for a signal it holds no value for, which is not a reading; it now maps to
  `null`, same rule as every other getter here. A caller that stored it would have had
  every "below N degrees" comparison come out true.

## [1.0.0] - 2026-08-15

### ⚠️ Breaking

- Renamed from MG4Hardware to **EVHardware**, and the package from `com.mg4.hardware` to
  **`com.evsuite.hardware`**. Every import has to be updated; there is no compatibility
  alias.
- `VehicleWriteGate.decide` no longer takes `allowUpToKmh` or `parked`. The gate is fixed at
  a readable 0 km/h, with no caller threshold and no park rescue, so callers that passed
  either argument must drop it. Consumers using `decideNow()` or `decide(speed)` are
  unaffected.

### Added

- `FirmwareInfo.generationOf` as a pure parser, and `isDetectedGenerationSupported`, so an
  unforced unknown firmware fails closed.
- Catalogue entry `ASK_CONFIRM`, with its localised strings and firmware-matrix row.

### Changed

- Consolidated the four copies of this library that had drifted apart while it was vendored
  into EVProfile, EVTasker and EVABRPUploader.

## [0.3.0] - 2026-08-10

### Added

- **Shared diagnostic engine** in `com.evsuite.hardware.diag`, so the apps stop each carrying
  their own copy:
  - `CrashLogger` writes an uncaught exception, its cause chain and the `AppLogger` buffer
    to `filesDir/last_crash.txt`, chaining the previous handler and truncating from the tail
    so the exception survives. It replaces the two divergent copies in EVProfile and
    EVTasker, each of which carried a fix the other was missing.
  - `PrivateBin` uploads a report to a PrivateBin instance, zero-knowledge — the key never
    leaves the URL fragment. Moved verbatim from EVTasker.
  No UI: rendering a report and deciding when to paste it stay in the apps.

### Changed

- **One "Call" action.** `CALL_NUMBER` now carries a `CONTACT` value, so the same entry takes
  either a typed number or a name from the phone book. `CALL_CONTACT` is deprecated and no
  longer offered by `ActionType.byGroup()`: it stays in the enum only so rules saved by a
  release that exposed it still load and still call, since enum entries are persisted by name
  and a removed one deserialises to null.

## [0.2.0] - 2026-08-02

### Added

- Vehicle access to climate, charging, radio and calls through the vendor's own
  service, alongside the existing reflection layer.
- `SPEAK_TEXT` local action: text-to-speech through the vehicle's own voice service.
- Window and door-lock control through the vendor control service.
- Park-based fallback for the write gate, and radio tuning.

### Fixed

- Unreadable vehicle signals were being reported as "off"; they're now reported
  as unknown.

### Changed

- Adopted the EVSuite documentation structure: shared README skeleton, generated table
  of contents, and [DESIGN.md](DESIGN.md) carried in every suite repository.

## [0.1.0] - 2026-07-23

Initial tagged release.
