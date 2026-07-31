# Contributing

This library reads and writes a car. That single fact drives everything below.

## Ground rules

1. **The 0 km/h gate is load-bearing.** Any road-behaviour write must pass through
   `VehicleWriteGate` and carry `@RequiresStandstill`. It fails closed on unreadable speed —
   keep it that way.
2. **Branch every vehicle call per firmware generation** via `FirmwareInfo`. Never assume a
   method or property id is universal across SWI68/69/131/132/133/165.
3. **Unreadable is not a value.** Return null / -1 when the layer is not ready or the
   property is absent; never fabricate 0/false.
4. **Property ids come from evidence.** Confirm ids/transaction codes against the R69 OEM
   sources (or on-vehicle logs) and document the source inline. An unverified id is
   read-only until confirmed — no write path.

## Before a PR

```bash
mise run check     # JVM unit tests + firmware-matrix freshness
```

New vehicle catalogue entries need a `@SupportedOn(...)`; a test fails without it, and
`docs/firmware-matrix.md` regenerates on the test run — commit it, do not hand-edit it.

## Language

English only — code, comments, commit messages, docs. User strings in `values/` (English)
with a `values-fr/` translation.

## Consumers

Changing a public signature ripples into MG4Control, MG4Tasker and MG4ABRPUploader (submodule
consumers). Note the affected apps in the PR; a minified consumer must be verified on a
vehicle because vehicle access is reflection-based.

Contributions are accepted under MIT (see [`LICENSE.md`](LICENSE.md)).
