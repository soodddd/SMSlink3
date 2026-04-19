param(
    [string]$WorkspaceRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputRoot = (Join-Path $WorkspaceRoot "test_output"),
    [int]$StepTimeoutSeconds = 300
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$RunRoot = Join-Path $OutputRoot $Timestamp
$LogRoot = Join-Path $RunRoot "logs"
$ScreenshotRoot = Join-Path $RunRoot "screenshots"
$VideoRoot = Join-Path $RunRoot "videos"
$UiaRoot = Join-Path $RunRoot "uia"
$BuildRoot = Join-Path $RunRoot "build"

foreach ($dir in @($RunRoot, $LogRoot, $ScreenshotRoot, $VideoRoot, $UiaRoot, $BuildRoot)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

$SummaryPath = Join-Path $RunRoot "summary.md"
$global:SummaryLines = New-Object System.Collections.Generic.List[string]

function Write-Section {
    param([string]$Title)
    $line = ""
    $border = "=" * 72
    Write-Host $line
    Write-Host $border
    Write-Host $Title
    Write-Host $border
    $global:SummaryLines.Add("")
    $global:SummaryLines.Add("## $Title")
}

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message"
    $global:SummaryLines.Add("- $Message")
}

function Write-Warn {
    param([string]$Message)
    Write-Warning $Message
    $global:SummaryLines.Add("- WARN: $Message")
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
    $global:SummaryLines.Add("- FAIL: $Message")
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogFile,
        [string]$WorkingDirectory = $WorkspaceRoot,
        [int]$TimeoutSeconds = 0,
        [switch]$IgnoreExitCode
    )

    $argumentText = ($Arguments -join " ")
    Write-Host ">> $FilePath $argumentText"
    Add-Content -Path $LogFile -Encoding UTF8 -Value "`n>> $FilePath $argumentText"

    $oldLocation = Get-Location
    Set-Location $WorkingDirectory
    try {
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Set-Location $oldLocation
    }

    $stdoutText = ($output | Out-String)
    $stderrText = ""
    if ($stdoutText) {
        Add-Content -Path $LogFile -Encoding UTF8 -Value $stdoutText
    }

    if ($exitCode -ne 0 -and -not $IgnoreExitCode) {
        throw "Command failed with exit code $exitCode: $FilePath $argumentText"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Stdout   = $stdoutText
        Stderr   = $stderrText
    }
}

function Get-SdkRoot {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        "C:\Users\forek\AppData\Local\Android\Sdk"
    ) | Where-Object { $_ -and (Test-Path $_) }

    if (-not $candidates) {
        throw "Android SDK root not found."
    }

    return $candidates[0]
}

function Ensure-SdkComponents {
    param([string]$SdkRoot)

    $sdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $sdkManager)) {
        throw "sdkmanager not found at $sdkManager"
    }

    $required = @(
        "platforms;android-35",
        "build-tools;35.0.0"
    )

    foreach ($component in $required) {
        $path = switch ($component) {
            "platforms;android-35" { Join-Path $SdkRoot "platforms\android-35" }
            "build-tools;35.0.0" { Join-Path $SdkRoot "build-tools\35.0.0" }
            default { "" }
        }

        if (Test-Path $path) {
            Write-Info "SDK component present: $component"
            continue
        }

        Write-Warn "Installing missing SDK component: $component"
        Invoke-LoggedCommand -FilePath $sdkManager -Arguments @("--sdk_root=$SdkRoot", "--install", $component, "--licenses") -LogFile (Join-Path $BuildRoot "sdkmanager.log") -IgnoreExitCode
    }
}

function Get-ConnectedDevices {
    $adb = Join-Path (Get-SdkRoot) "platform-tools\adb.exe"
    $result = Invoke-LoggedCommand -FilePath $adb -Arguments @("devices", "-l") -LogFile (Join-Path $BuildRoot "adb_devices.log")
    $devices = @()
    foreach ($line in ($result.Stdout -split "`r?`n")) {
        if ($line -match '^(?<serial>\S+)\s+device\b') {
            $devices += $Matches.serial
        }
    }

    return $devices
}

