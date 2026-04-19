param(
    [string]$OutDir = "$(Get-Location)\vm-logs",
    [string[]]$Serials = @(),
    [string]$PackageName = "",
    [switch]$ClearBefore
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-AdbCapture {
    param(
        [string]$Serial,
        [string[]]$Args,
        [string]$Target
    )

    $command = @()
    if ($Serial) {
        $command += @("-s", $Serial)
    }
    $command += $Args
    & adb @command 2>&1 | Out-File -Encoding utf8 $Target
}

function Capture-DeviceSnapshot {
    param([string]$Serial)

    $safe = if ($Serial) { $Serial -replace '[^a-zA-Z0-9._-]', '_' } else { "default" }
    $dir = Join-Path $OutDir $safe
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    if ($ClearBefore) {
        $clearArgs = @()
        if ($Serial) {
            $clearArgs += @("-s", $Serial)
        }
        $clearArgs += @("logcat", "-c")
        & adb @clearArgs 2>$null | Out-Null
    }

    Invoke-AdbCapture -Serial $Serial -Args @("get-state") -Target (Join-Path $dir "adb_get_state.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "getprop", "sys.boot_completed") -Target (Join-Path $dir "boot_completed.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "getprop", "dev.bootcomplete") -Target (Join-Path $dir "dev_bootcomplete.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "pm", "list", "packages") -Target (Join-Path $dir "packages.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "dumpsys", "window") -Target (Join-Path $dir "dumpsys_window.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "dumpsys", "activity", "top") -Target (Join-Path $dir "dumpsys_activity_top.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("shell", "dumpsys", "activity", "services") -Target (Join-Path $dir "dumpsys_services.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("logcat", "-d", "-b", "crash") -Target (Join-Path $dir "logcat_crash.txt")
    Invoke-AdbCapture -Serial $Serial -Args @("logcat", "-d", "-b", "main", "-b", "system") -Target (Join-Path $dir "logcat_main_system.txt")

    if ($PackageName) {
        Invoke-AdbCapture -Serial $Serial -Args @("shell", "cmd", "package", "resolve-activity", "--brief", $PackageName) -Target (Join-Path $dir "resolve_activity.txt")
        Invoke-AdbCapture -Serial $Serial -Args @("shell", "pidof", "-s", $PackageName) -Target (Join-Path $dir "pidof.txt")
        Invoke-AdbCapture -Serial $Serial -Args @("shell", "dumpsys", "package", $PackageName) -Target (Join-Path $dir "dumpsys_package.txt")
    }
}

Invoke-AdbCapture -Serial "" -Args @("devices", "-l") -Target (Join-Path $OutDir "adb_devices.txt")

if ($Serials.Count -eq 0) {
    Capture-DeviceSnapshot -Serial ""
} else {
    foreach ($serial in $Serials) {
        Capture-DeviceSnapshot -Serial $serial
    }
}

