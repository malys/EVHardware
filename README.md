# EVHardware

<p align="center"><img src="docs/logo.svg" width="440" alt="EVHardware"></p>

[![Tests](https://github.com/malys/EVHardware/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/EVHardware/actions/workflows/tests.yml)
[![Security](https://github.com/malys/EVHardware/actions/workflows/security.yml/badge.svg)](https://github.com/malys/EVHardware/actions/workflows/security.yml)
[![Publish](https://github.com/malys/EVHardware/actions/workflows/publish.yml/badge.svg)](https://github.com/malys/EVHardware/actions/workflows/publish.yml)
[![Release](https://img.shields.io/github/v/release/malys/EVHardware?include_prereleases&amp;sort=semver)](https://github.com/malys/EVHardware/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> ⚠️ **This library reads and writes a car's settings.** It runs inside apps installed on
> an MG4 head unit. Read [DISCLAIMER.md](DISCLAIMER.md) before depending on it.
> MG and MG4 are third-party marks used only to identify compatibility; this project is
> independent and is not approved by SAIC Motor or MG Motor.

Shared vehicle-access layer for the SAIC MG4 (Android Automotive OS 9, MT2712). One
implementation of the reflection-based hardware layer, the 0 km/h safety gate, the driving
models, and the **condition/action catalogue** — consumed by the EVSuite apps so they do not
each re-derive it and drift apart.

The design line: **the apps are thin. EVHardware provides the vehicle.** EVTasker, for
example, is only a rule engine between conditions and actions — both of which are defined
here.

---

## Contents

- [Overview](#overview)
- [How it works](#how-it-works)
- [Install](#install)
- [The EVSuite](#the-mg4-app-suite)
- [Building](#building)
- [Project documents](#project-documents)
- [Security](#security)
- [Contributing](#contributing)
- [Legal](#legal)

## Overview
| Area | Types |
|---|---|
| Vehicle access | `EVHardware` — Katman1/4/5 reflection over `android.car` + SAIC SDK, per-firmware routing |
| Safety | `VehicleWriteGate` (0 km/h, fail-closed) + `@RequiresStandstill` on every gated setter |
| Vendor services | `saic.*` — the AIDL the head unit's own HVAC, charging, radio, hands-free and TTS apps use |
| Firmware | `FirmwareInfo` (detection + capability helpers), `FirmwareGen` |
| Models | `DrivingProfile`, `DriveMode`, `RegenLevel`, `ProfileBackup` |
| Catalogue | `ConditionType`, `ActionType`, `ValueSpec`, `VehicleEnums`, `SnapshotKeys` |
| Compatibility | `@SupportedOn`, `FirmwareSupport`, `FirmwareMatrix` → [docs/firmware-matrix.md](docs/firmware-matrix.md) |
| Diagnostics | `AppLogger` (ring buffer, no ADB needed), `diag.CrashLogger`, `diag.PrivateBin` |

### The safety gate and `@RequiresStandstill`

`VehicleWriteGate` refuses any road-behaviour write above 0 km/h, and fails closed when the
speed is unreadable. Every gated setter in `EVHardware` carries `@RequiresStandstill`, and
a unit test asserts that exact set — a setter cannot silently gain or lose gating. Comfort
writes (seat/steering heating, volume, brightness, audio) are not gated and not annotated.

The threshold is fixed at exactly 0 km/h. It is not configurable, and no secondary signal
(including park) can rescue an unreadable speed. Every decision reads the speed again so a
sequence is cancelled as soon as the vehicle moves or the signal disappears.

### Firmware compatibility is generated, not written

Each vehicle catalogue entry carries `@SupportedOn(...)`, derived from `FirmwareInfo` and
`EVHardware`'s per-generation routing. [docs/firmware-matrix.md](docs/firmware-matrix.md)
is rendered from those annotations by `FirmwareMatrix` and checked by a test — never edited
by hand. Consumers use the same annotations to hide entries unsupported on the connected
car.

> The matrix is derived from code, **not** on-vehicle testing. Consumers should surface a
> diagnostic that reads each signal live; unreadable ≠ unsupported.

### Climate, glass and charging go through the vendor services

The AOSP property ids for HVAC and window position match the R69 runtime names but are not
confirmed on every MG4 generation. They are still used for *reads*, where a wrong id returns null
and nothing else happens — but nothing is written through them, because writing an unverified
id to a vehicle is a different kind of mistake.

Writes go to `saic.*` instead: the binder interfaces the car's own HVAC, charging and vehicle
apps call. The integration contract is known; whether a
given firmware answers is a bind, which consumers report live rather than tabulate. The
electric tailgate stays out — the launcher defines OPEN and CLOSE as the same value, so it is
a pulse whose direction depends on state this cannot read.

---

## How it works
EVHardware is consumed as a **git submodule** exposed as a Gradle subproject. In a
consumer app:

```bash
git submodule add https://github.com/malys/EVHardware EVHardware
```

`settings.gradle.kts`:

```kotlin
include(":evhardware")
project(":evhardware").projectDir = file("EVHardware/lib")
```

`app/build.gradle.kts`:

```kotlin
dependencies { implementation(project(":evhardware")) }
```

Vehicle writes require the app to be signed with the ROM **platform key** (the car
permissions are `signature|privileged`); reads of standard AAOS properties work with less.
The library itself declares no permissions and no `sharedUserId` — that is the consuming
app's decision.

---

### External projects: consume the AAR

Projects outside this org can depend on EVHardware as a binary instead of a submodule. A
version tag publishes the AAR to GitHub Packages (`com.evsuite:evhardware:<version>`), and
`./gradlew :lib:publishToMavenLocal` installs it to `~/.m2` for local experimentation:

```kotlin
repositories {
    mavenLocal()
    maven { url = uri("https://maven.pkg.github.com/malys/EVHardware") } // GitHub Packages
}
dependencies { implementation("com.evsuite:evhardware:0.1.0-SNAPSHOT") }
```

The AAR carries `consumer-rules.pro`, so a consumer's R8 keeps the reflected names. Vehicle
writes still require the consuming app to be signed with the ROM platform key.

## Install
EVHardware is a library — it ships inside the consumer apps (EVProfile, EVTasker), not
as its own APK. Those apps are sideloaded on the head unit via the keyboard route: open a
text field, long-press `,` on the on-screen keyboard → **Language settings** → search
`backup` then press back to reach Android Settings → enable **Developer options** +
**Install unknown apps** → search `storage` and open the APK. See each app's README for
the full steps.

## The EVSuite
Part of a small set of projects for the SAIC MG4 (AAOS 9, MT2712), all sharing the
**EVHardware** vehicle layer:

| Project | Role |
|---|---|
| [EVHardware](https://github.com/malys/EVHardware) | Shared vehicle-access layer: reflection hardware layer, 0 km/h safety gate, driving models, condition/action catalogue + firmware matrix |
| [EVProfile](https://github.com/malys/EVProfile) | Drive-profile manager; applies settings at startup; owns the signature-protected TaskerBridge |
| [EVTasker](https://github.com/malys/EVTasker) | Rule engine — *when* conditions *then* actions — driving the car through EVProfile |
| [EVABRPUploader](https://github.com/malys/EVABRPUploader) | Live telemetry uploader to A Better Route Planner |

Common toolchain: **AGP 9.1.1 / Gradle 9.3.1 / compileSdk 36 / JDK 17**. Each app consumes
EVHardware as a git submodule (`EVHardware/lib` as the `:evhardware` subproject).

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
| [DESIGN.md](DESIGN.md) | The EVSuite design system — colour, type, touch targets, icons |
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
