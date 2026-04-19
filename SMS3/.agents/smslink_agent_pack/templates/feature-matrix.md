# Feature Matrix Template

| feature | bucket | required_healthy_devices | evidence | fallback_if_platform_blocked | status |
|---|---|---:|---|---|---|
| app launch | real-emulator-testable | 1 | screenshot + dumpsys + logcat | retry once then label blocker | pending |
| permissions guidance | real-emulator-testable | 1 | screenshot + settings deep link | capture settings entry instead | pending |
| notification mirror | real-emulator-testable | 2 | sender event + receiver UI + logs | capture blocker and degrade note | pending |
| SMS sync UI | degrade-or-guide-only | 2 | UI state + mock/log evidence | true-device-only follow-up | pending |
| file transfer | real-emulator-testable | 2 | picker/share path + send logs + receiver progress | prefer share-intent route if picker unstable | pending |
| connection/reconnection | real-emulator-testable | 2 | state transitions + logs | isolate platform blockers | pending |
| telephony control UI | degrade-or-guide-only | 1 | guidance UI + default-role entry | mark true-device-only for call control | pending |
| diagnostics/log export | real-emulator-testable | 1 | app page + export/log files | adb pull evidence | pending |
