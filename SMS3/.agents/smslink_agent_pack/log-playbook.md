# SMS-link Log Playbook

## Goal

Collect enough evidence to separate platform blockers from app defects.

## Evidence order

1. `adb devices -l`
2. boot state and package manager readiness
3. `dumpsys window`
4. `dumpsys activity top`
5. `dumpsys activity services`
6. `logcat` for main/system/crash buffers
7. screenshots or UI tree dumps when the UI is stable enough

## Preflight gate

- Run `scripts/prepare_android_emulator.ps1` before any app assertion.
- Mark the device as blocked if:
  - `sys.boot_completed` never reaches `1`
  - package manager does not respond
  - the screen is owned by launcher, System UI, PermissionController, or DocumentsUI

## Failure capture

- After any failed step, collect logs immediately.
- Save per-serial evidence in a separate folder.
- Include the expected result, actual result, focus owner, and top activity in the report.

## File picker rule

- After file selection, verify all of the following:
  - focus returned to the app
  - the app process is still alive
  - logs show the business path started
- If any item is missing, treat the step as unresolved and collect more evidence.