function Get-ProjectModules {
    return @(
        ":app",
        ":core:common",
        ":core:model",
        ":core:database",
        ":core:preferences",
        ":network:protocol",
        ":network:transport",
        ":network:discovery",
        ":network:hotspot",
        ":feature:device",
        ":feature:notification",
        ":feature:call",
        ":feature:transfer",
        ":feature:settings",
        ":audio",
        ":ui"
    )
}

function Get-ModulesWithUnitTests {
    $modules = @()
    foreach ($module in Get-ProjectModules) {
        $modulePath = $module.TrimStart(":") -replace ":", "\"
        $testDir = Join-Path $WorkspaceRoot "$modulePath\src\test"
        if (Test-Path $testDir) {
            $modules += $module
        }
    }
    return $modules
}

function Get-AppPackage {
    return "com.smslink"
}

function Get-ListenerComponent {
    return "com.smslink/com.smslink.feature.notification.SmsLinkNotificationListenerService"
}

function Invoke-GradleTasks {
    param(
        [string[]]$Tasks,
        [string]$LogName
    )

    $gradlew = Join-Path $WorkspaceRoot "gradlew.bat"
    $args = @("--no-daemon", "--console=plain") + $Tasks
    Invoke-LoggedCommand -FilePath $gradlew -Arguments $args -LogFile (Join-Path $BuildRoot $LogName) -WorkingDirectory $WorkspaceRoot
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Args,
        [Parameter(Mandatory = $false)][string]$LogFile = (Join-Path $BuildRoot "adb_device.log"),
        [switch]$IgnoreExitCode
    )

    $adb = Join-Path (Get-SdkRoot) "platform-tools\adb.exe"
    $fullArgs = @("-s", $Serial) + $Args
    return Invoke-LoggedCommand -FilePath $adb -Arguments $fullArgs -LogFile $LogFile -IgnoreExitCode:$IgnoreExitCode
}

function Start-AdbLogcat {
    param([string]$Serial)

    $adb = Join-Path (Get-SdkRoot) "platform-tools\adb.exe"
    $logFile = Join-Path $LogRoot "$Serial.logcat.txt"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    [void]$psi.ArgumentList.Add("-s")
    [void]$psi.ArgumentList.Add($Serial)
    [void]$psi.ArgumentList.Add("logcat")
    [void]$psi.ArgumentList.Add("-v")
    [void]$psi.ArgumentList.Add("time")
    $psi.WorkingDirectory = $WorkspaceRoot
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()

    $stream = $process.StandardOutput
    $writer = [System.IO.StreamWriter]::new($logFile, $true, [System.Text.Encoding]::UTF8)
    $writer.AutoFlush = $true

    $job = Start-Job -ArgumentList $process.Id, $logFile -ScriptBlock {
        param($Pid, $File)
        $proc = [System.Diagnostics.Process]::GetProcessById($Pid)
        $reader = $proc.StandardOutput
        $writer = [System.IO.StreamWriter]::new($File, $true, [System.Text.Encoding]::UTF8)
        $writer.AutoFlush = $true
        try {
            while (-not $proc.HasExited) {
                $line = $reader.ReadLine()
                if ($null -ne $line) {
                    $writer.WriteLine($line)
                } else {
                    Start-Sleep -Milliseconds 100
                }
            }
        } finally {
            $writer.Dispose()
        }
    }

    return [pscustomobject]@{
        Process = $process
        Job = $job
        LogFile = $logFile
    }
}

function Stop-AdbLogcat {
    param($Capture)
    if ($null -ne $Capture.Job) {
        try { Stop-Job $Capture.Job -Force | Out-Null } catch {}
        try { Remove-Job $Capture.Job -Force | Out-Null } catch {}
    }
    if ($null -ne $Capture.Process -and -not $Capture.Process.HasExited) {
        try { $Capture.Process.Kill($true) } catch {}
    }
}

