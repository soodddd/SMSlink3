# Automated Log Parser
# Purpose: Extract failure reasons from logs automatically
# Expected: Save 3-5 minutes per failure of manual log analysis

param(
    [Parameter(Mandatory = $true)]
    [string]$LogcatPath,
    [string]$DumpsysWindowPath = "",
    [string]$DumpsysActivityPath = ""
)

$ErrorActionPreference = "Continue"

# Load logs
$logcat = ""
if (Test-Path $LogcatPath) {
    $logcat = Get-Content $LogcatPath -Raw -Encoding UTF8
} else {
    Write-Error "Logcat file not found: $LogcatPath"
    exit 1
}

$dumpsysWindow = ""
if ($DumpsysWindowPath -and (Test-Path $DumpsysWindowPath)) {
    $dumpsysWindow = Get-Content $DumpsysWindowPath -Raw -Encoding UTF8
}

$dumpsysActivity = ""
if ($DumpsysActivityPath -and (Test-Path $DumpsysActivityPath)) {
    $dumpsysActivity = Get-Content $DumpsysActivityPath -Raw -Encoding UTF8
}

# Pattern library - ordered by priority
$patterns = @{
    # Connection issues
    "connection_timeout" = @(
        "TcpConnectionImpl.*timeout",
        "Connection timed out",
        "SocketTimeoutException",
        "connect.*timeout"
    )
    "tls_handshake_fail" = @(
        "SSLHandshakeException",
        "Certificate.*failed",
        "TLS.*handshake.*failed",
        "javax\.net\.ssl\.SSLException"
    )
    "connection_refused" = @(
        "Connection refused",
        "ConnectException.*refused",
        "ECONNREFUSED"
    )
    "connection_reset" = @(
        "Connection reset",
        "SocketException.*reset",
        "ECONNRESET"
    )

    # Device state issues
    "device_not_connected" = @(
        "No connected devices",
        "connectedDevices.*empty",
        "getConnectedDevices.*returned.*0",
        "Target device.*not connected"
    )
    "device_discovery_failed" = @(
        "Discovery.*failed",
        "BLE.*scan.*failed",
        "No devices discovered"
    )

    # File transfer issues
    "file_transfer_complete" = @(
        "Transfer progress: 1\.0",
        "File sent successfully",
        "File received successfully",
        "FileTransfer.*completed"
    )
    "file_transfer_failed" = @(
        "File transfer.*failed",
        "FileTransfer.*error",
        "Failed to send file",
        "Failed to receive file"
    )
    "file_picker_cancelled" = @(
        "User cancelled.*picker",
        "ActivityResult.*RESULT_CANCELED",
        "File selection cancelled"
    )

    # Notification issues
    "notification_synced" = @(
        "Notification synced successfully",
        "NotificationSync.*success",
        "Remote notification.*posted"
    )
    "notification_listener_not_enabled" = @(
        "NotificationListenerService.*not enabled",
        "Notification access.*denied",
        "NotificationListener.*permission"
    )
    "notification_sync_failed" = @(
        "Notification sync.*failed",
        "Failed to sync notification",
        "NotificationSync.*error"
    )

    # SMS issues
    "sms_received_locally" = @(
        "SmsReceiver.*onReceive",
        "SMS received.*from",
        "Message inserted.*sms"
    )
    "sms_sync_failed" = @(
        "SMS sync.*failed",
        "Failed to sync.*SMS",
        "SmsSync.*error"
    )

    # App crashes and ANRs
    "app_crash" = @(
        "FATAL EXCEPTION.*com\.smslink",
        "AndroidRuntime.*com\.smslink",
        "Process.*com\.smslink.*died"
    )
    "app_anr" = @(
        "ANR in.*com\.smslink",
        "com\.smslink.*not responding",
        "Input dispatching timed out.*com\.smslink"
    )

    # Platform issues
    "system_ui_anr" = @(
        "System UI.*isn't responding",
        "SystemUI.*Application Not Responding",
        "ANR in.*SystemUI"
    )
    "launcher_anr" = @(
        "Launcher.*isn't responding",
        "Launcher.*ANR",
        "nexuslauncher.*not responding",
        "Pixel Launcher.*isn't responding"
    )
    "input_method_timeout" = @(
        "InputMethodManager.*timeout",
        "InputMethod.*ANR",
        "Input method.*not responding"
    )

    # Permission issues
    "permission_denied" = @(
        "Permission denied",
        "SecurityException",
        "requires.*permission"
    )
}

