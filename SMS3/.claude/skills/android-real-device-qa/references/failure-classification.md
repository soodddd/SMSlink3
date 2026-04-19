# Failure Classification

Use these buckets:

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

Rules:

- If the device is offline, unauthorized, locked, or disconnected, do not patch product code.
- If the failure is due to system UI, permission prompts, OEM dialogs, or install/launch plumbing, classify it as environment/device-related first.
- Only patch when logs and runtime evidence implicate the app.