function Capture-Screenshot {
    param(
        [string]$Serial,
        [string]$Label
    )

    $safeLabel = ($Label -replace '[^a-zA-Z0-9._-]', '_')
    $remote = "/sdcard/${safeLabel}.png"
    $local = Join-Path $ScreenshotRoot "$Serial-$safeLabel.png"
    Invoke-Adb -Serial $Serial -Args @("shell", "screencap", "-p", $remote) -LogFile (Join-Path $BuildRoot "$Serial-screencap.log") -IgnoreExitCode
    Invoke-Adb -Serial $Serial -Args @("pull", $remote, $local) -LogFile (Join-Path $BuildRoot "$Serial-screencap-pull.log")
    return $local
}

function Capture-UiDump {
    param(
        [string]$Serial,
        [string]$Label
    )

    $safeLabel = ($Label -replace '[^a-zA-Z0-9._-]', '_')
    $remote = "/sdcard/${safeLabel}.xml"
    $local = Join-Path $UiaRoot "$Serial-$safeLabel.xml"
    Invoke-Adb -Serial $Serial -Args @("shell", "uiautomator", "dump", $remote) -LogFile (Join-Path $BuildRoot "$Serial-ui-dump.log") -IgnoreExitCode
    Invoke-Adb -Serial $Serial -Args @("pull", $remote, $local) -LogFile (Join-Path $BuildRoot "$Serial-ui-dump-pull.log")
    return $local
}

function Capture-Video {
    param(
        [string]$Serial,
        [string]$Label,
        [int]$Seconds = 30
    )

    $safeLabel = ($Label -replace '[^a-zA-Z0-9._-]', '_')
    $remote = "/sdcard/${safeLabel}.mp4"
    $local = Join-Path $VideoRoot "$Serial-$safeLabel.mp4"
    $record = Start-Process -FilePath (Join-Path (Get-SdkRoot) "platform-tools\adb.exe") -ArgumentList @("-s", $Serial, "shell", "screenrecord", "--time-limit", "$Seconds", $remote) -PassThru -WindowStyle Hidden
    Wait-Process -Id $record.Id
    Invoke-Adb -Serial $Serial -Args @("pull", $remote, $local) -LogFile (Join-Path $BuildRoot "$Serial-screenrecord-pull.log")
    return $local
}

function Read-UiaNodes {
    param([string]$XmlPath)
    [xml]$xml = Get-Content -Raw -Encoding UTF8 $XmlPath
    return $xml.SelectNodes("//node")
}

function Get-UiaNodeByText {
    param(
        [string]$XmlPath,
        [string[]]$Texts
    )

    $nodes = Read-UiaNodes -XmlPath $XmlPath
    foreach ($text in $Texts) {
        foreach ($node in $nodes) {
            $nodeText = $node.GetAttribute("text")
            $desc = $node.GetAttribute("content-desc")
            if ($nodeText -eq $text -or $desc -eq $text -or $nodeText -like "*$text*" -or $desc -like "*$text*") {
                return $node
            }
        }
    }

    return $null
}

function Get-UiaNodeByRegex {
    param(
        [string]$XmlPath,
        [string]$Pattern
    )

    $nodes = Read-UiaNodes -XmlPath $XmlPath
    foreach ($node in $nodes) {
        $nodeText = $node.GetAttribute("text")
        $desc = $node.GetAttribute("content-desc")
        if (($nodeText -match $Pattern) -or ($desc -match $Pattern)) {
            return $node
        }
    }

    return $null
}

function Get-UiaNodeByClass {
    param(
        [string]$XmlPath,
        [string]$ClassName
    )

    $nodes = Read-UiaNodes -XmlPath $XmlPath
    foreach ($node in $nodes) {
        if ($node.GetAttribute("class") -eq $ClassName) {
            return $node
        }
    }

    return $null
}

function Get-UiaNodeBounds {
    param($Node)
    $bounds = $Node.GetAttribute("bounds")
    if ($bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        throw "Unable to parse bounds: $bounds"
    }

    $left = [int]$Matches[1]
    $top = [int]$Matches[2]
    $right = [int]$Matches[3]
    $bottom = [int]$Matches[4]
    return [pscustomobject]@{
        Left = $left
        Top = $top
        Right = $right
        Bottom = $bottom
        CenterX = [int](($left + $right) / 2)
        CenterY = [int](($top + $bottom) / 2)
    }
}

