# SMS-link Stage 2 4-Emulator QA Blocker Report

Date: 2026-04-16

Scope: 4 healthy Android emulators for stage 2 fan-out / pairing validation

## What was recovered

- `emulator-5560` initially booted from an API 30 AVD and could not install the current APK because the app requires SDK 33+.
- I replaced that instance with a fresh API 34 AVD clone and then wiped its userdata to avoid identity contamination.
- Final `ANDROID_ID` values are unique across all 4 devices:
  - `emulator-5554` -> `61446e3099ea6717`
  - `emulator-5556` -> `28f6a62f3f19baef`
  - `emulator-5558` -> `1fda61e139030e5f`
  - `emulator-5560` -> `df754fda405927c`

## What passed

- Build still passes: `.\gradlew.bat :app:assembleDebug`
- The 4th emulator is now a valid API 34 device and installs the APK successfully.
- Cold launch via `am start -S -W -n com.smslink/.MainActivity` succeeds from the system side.
- App startup logs show the network/TLS listener starts:
  - `Encryption: Certificate not found for device: local_tls_listener`
  - `ConnectionManager: TLS TCP listener started on port 1716`

## Blocker found

After repeated cold starts, the app process starts but the UI never becomes usable again during this run:

- `uiautomator dump` returns a blank accessibility tree with no SMS-link texts.
- `dumpsys activity top` often falls back to the launcher instead of leaving a usable SMS-link screen in front.
- `screencap` shows a mostly white surface, not the expected SMS-link UI.
- `logcat` does not show a fatal app crash, but it does show the app process being killed during cold-stop/start cycles and the UI never settling back into a testable state.

This is not the same as the earlier first-stage 2-emulator pass, where the app UI matrix was readable. The difference is the repeated cold-stop / cold-start cycle needed to rebuild the 4-emulator stage and recover `emulator-5560`.

## Likely root cause class

- Not a build failure.
- Not an APK install failure on the final API 34 instance.
- Not an ADB identity failure after the wipe fix.
- Most likely a launch-state / cold-start / restore-path issue that needs a separate reproduction pass without aggressive force-stop churn.

## Evidence files

- `artifacts/smslink-auto-vm-qa-current/stage2_fanout_summary.json` if present from the next run
- `artifacts/shot-5554.png`
- logcat from `emulator-5554` around `AndroidRuntime` / `TLS TCP listener started`

## Recommendation for the next agent

1. Do not reuse a cloned AVD with copied userdata unless you explicitly wipe it first.
2. Treat `ANDROID_ID` collisions as a hard blocker before any pairing test.
3. Re-run the 4-emulator fan-out from a stable cold-boot baseline, but avoid repeated `force-stop` loops on all devices at once.
4. If the UI is blank after cold start, capture the exact launch log and reproduce once before patching. Do not speculate-fix without a confirmed product stack trace.
