# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
