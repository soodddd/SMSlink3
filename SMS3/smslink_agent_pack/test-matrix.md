# SMS-link Test Matrix

| feature | bucket | required_healthy_devices | evidence | fallback_if_platform_blocked | status |
|---|---|---:|---|---|---|
| app launch | real-emulator-testable | 1 | screenshot + dumpsys + logcat | retry once then label blocker | pending |
| permission guidance | real-emulator-testable | 1 | screenshot + settings entry | capture settings entry instead | pending |
| notification mirror and history | real-emulator-testable | 1 | UI state + logs | capture blocker and continue | pending |
| SMS sync UI | real-emulator-testable | 1 | UI state + sync logs | mark as device-only if hardware-dependent | pending |
| file share / transfer | real-emulator-testable | 2 | picker/share path + send logs + return-to-app evidence | use share-intent route if picker is unstable | pending |
| device discovery and pairing | real-emulator-testable | 2 | discovery state + pairing logs | isolate platform blockers | pending |
| connection / reconnection | real-emulator-testable | 2 | state transitions + logs | isolate platform blockers | pending |
| call guidance and default-app flow | degrade-or-guide-only | 1 | guidance UI + settings entry | mark true-device-only for call control | pending |
| diagnostics / log export | real-emulator-testable | 1 | export screen + log files | pull logs with adb | pending |

## Round rules

- 1-level round uses 2 healthy emulators.
- 2-level round uses 4 healthy emulators.
- Do not promote to 2-level until 1-level peer flows pass.

