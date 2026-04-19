param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$PackageName = "",
    [string]$ActivityComponent = "",
    [int]$BootTimeoutSec = 300,
    [int]$LaunchTimeoutSec = 45,
    [switch]$DisableAnimations,
    [switch]$ClearLogcat
)

$ErrorActionPreference = "Stop"

function Invoke-AdbText {
    param([string[]]$Args)
    $command = @("-s", $Serial)
    $command += $Args
    (& adb @command 2>&1 | Out-String).Trim()
}

function Wait-Until {
    param(
        [scriptblock]$Condition,
        [int]$TimeoutSec,
        [int]$SleepSec = 5
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            return $true
        }
        Start-Sleep -Seconds $SleepSec
    }
    return $false
}

$result = [ordered]@{
    serial = $Serial
    adbState = ""
    bootCompleted = $false
    packageManagerReady = $false
    animationsDisabled = $false
    packageResolvable = $null
    appLaunchSucceeded = $null
    appPid = ""
    focusedApp = ""
    currentFocus = ""
    androidId = ""
    classification = "healthy"
    notes = @()
}

Invoke-AdbText @("wait-for-device") | Out-Null
$result.adbState = Invoke-AdbText @("get-state")

$bootReady = Wait-Until -TimeoutSec $BootTimeoutSec -Condition {
    (Invoke-AdbText @("shell", "getprop", "sys.boot_completed")) -match "^1$"
}
$result.bootCompleted = $bootReady
if (-not $bootReady) {
    $result.classification = "platform_boot"
    $result.notes += "sys.boot_completed did not reach 1 in time"
}

if ($bootReady) {
    $pmReady = Wait-Until -TimeoutSec 60 -Condition {
        $packages = Invoke-AdbText @("shell", "pm", "list", "packages")
        $LASTEXITCODE -eq 0 -and $packages -match "^package:"
    }
    $result.packageManagerReady = $pmReady
    if (-not $pmReady) {
        $result.classification = "platform_boot"
        $result.notes += "package manager was not ready"
    }
}

if ($DisableAnimations -and $result.packageManagerReady) {
    Invoke-AdbText @("shell", "settings", "put", "global", "window_animation_scale", "0") | Out-Null
    Invoke-AdbText @("shell", "settings", "put", "global", "transition_animation_scale", "0") | Out-Null
    Invoke-AdbText @("shell", "settings", "put", "global", "animator_duration_scale", "0") | Out-Null
    $result.animationsDisabled = $true
}

if ($ClearLogcat) {
    Invoke-AdbText @("logcat", "-c") | Out-Null
}

if ($PackageName -and $result.packageManagerReady) {
    $resolve = Invoke-AdbText @("shell", "cmd", "package", "resolve-activity", "--brief", $PackageName)
    $result.packageResolvable = ($LASTEXITCODE -eq 0 -and $resolve -match [regex]::Escape($PackageName))
    if (-not $result.packageResolvable) {
        $result.classification = "platform_launch"
        $result.notes += "package could not be resolved yet"
    }
}

if ($ActivityComponent -and $result.packageResolvable -ne $false) {
    $launch = Invoke-AdbText @("shell", "am", "start", "-W", "-n", $ActivityComponent)
    $result.appLaunchSucceeded = ($LASTEXITCODE -eq 0 -and $launch -match "Status:\s*ok")
    if (-not $result.appLaunchSucceeded) {
        $result.classification = "platform_launch"
        $result.notes += "activity launch did not report Status: ok"
    }

    if ($result.appLaunchSucceeded) {
        $launched = Wait-Until -TimeoutSec $LaunchTimeoutSec -SleepSec 2 -Condition {
            $pid = Invoke-AdbText @("shell", "pidof", "-s", $PackageName)
            $LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($pid)
        }
        $result.appPid = Invoke-AdbText @("shell", "pidof", "-s", $PackageName)
        if (-not $launched) {
            $result.classification = "app_launch_uncertain"
            $result.notes += "process did not stabilize after launch"
        }
    }
}

$result.androidId = Invoke-AdbText @("shell", "settings", "get", "secure", "android_id")

$window = Invoke-AdbText @("shell", "dumpsys", "window")
$focusedAppMatch = [regex]::Match($window, "mFocusedApp=([^\r\n]+)")
$currentFocusMatch = [regex]::Match($window, "mCurrentFocus=([^\r\n]+)")
if ($focusedAppMatch.Success) {
    $result.focusedApp = $focusedAppMatch.Groups[1].Value.Trim()
}
if ($currentFocusMatch.Success) {
    $result.currentFocus = $currentFocusMatch.Groups[1].Value.Trim()
}

if ($window -match "isn't responding|Application Not Responding|ANR") {
    if ($window -match "System UI|Launcher|PermissionController|nexuslauncher") {
        $result.classification = "platform_ui"
        $result.notes += "system-owned ANR dialog detected"
    } elseif ($PackageName -and $window -match [regex]::Escape($PackageName)) {
        $result.classification = "app_anr"
        $result.notes += "app-owned ANR dialog detected"
    }
}

$result | ConvertTo-Json -Depth 4
