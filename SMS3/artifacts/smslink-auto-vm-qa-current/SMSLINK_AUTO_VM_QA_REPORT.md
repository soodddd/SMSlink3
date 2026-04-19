# SMS-link Auto VM QA Report

Date: 2026-04-16

Skill: `smslink-auto-vm-qa`

Scope: first-stage QA on exactly 2 healthy Android emulators.

Package: `com.smslink`

## Build

`.\gradlew.bat :app:assembleDebug`: PASS

## Devices

Manual preflight passed after one environment recovery cycle.

Evidence: `artifacts/smslink-auto-vm-qa-current/preflight-manual.json`

Devices:
- `emulator-5554`: `device`, `sys.boot_completed=1`, app installed, `com.smslink/.MainActivity` foreground, no ANR.
- `emulator-5556`: `device`, `sys.boot_completed=1`, app installed, `com.smslink/.MainActivity` foreground, no ANR.

## Requirement Matrix

| Area | Requirement | Classification | Result |
| --- | --- | --- | --- |
| App launch | Main Activity cold start and foreground focus | real-emulator-testable | PASS |
| Navigation | Devices, Notifications, SMS, Files, Calls, Settings pages reachable | real-emulator-testable | PASS |
| Device management | Paired device/discovery UI visible | real-emulator-testable | PASS |
| Permissions | Permission center shows notification/SMS/call/Bluetooth/location/file states | real-emulator-testable | PASS |
| Diagnostics | Diagnostic logs page reachable | real-emulator-testable | PASS |
| Notifications | Notification list and listener start entry | real-emulator-testable with special permission limitation | PASS for entry and listener creation |
| SMS | SMS list, sync entry, permissions | degrade-or-guide-only on emulator | PASS for UI and permissions; real carrier SMS requires SIM |
| Files | SAF picker, transfer progress, result feedback | real-emulator-testable | PASS |
| File data channel | L1 WiFi LAN data channel over TLS | real-emulator-testable with emulator NAT bridge | PASS |
| Calls | Call page and default-phone-app guidance | degrade-or-guide-only on emulator | PASS for guidance; real call control requires phone role and telephony |
| Background | Notification listener service can be created from app entry | real-emulator-testable | PASS |
| True LAN | Two physical phones on WiFi LAN without host proxy | true-device-only | BLOCKED: no physical devices connected |

## Key Evidence

- UI matrix: `artifacts/smslink-auto-vm-qa-current/ui-matrix-summary.json`
- Notification/call interactions: `artifacts/smslink-auto-vm-qa-current/entry-interactions-summary.json`
- Call guidance XML: `artifacts/smslink-auto-vm-qa-current/call-guidance.xml`
- File transfer summary: `artifacts/smslink-auto-vm-qa-current/file-transfer-summary.txt`
- Sender filtered log: `artifacts/smslink-auto-vm-qa-current/sender-emulator-5554-filtered.log`
- Receiver filtered log: `artifacts/smslink-auto-vm-qa-current/receiver-emulator-5556-filtered.log`
- Full collected logs: `artifacts/smslink-auto-vm-qa-current/collected/`

## File Transfer Result

```text
sender_connected=True
sender_sent=True
sender_progress_complete=True
receiver_tls_listener=True
receiver_accepted=True
receiver_file_request=True
receiver_receiving=True
receiver_received=True
no_storage_denied=True
no_sender_fatal=True
no_receiver_fatal=True
no_ssl_handshake=True
no_crypto_upcalls=True
```

## Triage Notes

- `adb devices` was initially empty. This was treated as a non-program environment issue per skill rules. One recovery cycle restarted two AVDs and testing continued.
- Emulator file transfer used QA-only host proxy and `adb forward` because emulator NAT cannot represent real WiFi LAN peer-to-peer. This is not product code.
- Direct shell start of non-exported services is not used as an acceptance criterion. Notification listener was triggered through the app UI.
- Call guidance string assertion initially failed due to strict text matching, but XML evidence showed the dialog was visible. Classified as test assertion issue, not app defect.

## Product Fixes This Round

No new confirmed product defects were found in this `smslink-auto-vm-qa` round, so no code changes were made.

## Final Status

First-stage emulator-testable items: PASS.

Remaining true-device-only validation:
- Real WiFi LAN file transfer on 2 physical Android phones without host proxy.
- Real carrier SMS receive/send with SIM.
- Real call control with SMS-link set as default phone app.
- Bluetooth RFCOMM and hotspot fallback on physical devices.
