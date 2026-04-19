# Smart Restart Policy

## Purpose
Avoid unnecessary force-stop cycles that destabilize emulator UI state. Only restart devices that truly need it, and limit restart attempts.

## Decision Tree

```
Failure detected
├─ adb unauthorized
│  └─ Action: Reconnect once → Recheck → If still fails, mark as adb_blocker
│
├─ boot_completed=0
│  └─ Action: Wait 30s → Recheck → If still 0, mark as platform_boot blocker
│
├─ App ANR (app process in ANR state)
│  └─ Action: Force-stop app only (not device) → Relaunch → If ANR persists, mark as app_anr
│
├─ UI blank after restart (uiautomator dump returns empty tree)
│  └─ Action: Capture evidence → Mark as platform_ui blocker → DO NOT restart again
│
├─ System UI ANR
│  └─ Action: Mark as platform_ui blocker → DO NOT restart device
│
└─ Package manager not ready
   └─ Action: Wait 30s → Recheck → If still not ready, mark as platform_boot blocker
```

## Restart Limits

- **Per device per round**: Maximum 1 restart
- **Per scenario**: Maximum 2 attempts before marking as blocker
- **Tracking**: Use execution ledger to track restart count

## Implementation Rules

### Rule 1: Check Ledger Before Restart
```powershell
$deviceState = Get-DeviceState -Serial $serial -LedgerPath $ledgerPath
if ($deviceState.restartCount -ge 1) {
    Write-Host "Device $serial already restarted once this round. Marking as blocker."
    return "device_blocker"
}
```

### Rule 2: Distinguish Transient vs Persistent Failures

**Transient failures** (retry once):
- `adb unauthorized` → reconnect
- `boot_completed=0` for < 30 seconds → wait
- App process not found immediately after launch → wait 5s

**Persistent failures** (mark as blocker):
- `adb offline` after reconnect
- `boot_completed=0` after 60 seconds
- UI blank after restart
- System UI ANR
- Launcher ANR

### Rule 3: Restart Only Failed Device

**DO NOT**:
```powershell
# Bad: Restart all devices
foreach ($serial in $allSerials) {
    adb -s $serial shell am force-stop com.smslink
}
```

**DO**:
```powershell
# Good: Restart only the failed device
if ($failedSerial) {
    adb -s $failedSerial shell am force-stop com.smslink
    Update-DeviceState -Serial $failedSerial -RestartCount ($deviceState.restartCount + 1)
}
```

### Rule 4: UI Blank After Restart = Hard Stop

If after a restart:
- `uiautomator dump` returns empty or minimal tree
- `dumpsys activity top` shows Launcher instead of app
- Screenshot shows blank or launcher screen

**Action**: Mark as `platform_ui` blocker and STOP. Do not attempt another restart.

## Integration with Execution Ledger

```powershell
# Before restart
$deviceState = .\execution-ledger-manager.ps1 -Action get-device -Serial $serial
if ($deviceState.restartCount -ge 1) {
    # Already restarted, mark as blocker
    return
}

# After restart
.\execution-ledger-manager.ps1 -Action update-device -Serial $serial -DeviceUpdate @{
    restartCount = $deviceState.restartCount + 1
    lastRestart = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    lastFailure = $failureReason
}
```

## Expected Benefits

- Avoid 4 devices × 30-60 seconds of unnecessary restarts = 2-4 minutes saved per round
- Prevent UI instability caused by repeated cold-start cycles
- Faster blocker identification (stop after 1 restart instead of 3-5 retries)

## References

See `STAGE2_4EMU_BLOCKER_REPORT.md` for evidence of UI blank issue after repeated cold-start cycles.
