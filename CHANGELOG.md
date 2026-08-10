# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-08-10

### Added

- **Shared diagnostic engine** in `com.mg4.hardware.diag`, so the apps stop each carrying
  their own copy:
  - `CrashLogger` writes an uncaught exception, its cause chain and the `AppLogger` buffer
    to `filesDir/last_crash.txt`, chaining the previous handler and truncating from the tail
    so the exception survives. It replaces the two divergent copies in MG4Control and
    MG4Tasker, each of which carried a fix the other was missing.
  - `PrivateBin` uploads a report to a PrivateBin instance, zero-knowledge — the key never
    leaves the URL fragment. Moved verbatim from MG4Tasker.
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

- Adopted the MG4Suite documentation structure: shared README skeleton, generated table
  of contents, and [DESIGN.md](DESIGN.md) carried in every suite repository.

## [0.1.0] - 2026-07-23

Initial tagged release.