# Parse results
$result = @{
    timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    logcatPath = $LogcatPath
    matches = @()
    primaryFailure = "unknown"
    confidence = "low"
    summary = ""
}

# Check each pattern
foreach ($key in $patterns.Keys) {
    foreach ($pattern in $patterns[$key]) {
        if ($logcat -match $pattern) {
            $match = @{
                category = $key
                pattern = $pattern
                source = "logcat"
            }
            $result.matches += $match

            # Set primary failure to first match (highest priority)
            if ($result.primaryFailure -eq "unknown") {
                $result.primaryFailure = $key
                $result.confidence = "high"
            }
        }
    }
}

# Check dumpsys window for UI issues
if ($dumpsysWindow) {
    if ($dumpsysWindow -match "System UI.*isn't responding") {
        $result.matches += @{ category = "system_ui_anr"; pattern = "dumpsys window"; source = "dumpsys_window" }
        if ($result.primaryFailure -eq "unknown") {
            $result.primaryFailure = "system_ui_anr"
            $result.confidence = "high"
        }
    }
    if ($dumpsysWindow -match "Launcher.*ANR|nexuslauncher.*not responding") {
        $result.matches += @{ category = "launcher_anr"; pattern = "dumpsys window"; source = "dumpsys_window" }
        if ($result.primaryFailure -eq "unknown") {
            $result.primaryFailure = "launcher_anr"
            $result.confidence = "high"
        }
    }
    if ($dumpsysWindow -match "com\.smslink.*isn't responding") {
        $result.matches += @{ category = "app_anr"; pattern = "dumpsys window"; source = "dumpsys_window" }
        if ($result.primaryFailure -eq "unknown") {
            $result.primaryFailure = "app_anr"
            $result.confidence = "high"
        }
    }
}

# Generate summary
$matchCount = $result.matches.Count
if ($matchCount -eq 0) {
    $result.summary = "No known failure patterns detected. Manual triage required."
    $result.confidence = "low"
} elseif ($matchCount -eq 1) {
    $result.summary = "Single failure pattern detected: $($result.primaryFailure)"
} else {
    $result.summary = "$matchCount failure patterns detected. Primary: $($result.primaryFailure)"
}

# Classification recommendation
$result.recommendation = switch ($result.primaryFailure) {
    "app_crash" { "product_defect - App crash requires code fix" }
    "app_anr" { "product_defect - App ANR requires code fix" }
    "system_ui_anr" { "platform_ui - System UI issue, not app fault" }
    "launcher_anr" { "platform_ui - Launcher issue, not app fault" }
    "connection_timeout" { "link - Network connectivity issue" }
    "tls_handshake_fail" { "link - TLS configuration or certificate issue" }
    "device_not_connected" { "state - Device connection state issue" }
    "notification_listener_not_enabled" { "permission - Notification permission not granted" }
    "permission_denied" { "permission - Permission issue" }
    "file_transfer_complete" { "success - File transfer completed successfully" }
    "notification_synced" { "success - Notification synced successfully" }
    "sms_received_locally" { "success - SMS received locally" }
    default { "unknown - Requires manual triage" }
}

Write-Host "Log parsing complete: $($result.summary)"
Write-Host "Recommendation: $($result.recommendation)"

return $result | ConvertTo-Json -Depth 5
