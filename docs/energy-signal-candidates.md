# Public energy-signal candidates

Reviewed 2026-08-28 for CP-004. This review uses only public AOSP catalogues and the
project's existing runtime read seam. It does not use proprietary artifacts or non-public
property catalogues.

## Safety and validation boundary

Every entry below is read-only and starts with `validated: no`. A public property number proves
only that AOSP assigned a number and semantics; it does not prove that an MG4 VHAL implements or
publishes it. No candidate may enter `EnergySnapshot`, a production fallback or a stable UI until
CP-003 records availability, unit, scale, sign and update cadence for every supported firmware
generation. An unavailable or non-finite read stays null.

## Public catalogue findings

| Signal | Public declaration | Type / area / unit / mode | AOSP coverage | EVSuite disposition | validated |
|---|---|---|---|---|---|
| `EV_CURRENT_BATTERY_CAPACITY` (`291504909`, `0x1160030D`) | [AOSP snapshot `c80b787`](https://android.googlesource.com/platform/packages/services/Car/+/c80b787d499243d120c2738eb68e7c0520c7238d/car-lib/src/android/car/VehiclePropertyIds.java) | Float / global / Wh / on-change / read-only | Public post-AAOS 9 API; the declaration requires Car UDC with Android 13 or later | Highest-value new candidate: status-aware probe in unstable evidence capture only | no |
| `EV_BATTERY_AVERAGE_TEMPERATURE` (`291504910`, `0x1160030E`) | [AOSP change `5b96f99`](https://android.googlesource.com/platform/packages/services/Car/+/5b96f99271e9209774a312c8f0fc1279dccf0678/car-lib/src/android/car/VehiclePropertyIds.java) | Float / global / °C / continuous / read-only | Added to the public API in December 2023 | Already read by the CP-001 baseline as nullable battery temperature; CP-004 adds no duplicate candidate | no |
| `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` (`291504908`, `0x1160030C`) | [Android 9 AOSP snapshot `8686c86`](https://android.googlesource.com/platform/packages/services/Car/+/8686c86b38a590712412fea2dd083181aceba4f2/car-lib/src/android/car/VehiclePropertyIds.java) | Float / global / mW / continuous / read-only | Android 9 public catalogue | Existing CP-001 battery-power input; its live scale and sign still require CP-003 evidence | no |

All cited files are Copyright Android Open Source Project and licensed Apache-2.0 at the cited
commit. The review records public declarations, not copied implementation code. Vehicle coverage
remains unknown for SWI68, SWI69, SWI131, SWI132, SWI133 and SWI165 until CP-003 runs on each one.

## Explicit negative results

| Requested quantity | Public catalogue result | Consequence |
|---|---|---|
| HVAC, compressor or PTC electrical power | No Android 9 or reviewed later public vehicle property. `HVAC_POWER_ON` is only a boolean subsystem state. | Climate energy can only be estimated from validated pack power and controlled trials. |
| Battery-heater power | No public power or energy property found. Battery temperature is a temperature, not heater consumption. | No direct measurement candidate. |
| DC-DC or accessory load | No public power or energy property found. | No direct measurement candidate. |
| Cumulative battery discharge | No public cumulative counter found. `EV_BATTERY_LEVEL` is current stored energy. | Integrate validated instantaneous power, retaining uncertainty and gaps. |
| Cumulative regeneration | No public cumulative counter found. Later regenerative-braking properties describe a state, not recovered energy. | Integrate only after CP-003 proves the power sign convention. |
| Per-trip energy | No public per-trip energy property found. | EVSuite trip totals remain derived from validated samples. |

The search deliberately stops here: sweeping arbitrary property ids would produce ambiguous data
and broaden the runtime surface without provenance.

## Probe wiring

`EV_CURRENT_BATTERY_CAPACITY` is read through the same status-aware reflected
`CarPropertyManager.getProperty` path as existing telemetry. The read is explicitly branched over
the supported firmware generations and returns null for unknown firmware, unavailable status,
negative values, exceptions and non-finite values. It is exposed only by
`EnergyTelemetryReader.readEvidence()` and recorded as
`candidate.evCurrentBatteryCapacityWh`; ordinary `read()` and `EnergySnapshot` are unchanged.
