# MG4Hardware

<p align="center"><img src="docs/logo.svg" width="440" alt="MG4Hardware"></p>

[![Tests](https://github.com/malys/MG4Hardware/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/MG4Hardware/actions/workflows/tests.yml)
[![Security](https://github.com/malys/MG4Hardware/actions/workflows/security.yml/badge.svg)](https://github.com/malys/MG4Hardware/actions/workflows/security.yml)
[![Publish](https://github.com/malys/MG4Hardware/actions/workflows/publish.yml/badge.svg)](https://github.com/malys/MG4Hardware/actions/workflows/publish.yml)
[![Release](https://img.shields.io/github/v/release/malys/MG4Hardware?include_prereleases&amp;sort=semver)](https://github.com/malys/MG4Hardware/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> ⚠️ **This library reads and writes a car's settings.** It runs inside apps installed on
> an MG4 head unit. Read [DISCLAIMER.md](DISCLAIMER.md) before depending on it.

Shared vehicle-access layer for the SAIC MG4 (Android Automotive OS 9, MT2712). One
implementation of the reflection-based hardware layer, the 0 km/h safety gate, the driving
models, and the **condition/action catalogue** — consumed by the MG4 apps so they do not
each re-derive it and drift apart.

The design line: **the apps are thin. MG4Hardware provides the vehicle.** MG4Tasker, for
example, is only a rule engine between conditions and actions — both of which are defined
here.

---

## Contents

- [Overview](#overview)
- [How it works](#how-it-works)
- [Install](#install)
- [The MG4 app suite](#the-mg4-app-suite)
- [Building](#building)
- [Project documents](#project-documents)
- [Security](#security)
- [Contributing](#contributing)
- [Legal](#legal)

## Overview
| Area | Types |
|---|---|
| Vehicle access | `MG4Hardware` — Katman1/4/5 reflection over `android.car` + SAIC SDK, per-firmware routing |
| Safety | `VehicleWriteGate` (0 km/h, fail-closed) + `@RequiresStandstill` on every gated setter |
| Vendor services | `saic.*` — the AIDL the head unit's own HVAC, charging, radio, hands-free and TTS apps use |
| Firmware | `FirmwareInfo` (detection + capability helpers), `FirmwareGen` |
| Models | `DrivingProfile`, `DriveMode`, `RegenLevel`, `ProfileBackup` |
| Catalogue | `ConditionType`, `ActionType`, `ValueSpec`, `VehicleEnums`, `SnapshotKeys` |
| Compatibility | `@SupportedOn`, `FirmwareSupport`, `FirmwareMatrix` → [docs/firmware-matrix.md](docs/firmware-matrix.md) |
| Diagnostics | `AppLogger` (ring buffer, no ADB needed) |

### The safety gate and `@RequiresStandstill`

`VehicleWriteGate` refuses any road-behaviour write above 0 km/h, and fails closed when the
speed is unreadable. Every gated setter in `MG4Hardware` carries `@RequiresStandstill`, and
a unit test asserts that exact set — a setter cannot silently gain or lose gating. Comfort
writes (seat/steering heating, volume, brightness, audio) are not gated and not annotated.

The threshold is configurable up to 50 km/h (`allowUpToKmh`, clamped here, default 0). One
thing can rescue a refusal: **park**, and only the unreadable-speed one. A car in P is not
moving whatever the speedometer failed to say, and the gear comes by another route — but it
never overrides a speed that did read, because park at 30 km/h means a signal is wrong.

### Firmware compatibility is generated, not written

Each vehicle catalogue entry carries `@SupportedOn(...)`, derived from `FirmwareInfo` and
`MG4Hardware`'s per-generation routing. [docs/firmware-matrix.md](docs/firmware-matrix.md)
is rendered from those annotations by `FirmwareMatrix` and checked by a test — never edited
by hand. Consumers use the same annotations to hide entries unsupported on the connected
car.

> The matrix is derived from code, **not** on-vehicle testing. Consumers should surface a
> diagnostic that reads each signal live; unreadable ≠ unsupported.

### Climate, glass and charging go through the vendor services

The AOSP property ids for HVAC and window position are the ones the R69 OEM sources name but
no MG4 generation confirms. They are still used for *reads*, where a wrong id returns null
and nothing else happens — but nothing is written through them, because writing an unverified
id to a vehicle is a different kind of mistake.

Writes go to `saic.*` instead: the binder interfaces the car's own HVAC, charging and vehicle
apps call. The integration contract is known; whether a
given firmware answers is a bind, which consumers report live rather than tabulate. The
electric tailgate stays out — the launcher defines OPEN and CLOSE as the same value, so it is
a pulse whose direction depends on state this cannot read.

---

## How it works
MG4Hardware is consumed as a **git submodule** exposed as a Gradle subproject. In a
consumer app:

```bash
git submodule add https://github.com/malys/MG4Hardware MG4Hardware
```

`settings.gradle.kts`:

```kotlin
include(":mg4hardware")
project(":mg4hardware").projectDir = file("MG4Hardware/lib")
```

`app/build.gradle.kts`:

```kotlin
dependencies { implementation(project(":mg4hardware")) }
```

Vehicle writes require the app to be signed with the ROM **platform key** (the car
permissions are `signature|privileged`); reads of standard AAOS properties work with less.
The library itself declares no permissions and no `sharedUserId` — that is the consuming
app's decision.

---

### External projects: consume the AAR

Projects outside this org can depend on MG4Hardware as a binary instead of a submodule. A
version tag publishes the AAR to GitHub Packages (`com.mg4:mg4hardware:<version>`), and
`./gradlew :lib:publishToMavenLocal` installs it to `~/.m2` for local experimentation:

```kotlin
repositories {
    mavenLocal()
    maven { url = uri("https://maven.pkg.github.com/malys/MG4Hardware") } // GitHub Packages
}
dependencies { implementation("com.mg4:mg4hardware:0.1.0-SNAPSHOT") }
```

The AAR carries `consumer-rules.pro`, so a consumer's R8 keeps the reflected names. Vehicle
writes still require the consuming app to be signed with the ROM platform key.

## Install
MG4Hardware is a library — it ships inside the consumer apps (MG4Control, MG4Tasker), not
as its own APK. Those apps are sideloaded on the head unit via the keyboard route: open a
text field, long-press `,` on the on-screen keyboard → **Language settings** → search
`backup` then press back to reach Android Settings → enable **Developer options** +
**Install unknown apps** → search `storage` and open the APK. See each app's README for
the full steps.

## The MG4 app suite
Part of a small set of projects for the SAIC MG4 (AAOS 9, MT2712), all sharing the
**MG4Hardware** vehicle layer:

| Project | Role |
|---|---|
| [MG4Hardware](https://github.com/malys/MG4Hardware) | Shared vehicle-access layer: reflection hardware layer, 0 km/h safety gate, driving models, condition/action catalogue + firmware matrix |
| [MG4Control](https://github.com/malys/MG4Control) | Drive-profile manager; applies settings at startup; owns the signature-protected TaskerBridge |
| [MG4Tasker](https://github.com/malys/MG4Tasker) | Rule engine — *when* conditions *then* actions — driving the car through MG4Control |
| [MG4AbrpTelemetry](https://github.com/malys/MG4AbrpTelemetry) | Live telemetry uploader to A Better Route Planner |

Common toolchain: **AGP 9.1.1 / Gradle 9.3.1 / compileSdk 36 / JDK 17**. Each app consumes
MG4Hardware as a git submodule (`MG4Hardware/lib` as the `:mg4hardware` subproject).

## Building
```bash
./gradlew :lib:testDebugUnitTest   # unit tests + regenerates docs/firmware-matrix.md
./gradlew :lib:assembleDebug       # the AAR
```

JDK 17 (AGP 9.1.1 / Gradle 9.3.1 / compileSdk 36). `android.car` is not on the compile SDK, so all vehicle access is
reflection — there is nothing to test on an emulator; the JVM tests cover the decision
logic (gate, firmware annotations, catalogue consistency).

### Adding a condition or action

One enum entry in `catalog/ConditionType` or `catalog/ActionType`, one string in
`lib/src/main/res/values/strings.xml` (+ `values-fr/`), and — for a vehicle entry — one
`@SupportedOn(...)`. A test fails if a vehicle entry lacks it; the matrix regenerates on the
next test run.

---

## Project documents
| Document | What it covers |
|---|---|
| [DESIGN.md](DESIGN.md) | The MG4Suite design system — colour, type, touch targets, icons |
| [AGENTS.md](AGENTS.md) | Context for AI agents working in this repository |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to build, test and submit a change |
| [SECURITY.md](SECURITY.md) | Threat model and vulnerability disclosure |
| [DISCLAIMER.md](DISCLAIMER.md) | Vehicle-safety disclaimer — read before installing |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [LICENSE.md](LICENSE.md) | Licence text |

## Security
See [SECURITY.md](SECURITY.md). The library is the one place vehicle writes happen for its
consumers, so the gate, the closed action vocabulary, and the per-firmware routing all live
here rather than being reimplemented per app.

## Contributing
Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. In short: this
code runs in a moving vehicle, so changes stay small, carry tests, and say in the diff
what would break without them. Anything touching the interface follows
[DESIGN.md](DESIGN.md).

## Legal
MIT — see [LICENSE](LICENSE) and [LICENSE.md](LICENSE.md). Runs on a vehicle; see
[DISCLAIMER.md](DISCLAIMER.md).