function Tap-UiaNode {
    param(
        [string]$Serial,
        [string]$XmlPath,
        $Node
    )

    $bounds = Get-UiaNodeBounds -Node $Node
    Invoke-Adb -Serial $Serial -Args @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") -LogFile (Join-Path $BuildRoot "$Serial-input.log")
}

function Wait-ForUiText {
    param(
        [string]$Serial,
        [string[]]$Texts,
        [int]$TimeoutSeconds = $StepTimeoutSeconds,
        [string]$Label = "wait"
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $xmlPath = Capture-UiDump -Serial $Serial -Label $Label
        $node = Get-UiaNodeByText -XmlPath $xmlPath -Texts $Texts
        if ($null -ne $node) {
            return $xmlPath
        }
        Start-Sleep -Seconds 2
    }

    $shot = Capture-Screenshot -Serial $Serial -Label "$Label-timeout"
    $video = Capture-Video -Serial $Serial -Label "$Label-timeout" -Seconds 30
    throw "Timed out waiting for UI text [$($Texts -join ', ')] on $Serial. Screenshot: $shot Video: $video"
}

function Wait-ForRegexInUi {
    param(
        [string]$Serial,
        [string]$Pattern,
        [int]$TimeoutSeconds = $StepTimeoutSeconds,
        [string]$Label = "waitregex"
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $xmlPath = Capture-UiDump -Serial $Serial -Label $Label
        $node = Get-UiaNodeByRegex -XmlPath $xmlPath -Pattern $Pattern
        if ($null -ne $node) {
            return [pscustomobject]@{ XmlPath = $xmlPath; Node = $node }
        }
        Start-Sleep -Seconds 2
    }

    $shot = Capture-Screenshot -Serial $Serial -Label "$Label-timeout"
    $video = Capture-Video -Serial $Serial -Label "$Label-timeout" -Seconds 30
    throw "Timed out waiting for UI regex [$Pattern] on $Serial. Screenshot: $shot Video: $video"
}

function Wait-ForAppProcess {
    param(
        [string]$Serial,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = Invoke-Adb -Serial $Serial -Args @("shell", "pidof", Get-AppPackage()) -LogFile (Join-Path $BuildRoot "$Serial-pidof.log") -IgnoreExitCode
        if ($result.Stdout.Trim()) {
            return $result.Stdout.Trim()
        }
        Start-Sleep -Seconds 2
    }

    throw "App process did not start on $Serial"
}

function Set-DevicePermissions {
    param([string]$Serial)

    $pkg = Get-AppPackage
    $permissions = @(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.NEARBY_WIFI_DEVICES"
    )

    foreach ($permission in $permissions) {
        Invoke-Adb -Serial $Serial -Args @("shell", "pm", "grant", $pkg, $permission) -LogFile (Join-Path $BuildRoot "$Serial-pm-grant.log") -IgnoreExitCode
    }

    Invoke-Adb -Serial $Serial -Args @("shell", "settings", "put", "secure", "enabled_notification_listeners", (Get-ListenerComponent)) -LogFile (Join-Path $BuildRoot "$Serial-listener.log") -IgnoreExitCode
    Invoke-Adb -Serial $Serial -Args @("shell", "settings", "put", "global", "stay_on_while_plugged_in", "7") -LogFile (Join-Path $BuildRoot "$Serial-stayon.log") -IgnoreExitCode
}

function Prepare-Device {
    param([string]$Serial)

    $pkg = Get-AppPackage
    Write-Info "Preparing device $Serial"
    Invoke-Adb -Serial $Serial -Args @("wait-for-device") -LogFile (Join-Path $BuildRoot "$Serial-wait.log")
    Invoke-Adb -Serial $Serial -Args @("shell", "pm", "clear", $pkg) -LogFile (Join-Path $BuildRoot "$Serial-pm-clear.log")
    Invoke-Adb -Serial $Serial -Args @("install", "-r", "-g", (Join-Path $WorkspaceRoot "app\build\outputs\apk\debug\app-debug.apk")) -LogFile (Join-Path $BuildRoot "$Serial-install.log")
    Set-DevicePermissions -Serial $Serial
}

