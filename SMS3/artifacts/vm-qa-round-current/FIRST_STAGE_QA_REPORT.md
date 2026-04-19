# SMS-link First Stage Emulator QA Report

Date: 2026-04-16

Scope: 2 Android emulators, first-stage requirement validation from `SMS-link_技术需求书_v3.1.md`.

Package: `com.smslink`

Build: `.\gradlew.bat :app:assembleDebug` passed.

Devices:
- `emulator-5554`, Android API 34 image, healthy after restart.
- `emulator-5556`, Android API 34 image, healthy after restart.

## Requirement Matrix

| Area | Requirement | Emulator Classification | Result |
| --- | --- | --- | --- |
| App launch | Main activity starts and remains foreground | real-emulator-testable | PASS |
| Navigation | Devices, Notifications, SMS, Files, Calls, Settings pages reachable | real-emulator-testable | PASS |
| Settings | Permission center and diagnostic logs reachable | real-emulator-testable | PASS |
| Device management | Device page shows paired/discovery actions | real-emulator-testable | PASS |
| Notification mirror | Notification list UI and listener start entry | real-emulator-testable with permission limitation | PASS for entry/service creation; real notification mirroring requires notification access grant and source notifications |
| SMS | SMS list/sync UI and permission status | degrade-or-guide-only on emulator | PASS for UI/permission route; real carrier SMS requires SIM/telephony |
| File transfer | System picker, SAF file selection, progress, result feedback | real-emulator-testable | PASS |
| File transfer link | L1 WiFi LAN data channel over TLS | real-emulator-testable with emulator NAT proxy | PASS |
| Calls | Default phone-app guidance and call page | degrade-or-guide-only on emulator | PASS for UI guidance; real call control requires default phone role and real/telephony device |
| Background/service | Manifest service/receiver declarations and notification listener creation | real-emulator-testable | PASS |
| Diagnostics/logs | App diagnostics screen and logcat evidence | real-emulator-testable | PASS |
| True LAN | 2 physical phones on real WiFi LAN without host proxy | true-device-only | BLOCKED: no physical phones connected |

## Evidence

- Navigation summary: `artifacts/vm-qa-round-current/nav-summary.json`
- Permission/diagnostic summary: `artifacts/vm-qa-round-current/settings-entry-summary.json`
- Single-device feature summary: `artifacts/vm-qa-round-current/single-device-feature-summary.json`
- File transfer summary: `artifacts/vm-qa-round-current/file-transfer-summary.txt`
- Sender filtered log: `artifacts/vm-qa-round-current/sender-emulator-5554-filtered.log`
- Receiver filtered log: `artifacts/vm-qa-round-current/receiver-emulator-5556-filtered.log`
- Full collected logs: `artifacts/vm-qa-round-current/collected/`

## File Transfer Pass Criteria

All checks passed:

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
proxy_errors=[]
```

## Platform Notes

Android Emulator devices cannot directly represent two physical phones on the same WiFi LAN. The file transfer test used a QA-only temporary host TCP proxy plus `adb forward` to bridge sender `10.0.2.2:1716` to receiver `tcp:1716`. This proxy is not product code.

The `prepare_android_emulator.ps1` script timed out in this run, but manual health gates passed for both emulators:
- `adb get-state`: `device`
- `sys.boot_completed`: `1`
- Package manager responded.
- `com.smslink/.MainActivity` resolved and launched.
- No app or System UI ANR was present.

## Final Status

First-stage emulator-testable requirements passed.

Remaining validation requires physical devices:
- Real WiFi LAN file transfer between 2 phones without emulator host proxy.
- Real SMS receive/send through carrier telephony.
- Real call control with SMS-link as default phone app.
- Bluetooth RFCOMM and hotspot fallback on physical devices.
