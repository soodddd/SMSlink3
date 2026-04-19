# Android Real-Device QA

Use `$android-real-device-qa` as the single entry workflow for automated Android physical-device testing.

Requirements:

- Read the provided requirements or technical document.
- Build the real-device scenario matrix from the document.
- Confirm attached Android devices.
- Build and install the app.
- Run preflight and permission gates.
- Execute scenarios on physical devices.
- Collect screenshots, UI trees, logs, and crash evidence.
- Classify failures.
- Patch only confirmed product defects.
- Rebuild, reinstall, and rerun exact failed cases.
- Repeat until the app is stable or only non-product blockers remain.

Do not use emulator assumptions.

Prompt to paste:

`Use $android-real-device-qa to read the provided requirements or technical document, build the real-device scenario matrix, confirm attached Android devices, build and install the app, run preflight, execute tests, classify failures, patch only confirmed product defects, rebuild, reinstall, rerun exact failed cases, and continue until the app is stable or only non-product blockers remain.`

