# Security policy

This library is the one place its consumer apps read and write a car. That makes it
security-relevant even though it holds no credentials and opens no network.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability. Use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead. Say what you were
able to do, on which firmware generation, and whether the vehicle was moving.

## In scope

- A road-behaviour write reaching the vehicle above 0 km/h, or on an unreadable speed
  (the gate must fail closed).
- A gated setter missing `@RequiresStandstill`, or the gate being bypassable.
- A catalogue entry that maps to a vehicle capability outside its declared `@SupportedOn`
  set, or a wrong property id that writes an unintended vehicle property.
- Deserialization of a crafted `DrivingProfile`/`ProfileBackup` causing unintended writes.

## Not in scope

- The library declaring no permissions and no `sharedUserId`. Granting vehicle access is
  the **consuming app's** decision (platform signature / privileged install); report those
  choices against the app, not here.
- Climate/window reads returning null on a firmware that does not expose them — that is the
  unverified-and-read-only design.
- OEM firmware vulnerabilities — report to SAIC.

## Design decisions

- **The 0 km/h gate lives here**, applied inside the low-level write primitives, so every
  consumer inherits it in one place. `@RequiresStandstill` documents each gated setter and
  a test asserts the set.
- **No raw "write property 0xNNNN" API** is exposed to consumers beyond the typed setters;
  the action catalogue is a closed vocabulary.
- **Climate/window writes are intentionally absent.** Reads are unverified; a write path
  waits on on-vehicle confirmation.
- **Reflection targets are kept via `consumer-rules.pro`** so R8 in a consumer app cannot
  strip the `android.car`/SAIC/model/catalogue names resolved by name at runtime.
