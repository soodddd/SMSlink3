---
name: android-real-device-qa
description: Generic Android physical-device QA skill that derives scope from requirements or technical docs, validates attached devices, builds and installs the app, drives tests, classifies failures, and loops on confirmed product defects until stable.
---

# Android Real-Device QA

Use this as the single entry skill for automated Android app testing on **physical devices**.

## When to use

- The user provides requirements, a technical document, or a test matrix and wants automated real-device validation.
- The workflow must confirm devices, build and install the app, debug failures, rebuild, reinstall, and rerun.
- Do not use this for emulator-only testing.

## Mission

Take one requirements or technical document and drive the full loop:

1. Read the document and derive the scenario matrix.
2. Discover and validate attached Android devices.
3. Build the requested debug or test artifact.
4. Install and launch on real devices.
5. Run preflight and permission gates.
6. Execute the scenario matrix.
7. Capture screenshots, UI trees, logs, and crash evidence.
8. Classify failures.
9. Patch only confirmed product defects.
10. Rebuild, reinstall, and rerun the exact failed case.
11. Repeat until all in-scope scenarios pass or only non-product blockers remain.

## Operating rules

- Default backend: `ADB + UIAutomator + logcat + Gradle`.
- Fallback backend: `Appium / UiAutomator2` when a richer selector path or device-specific interaction makes it necessary.
- Derive package names, build variants, launch activities, permissions, and feature scope from the provided document or repository metadata.
- Do not hardcode project-specific names, package IDs, or scenario assumptions.
- Do not use emulator concepts such as AVDs, NAT, snapshots, relay peers, or boot animation logic.
- Treat USB disconnects, `adb unauthorized`, screen-lock state, OEM popups, Play Protect prompts, install failures, and system UI blockers as device/environment problems unless logs prove an app fault.
- Cap recovery effort per device to one refresh/reconnect/unlock cycle before classifying a blocker.

## Device gate

Before any scenario assertion, confirm:

- `adb devices -l` shows the device as `device`, not `offline` or `unauthorized`
- `adb -s <serial> shell getprop sys.boot_completed` returns `1`
- `adb -s <serial> shell pm list packages` succeeds
- the device is awake and unlocked
- the target package can resolve a launch activity when the app is installed
- no system-owned ANR or permission blocker owns the visible window

If the gate fails:
1. Try one recoverable refresh cycle
2. Recheck the gate
3. If it still fails, classify it as a device/environment blocker and stop product patching for that path

## Failure classification

Separate failures into these buckets:

- `device_blocker`
- `adb_blocker`
- `build_failure`
- `install_failure`
- `launch_failure`
- `permission_block`
- `system_ui_block`
- `app_crash`
- `app_anr`
- `missing_feature`
- `limit`
- `product_defect`

Only `product_defect` failures may move to code patching.

## Evidence required for each step

At minimum record:

- target serial(s)
- device model and Android version, if available
- package name and build variant
- preflight result
- install result
- launch result
- current focus owner and top activity
- screenshot path if available
- UI tree path if available
- logcat / crash log paths
- expected result vs actual result

## Patch loop

If a failure is confirmed as a product defect:

1. Make the smallest targeted patch.
2. Rebuild.
3. Reinstall on the same device(s).
4. Rerun the exact failed case.
5. Reclassify using fresh evidence.

Do not broaden the patch or restart unrelated flows while the same confirmed defect is still being validated.

## Output format

For each scenario or step, return:

- step id
- serial(s)
- action
- expected
- actual
- status
- classification
- evidence paths
- fix decision
- next action

## Prompt to start

Paste this exact text when you want to run it:

`Use $android-real-device-qa to read the provided requirements or technical document, build the real-device scenario matrix, confirm attached Android devices, build and install the app, run preflight, execute tests, classify failures, patch only confirmed product defects, rebuild, reinstall, rerun exact failed cases, and continue until the app is stable or only non-product blockers remain.`

## VSCode usage

- Claude Code: load the skill automatically from the repository or global `.claude/skills` folder, then paste the prompt above in the agent textbox.
- Gemini: paste the same prompt directly into the Gemini chat panel, or reuse `GEMINI_PROMPT.md` as the shared prompt file.