function Start-App {
    param([string]$Serial)
    $pkg = Get-AppPackage
    Invoke-Adb -Serial $Serial -Args @("shell", "am", "start", "-W", "-n", "$pkg/.MainActivity") -LogFile (Join-Path $BuildRoot "$Serial-start.log")
    Wait-ForAppProcess -Serial $Serial | Out-Null
}

function Complete-Onboarding {
    param(
        [string]$Serial,
        [string]$RoleText
    )

    Wait-ForUiText -Serial $Serial -Texts @("开始使用") -Label "onboarding-welcome" | Out-Null
    $xml = Capture-UiDump -Serial $Serial -Label "onboarding-welcome"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("开始使用")
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node

    Wait-ForUiText -Serial $Serial -Texts @("选择设备角色") -Label "role-selection" | Out-Null
    $xml = Capture-UiDump -Serial $Serial -Label "role-selection"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @($RoleText)
    if ($null -eq $node) {
        throw "Role node not found for $RoleText on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node

    Wait-ForUiText -Serial $Serial -Texts @("授予权限") -Label "permissions" | Out-Null
    $xml = Capture-UiDump -Serial $Serial -Label "permissions"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("授予权限")
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node

    Start-Sleep -Seconds 2
    $xml = Capture-UiDump -Serial $Serial -Label "permissions-post"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("开始配对")
    if ($null -eq $node) {
        $node = Get-UiaNodeByText -XmlPath $xml -Texts @("继续", "下一步")
    }
    if ($null -ne $node) {
        Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
    } else {
        $node = Get-UiaNodeByText -XmlPath $xml -Texts @("开始配对")
        if ($null -eq $node) {
            throw "Cannot find onboarding completion button on $Serial"
        }
        Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
    }

    Wait-ForUiText -Serial $Serial -Texts @("添加设备") -Label "pairing-entry" | Out-Null
}

function Generate-PairingCode {
    param([string]$Serial)

    $xml = Capture-UiDump -Serial $Serial -Label "pairing-before-generate"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("生成配对码")
    if ($null -eq $node) {
        throw "Generate code button not found on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node

    $result = Wait-ForRegexInUi -Serial $Serial -Pattern '\b\d{6}\b' -Label "pairing-code"
    $matchNode = $result.Node
    $code = $matchNode.GetAttribute("text")
    if ([string]::IsNullOrWhiteSpace($code) -or $code -notmatch '^\d{6}$') {
        $code = $matchNode.GetAttribute("content-desc")
    }
    if ($code -notmatch '^\d{6}$') {
        throw "Unable to extract pairing code on $Serial"
    }
    return [pscustomobject]@{ Code = $code; XmlPath = $result.XmlPath }
}

function Enter-PairingCode {
    param(
        [string]$Serial,
        [string]$Code
    )

    $xml = Capture-UiDump -Serial $Serial -Label "pairing-enter-code"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("输入配对码")
    if ($null -eq $node) {
        throw "Enter code button not found on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node

    Start-Sleep -Seconds 1
    Invoke-Adb -Serial $Serial -Args @("shell", "input", "text", $Code) -LogFile (Join-Path $BuildRoot "$Serial-input-code.log")

    $xml = Capture-UiDump -Serial $Serial -Label "pairing-enter-code-confirm"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("确认")
    if ($null -eq $node) {
        throw "Confirm button not found on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
}

function Finish-Pairing {
    param([string]$Serial)
    Wait-ForUiText -Serial $Serial -Texts @("配对成功") -Label "pairing-success" | Out-Null
    $xml = Capture-UiDump -Serial $Serial -Label "pairing-success"
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @("完成")
    if ($null -ne $node) {
        Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
    }
}

function Navigate-ByBottomTab {
    param(
        [string]$Serial,
        [string]$TabText,
        [string]$Label
    )

    $xml = Capture-UiDump -Serial $Serial -Label $Label
    $node = Get-UiaNodeByText -XmlPath $xml -Texts @($TabText)
    if ($null -eq $node) {
        throw "Bottom tab '$TabText' not found on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
}

function Run-NotificationSmoke {
    param(
        [string]$PrimarySerial,
        [string]$SecondarySerial
    )

    $pkg = Get-AppPackage
    Invoke-Adb -Serial $PrimarySerial -Args @("shell", "cmd", "notification", "post", "-S", "bigtext", "-t", "SmsLinkTest", "SmsLinkTest", "Automation notification from primary") -LogFile (Join-Path $BuildRoot "$PrimarySerial-notify-post.log") -IgnoreExitCode
    Start-Sleep -Seconds 3

    $primaryLog = Join-Path $LogRoot "$PrimarySerial.logcat.txt"
    $secondaryLog = Join-Path $LogRoot "$SecondarySerial.logcat.txt"
    if (Test-Path $primaryLog) {
        Add-Content -Path $primaryLog -Value ""
    }
    if (Test-Path $secondaryLog) {
        Add-Content -Path $secondaryLog -Value ""
    }

    Navigate-ByBottomTab -Serial $PrimarySerial -TabText "通知" -Label "goto-notifications-primary"
    Navigate-ByBottomTab -Serial $SecondarySerial -TabText "通知" -Label "goto-notifications-secondary"

    Wait-ForUiText -Serial $PrimarySerial -Texts @("通知历史") -Label "notifications-primary" | Out-Null
    Wait-ForUiText -Serial $SecondarySerial -Texts @("通知历史") -Label "notifications-secondary" | Out-Null
    Wait-ForUiText -Serial $PrimarySerial -Texts @("SmsLinkTest", "Automation notification from primary") -Label "notification-primary-item" | Out-Null
    Wait-ForUiText -Serial $SecondarySerial -Texts @("SmsLinkTest", "Automation notification from primary") -Label "notification-secondary-item" | Out-Null

    Capture-Screenshot -Serial $PrimarySerial -Label "notifications-primary" | Out-Null
    Capture-Screenshot -Serial $SecondarySerial -Label "notifications-secondary" | Out-Null
}

function Run-SettingsSmoke {
    param([string]$Serial)

    Navigate-ByBottomTab -Serial $Serial -TabText "设置" -Label "goto-settings"
    Wait-ForUiText -Serial $Serial -Texts @("设置") -Label "settings-page" | Out-Null
    $xml = Capture-UiDump -Serial $Serial -Label "settings-toggle"
    $node = Get-UiaNodeByClass -XmlPath $xml -ClassName "android.widget.Switch"
    if ($null -eq $node) {
        throw "Dark mode switch not found on $Serial"
    }
    Tap-UiaNode -Serial $Serial -XmlPath $xml -Node $node
    Start-Sleep -Seconds 1
    Invoke-Adb -Serial $Serial -Args @("shell", "am", "force-stop", (Get-AppPackage)) -LogFile (Join-Path $BuildRoot "$Serial-force-stop.log") -IgnoreExitCode
    Start-App -Serial $Serial
    Navigate-ByBottomTab -Serial $Serial -TabText "设置" -Label "goto-settings-after-restart"
}

function Run-TransferSmoke {
    param([string]$Serial)

    Navigate-ByBottomTab -Serial $Serial -TabText "传输" -Label "goto-transfer"
    Wait-ForUiText -Serial $Serial -Texts @("传输") -Label "transfer-page" | Out-Null
    Capture-Screenshot -Serial $Serial -Label "transfer-page" | Out-Null
}

function Run-HomeSmoke {
    param([string]$Serial)

    Navigate-ByBottomTab -Serial $Serial -TabText "首页" -Label "goto-home"
    Wait-ForUiText -Serial $Serial -Texts @("SMS-Link", "连接状态") -Label "home-page" | Out-Null
    Capture-Screenshot -Serial $Serial -Label "home-page" | Out-Null
}

function Test-ModuleCoverage {
    Write-Section "Gradle build and unit tests"

    $assembleTasks = (Get-ProjectModules | ForEach-Object { "$_:assembleDebug" })
    $unitTestTasks = (Get-ModulesWithUnitTests | ForEach-Object { "$_:testDebugUnitTest" })

    Write-Info "Assemble tasks: $($assembleTasks -join ', ')"
    Invoke-GradleTasks -Tasks $assembleTasks -LogName "assemble.log"

    if ($unitTestTasks.Count -gt 0) {
        Write-Info "Unit test tasks: $($unitTestTasks -join ', ')"
        Invoke-GradleTasks -Tasks $unitTestTasks -LogName "unit-tests.log"
    } else {
        Write-Warn "No unit test directories discovered."
    }
}

function Run-DeviceCoverage {
    param(
        [string]$PrimarySerial,
        [string]$SecondarySerial
    )

    Write-Section "Device preparation"
    Prepare-Device -Serial $PrimarySerial
    Prepare-Device -Serial $SecondarySerial

    Write-Section "App launch and onboarding"
    Start-App -Serial $PrimarySerial
    Start-App -Serial $SecondarySerial

    Complete-Onboarding -Serial $PrimarySerial -RoleText "主设备（手机）"
    Complete-Onboarding -Serial $SecondarySerial -RoleText "副设备（电脑）"

    Write-Section "Pairing flow"
    $primaryPairing = Generate-PairingCode -Serial $PrimarySerial
    Write-Info "Generated pairing code on primary: $($primaryPairing.Code)"
    Enter-PairingCode -Serial $SecondarySerial -Code $primaryPairing.Code
    Finish-Pairing -Serial $SecondarySerial
    Finish-Pairing -Serial $PrimarySerial

    Write-Section "Post-pairing screens"
    Run-HomeSmoke -Serial $PrimarySerial
    Run-HomeSmoke -Serial $SecondarySerial
    Run-TransferSmoke -Serial $PrimarySerial
    Run-TransferSmoke -Serial $SecondarySerial
    Run-SettingsSmoke -Serial $PrimarySerial
    Run-SettingsSmoke -Serial $SecondarySerial

    Write-Section "Notification smoke"
    Run-NotificationSmoke -PrimarySerial $PrimarySerial -SecondarySerial $SecondarySerial
}

function Finalize-Summary {
    $global:SummaryLines.Add("")
    $global:SummaryLines.Add("Artifacts:")
    $global:SummaryLines.Add("- Logs: $LogRoot")
    $global:SummaryLines.Add("- Screenshots: $ScreenshotRoot")
    $global:SummaryLines.Add("- Videos: $VideoRoot")
    $global:SummaryLines.Add("- UI dumps: $UiaRoot")
    $global:SummaryLines.Add("- Build logs: $BuildRoot")
    Set-Content -Path $SummaryPath -Value $global:SummaryLines -Encoding UTF8
}

try {
    Write-Section "Environment"
    $sdkRoot = Get-SdkRoot
    Write-Info "Workspace root: $WorkspaceRoot"
    Write-Info "Run root: $RunRoot"
    Write-Info "Android SDK root: $sdkRoot"
    Ensure-SdkComponents -SdkRoot $sdkRoot

    $devices = Get-ConnectedDevices
    if ($devices.Count -lt 2) {
        throw "Need at least 2 connected devices. Found: $($devices -join ', ')"
    }

    $primary = $devices[0]
    $secondary = $devices[1]
    Write-Info "Primary device: $primary"
    Write-Info "Secondary device: $secondary"

    Test-ModuleCoverage
    Run-DeviceCoverage -PrimarySerial $primary -SecondarySerial $secondary

    Write-Section "Result"
    Write-Info "All automated build, unit test, and device smoke checks completed."
} catch {
    Write-Fail $_.Exception.Message
    $errorLog = Join-Path $BuildRoot "error.txt"
    $_ | Out-String | Set-Content -Path $errorLog -Encoding UTF8
    throw
} finally {
    Finalize-Summary
}
