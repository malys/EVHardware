# Disclaimer — no warranty, no liability

**Use this software entirely at your own risk.**

This project is provided **"as is"**, without warranty of any kind, express or implied,
including but not limited to the warranties of merchantability, fitness for a particular
purpose and non-infringement. In no event shall the authors or contributors be liable for
any claim, damages or other liability, whether in an action of contract, tort or otherwise,
arising from, out of or in connection with the software or its use.

## What that means concretely

- This library **reads and writes settings on a vehicle**. Any app that embeds it can
  change how a car behaves. Installing such an app is your decision and your
  responsibility, including any effect on the head unit's stability, your warranty, your
  insurance, or your vehicle's roadworthiness.
- The safety gate refuses road-behaviour writes above 0 km/h and fails closed on an
  unreadable speed. It is a guard, **not** a guarantee — it does not make an ill-considered
  write safe, it only prevents it landing while moving.
- **Compatibility is inferred, not tested.** `docs/firmware-matrix.md` is derived from this
  code, not from running on cars. Consumers must check what a given vehicle actually
  exposes at runtime.
- Vehicle access uses **undocumented OEM interfaces** discovered by inspection of the R69
  firmware. They can change or disappear with any firmware update.
- Climate and window signals are **read-only and unverified** on MG4 hardware.

## Not affiliated

This project is **not affiliated with, endorsed by, or supported by** SAIC Motor, MG Motor,
or Google. All trademarks belong to their respective owners. "MG4" is used only to identify
the vehicle the software targets.
