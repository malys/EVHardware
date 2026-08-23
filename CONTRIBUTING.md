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

Changing a public signature ripples into EVProfile, EVTasker and EVABRPUploader (submodule
consumers). Note the affected apps in the PR; a minified consumer must be verified on a
vehicle because vehicle access is reflection-based.

## Ways to help

Not every useful contribution is code:

- **Bug reports** — firmware generation, app version, what you did, what happened.
- **Feature requests** — describe the problem before the solution.
- **Documentation** — README, this guide, the firmware matrix, translations.
- **Pull requests** — see below.
- **Testing pre-releases** — install an `unstable` build and report what broke.
- **Sponsorship** — see below.

## Contributing efficiently

Maintainer time and AI quota are the scarce resources here, ideas are not. If you have
access to Claude Opus or another capable coding model, a finished pull request is worth
much more than a feature request: someone still has to design, write, test and verify the
request, and that someone has a limited quota too.

A pull request that lands quickly usually carries:

- The problem, in one or two sentences.
- The proposed solution, and what you rejected.
- The implementation, scoped to one concern.
- Tests — `mise run check` passes.
- Documentation updated: README, CHANGELOG, this guide where relevant.

Generate the change locally with whatever model you have, then read every line yourself
before opening the PR. You are the author, not the model. Unreviewed generated code on a
path that reaches the vehicle will be sent back.

## Sponsorship

Maintaining EVSuite costs development time, test hardware and AI usage. If it is useful in
your daily driving, consider sponsoring through
[GitHub Sponsors](https://github.com/sponsors/malys). Sponsorship covers those costs and
gets fixes and features out faster.

Contributions are accepted under MIT (see [`LICENSE.md`](LICENSE.md)).
