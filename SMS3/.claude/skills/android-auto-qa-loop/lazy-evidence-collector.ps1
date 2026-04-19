# Lazy Evidence Collector
# Purpose: Two-phase evidence collection - minimal for classification, full only for product defects
# Expected: 30s -> 5s for non-product defects

param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidateSet("minimal", "full")]
    [string]$Phase,
    [string]$OutputDir = ".",
    [string]$Prefix = "evidence"
)

$ErrorActionPreference = "Continue"

function Invoke-AdbCommand {
    param([string]$Serial, [string[]]$Args)
    $cmd = @("-s", $Serial) + $Args
    (& adb @cmd 2>&1 | Out-String).Trim()
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$result = @{
    serial = $Serial
    phase = $Phase
    timestamp = $timestamp
    files = @{}
    duration = 0
}

$startTime = Get-Date

if ($Phase -eq "minimal") {
    # Phase 1: Minimal evidence (2-5 seconds)
    # Just enough to classify the failure type

    Write-Host "Collecting minimal evidence for $Serial..."

    # 1. Last 50 lines of logcat (fast)
    $logcatFile = Join-Path $OutputDir "$Prefix-logcat-tail-$Serial.txt"
    $logcatTail = Invoke-AdbCommand $Serial @("logcat", "-d", "-t", "50")
    $logcatTail | Out-File -FilePath $logcatFile -Encoding UTF8
    $result.files.logcatTail = $logcatFile

    # 2. Window focus and ANR check (fast)
    $windowFile = Join-Path $OutputDir "$Prefix-window-$Serial.txt"
    $windowDump = Invoke-AdbCommand $Serial @("shell", "dumpsys", "window")
    # Extract only relevant lines
    $windowFiltered = $windowDump | Select-String -Pattern "mFocusedApp|mCurrentFocus|isn't responding|ANR|Application Not Responding"
    $windowFiltered | Out-File -FilePath $windowFile -Encoding UTF8
    $result.files.windowDump = $windowFile

    # 3. Boot and package manager state (fast)
    $stateFile = Join-Path $OutputDir "$Prefix-state-$Serial.json"
    $state = @{
        bootCompleted = Invoke-AdbCommand $Serial @("shell", "getprop", "sys.boot_completed")
        adbState = Invoke-AdbCommand $Serial @("get-state")
        packageManagerSample = (Invoke-AdbCommand $Serial @("shell", "pm", "list", "packages") | Select-Object -First 5)
    }
    $state | ConvertTo-Json -Depth 2 | Out-File -FilePath $stateFile -Encoding UTF8
    $result.files.state = $stateFile

    Write-Host "Minimal evidence collected in $([math]::Round(((Get-Date) - $startTime).TotalSeconds, 1))s"

} elseif ($Phase -eq "full") {
    # Phase 2: Full evidence (10-30 seconds)
    # Complete diagnostic package for product defects

    Write-Host "Collecting full evidence for $Serial..."

    # 1. Complete logcat
    $logcatFile = Join-Path $OutputDir "$Prefix-logcat-full-$Serial.txt"
    $logcat = Invoke-AdbCommand $Serial @("logcat", "-d")
    $logcat | Out-File -FilePath $logcatFile -Encoding UTF8
    $result.files.logcatFull = $logcatFile

    # 2. Crash log
    $crashFile = Join-Path $OutputDir "$Prefix-logcat-crash-$Serial.txt"
    $crash = Invoke-AdbCommand $Serial @("logcat", "-d", "-b", "crash")
    $crash | Out-File -FilePath $crashFile -Encoding UTF8
    $result.files.crash = $crashFile

    # 3. UI hierarchy dump
    $uiFile = Join-Path $OutputDir "$Prefix-ui-$Serial.xml"
    $uiDumpPath = "/sdcard/window_dump_$timestamp.xml"
    Invoke-AdbCommand $Serial @("shell", "uiautomator", "dump", $uiDumpPath) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-AdbCommand $Serial @("pull", $uiDumpPath, $uiFile) | Out-Null
    Invoke-AdbCommand $Serial @("shell", "rm", $uiDumpPath) | Out-Null
    $result.files.uiDump = $uiFile

    # 4. Screenshot
    $screenshotFile = Join-Path $OutputDir "$Prefix-screen-$Serial.png"
    $screencapPath = "/sdcard/screen_$timestamp.png"
    Invoke-AdbCommand $Serial @("shell", "screencap", "-p", $screencapPath) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-AdbCommand $Serial @("pull", $screencapPath, $screenshotFile) | Out-Null
    Invoke-AdbCommand $Serial @("shell", "rm", $screencapPath) | Out-Null
    $result.files.screenshot = $screenshotFile

    # 5. Full window dump
    $windowFullFile = Join-Path $OutputDir "$Prefix-window-full-$Serial.txt"
    $windowFull = Invoke-AdbCommand $Serial @("shell", "dumpsys", "window")
    $windowFull | Out-File -FilePath $windowFullFile -Encoding UTF8
    $result.files.windowFull = $windowFullFile

    # 6. Activity dump
    $activityFile = Join-Path $OutputDir "$Prefix-activity-$Serial.txt"
    $activity = Invoke-AdbCommand $Serial @("shell", "dumpsys", "activity", "top")
    $activity | Out-File -FilePath $activityFile -Encoding UTF8
    $result.files.activity = $activityFile

    # 7. Services dump (for app services)
    $servicesFile = Join-Path $OutputDir "$Prefix-services-$Serial.txt"
    $services = Invoke-AdbCommand $Serial @("shell", "dumpsys", "activity", "services", "com.smslink")
    $services | Out-File -FilePath $servicesFile -Encoding UTF8
    $result.files.services = $servicesFile

    # 8. Process info
    $processFile = Join-Path $OutputDir "$Prefix-process-$Serial.txt"
    $processInfo = @{
        pid = Invoke-AdbCommand $Serial @("shell", "pidof", "-s", "com.smslink")
        meminfo = Invoke-AdbCommand $Serial @("shell", "dumpsys", "meminfo", "com.smslink")
    }
    $processInfo | ConvertTo-Json -Depth 2 | Out-File -FilePath $processFile -Encoding UTF8
    $result.files.process = $processFile

    Write-Host "Full evidence collected in $([math]::Round(((Get-Date) - $startTime).TotalSeconds, 1))s"
}

$result.duration = [math]::Round(((Get-Date) - $startTime).TotalSeconds, 2)

# Output result as JSON
return $result | ConvertTo-Json -Depth 3
