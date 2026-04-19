# Device Gate

Before any scenario assertion, confirm:

- device appears in `adb devices -l` as `device`
- `sys.boot_completed == 1`
- `pm list packages` succeeds
- device is awake and unlocked
- launch activity resolves for the installed app
- no system-owned ANR or permission dialog owns the window

If the gate fails, use one recoverable refresh cycle and then classify the path as a device/environment blocker if it still fails.

