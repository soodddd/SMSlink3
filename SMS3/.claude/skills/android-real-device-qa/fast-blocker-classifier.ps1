# Fast Platform Blocker Classifier
# Purpose: Quickly identify platform issues without full triage
# Expected: 5-10 seconds vs 5-10 minutes for full triage

param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$LogcatPath = "",
    [string]$DumpsysWindowPath = "",
    [string]$BootCompleted = "",
    [string]$PackageManagerOutput = ""
)

$ErrorActionPreference = "Continue"

function Invoke-AdbCommand {
    param([string]$Serial, [string[]]$Args)
    $cmd = @("-s", $Serial) + $Args
    (& adb @cmd 2>&1 | Out-String).Trim()
}

# Classification result
$result = @{
    serial = $Serial
    classification = "unknown"
    reason = ""
    confidence = "low"
    timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
}

# Collect minimal evidence if not provided
if (-not $BootCompleted) {
    $BootCompleted = Invoke-AdbCommand $Serial @("shell", "getprop", "sys.boot_completed")
}

if (-not $PackageManagerOutput) {
    $PackageManagerOutput = Invoke-AdbCommand $Serial @("shell", "pm", "list", "packages")
}

if (-not $DumpsysWindowPath -and -not $dumpsysWindow) {
    $dumpsysWindow = Invoke-AdbCommand $Serial @("shell", "dumpsys", "window")
} elseif ($DumpsysWindowPath -and (Test-Path $DumpsysWindowPath)) {
    $dumpsysWindow = Get-Content $DumpsysWindowPath -Raw -Encoding UTF8
}

if (-not $LogcatPath -and -not $logcat) {
    $logcat = Invoke-AdbCommand $Serial @("logcat", "-d", "-t", "100")
} elseif ($LogcatPath -and (Test-Path $LogcatPath)) {
    $logcat = Get-Content $LogcatPath -Raw -Encoding UTF8
}

# Fast classification rules (ordered by priority)

# 1. Boot incomplete
if ($BootCompleted -notmatch "^1$") {
    $result.classification = "platform_boot"
    $result.reason = "sys.boot_completed is not 1"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 2. Package manager not ready
if ($PackageManagerOutput -notmatch "^package:") {
    $result.classification = "platform_boot"
    $result.reason = "Package manager not returning package list"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 3. System UI ANR
if ($dumpsysWindow -match "System UI.*isn't responding|SystemUI.*Application Not Responding") {
    $result.classification = "platform_ui"
    $result.reason = "System UI ANR detected in dumpsys window"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 4. Launcher instability
if ($dumpsysWindow -match "Launcher.*isn't responding|Launcher.*ANR|nexuslauncher.*not responding|Pixel Launcher.*isn't responding") {
    $result.classification = "platform_ui"
    $result.reason = "Launcher ANR or instability detected"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 5. PermissionController blocking
if ($dumpsysWindow -match "PermissionController.*mCurrentFocus|com\.android\.permissioncontroller.*ANR") {
    $result.classification = "permission_block"
    $result.reason = "PermissionController dialog or ANR blocking interaction"
    $result.confidence = "medium"
    return $result | ConvertTo-Json -Depth 3
}

# 6. Google Play Protect noise
if ($logcat -match "Google Play.*protect|PlayProtect.*warning" -and $logcat -notmatch "com\.smslink.*FATAL") {
    $result.classification = "platform_noise"
    $result.reason = "Google Play Protect warning (not app crash)"
    $result.confidence = "medium"
    return $result | ConvertTo-Json -Depth 3
}

# 7. DocumentsUI instability (not a blocker, just noise)
if ($logcat -match "DocumentsUI.*Exception|DocumentsUI.*crash" -and $logcat -notmatch "com\.smslink.*FATAL") {
    $result.classification = "platform_noise"
    $result.reason = "DocumentsUI exception (not app crash)"
    $result.confidence = "medium"
    return $result | ConvertTo-Json -Depth 3
}

# 8. ADB offline/unauthorized
$adbState = Invoke-AdbCommand $Serial @("get-state")
if ($adbState -match "offline|unauthorized") {
    $result.classification = "adb_blocker"
    $result.reason = "Device is $adbState"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 9. App crash (product defect indicator)
if ($logcat -match "FATAL EXCEPTION.*com\.smslink|AndroidRuntime.*com\.smslink") {
    $result.classification = "app_crash"
    $result.reason = "App crash detected in logcat"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 10. App ANR (product defect indicator)
if ($dumpsysWindow -match "com\.smslink.*isn't responding|com\.smslink.*ANR") {
    $result.classification = "app_anr"
    $result.reason = "App ANR detected in dumpsys window"
    $result.confidence = "high"
    return $result | ConvertTo-Json -Depth 3
}

# 11. Input method timeout (platform issue)
if ($logcat -match "InputMethodManager.*timeout|InputMethod.*ANR" -and $logcat -notmatch "com\.smslink.*blocked") {
    $result.classification = "platform_ui"
    $result.reason = "Input method timeout (not app-caused)"
    $result.confidence = "medium"
    return $result | ConvertTo-Json -Depth 3
}

# 12. Package installer noise
if ($logcat -match "PackageInstaller.*Exception|PackageManager.*failed" -and $logcat -notmatch "com\.smslink.*install") {
    $result.classification = "platform_noise"
    $result.reason = "Package installer noise (not app install failure)"
    $result.confidence = "low"
    return $result | ConvertTo-Json -Depth 3
}

# If no fast classification matched, return unknown
$result.classification = "unknown"
$result.reason = "No fast classification pattern matched - requires full triage"
$result.confidence = "low"

return $result | ConvertTo-Json -Depth 3
