# SMS-link Acceptance Rules

## Product scope

- This app is an Android SMS-link style collaboration app.
- Core areas covered by emulator QA:
  - app launch and navigation
  - permissions and settings guidance
  - notification mirror and history
  - SMS sync and message state
  - file sharing and transfer
  - device discovery, pairing, connection, reconnection
  - call-related guidance and UI
  - diagnostics and log export

## Emulator acceptance boundary

- Treat a feature as accepted only when the emulator is healthy and the app path is evidenced by logs, screenshots, or `dumpsys`.
- Do not count launcher, System UI, PermissionController, DocumentsUI, package manager, or boot failures as app defects.
- Do not claim success for file transfer unless focus returns to the app and the app shows business-path evidence after the picker step.

## Emulator vs device-only

- Emulator-testable:
  - launch, navigation, permissions guidance, diagnostics, notification UI, SMS UI, file-share flow entry, connection state, and most background-service behavior
- Degrade-or-guide-only:
  - flows that need system guidance, default app selection, or fallback UI instead of full hardware behavior
- True-device-only:
  - carrier/SIM-dependent behavior, real telephony control, and other hardware or network paths that the emulator cannot faithfully provide

## Pass criteria

- The feature behaves as documented on a healthy emulator.
- Required evidence is captured.
- Any failure is classified before any code fix is attempted.

