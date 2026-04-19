# Parallel Device Gate
# Purpose: Check all devices in parallel instead of sequentially
# Expected: 4 devices from 60s to 15s

param(
    [Parameter(Mandatory = $true)]
    [string[]]$Serials,
    [string]$PackageName = "com.smslink",
    [int]$TimeoutSec = 120
)

$ErrorActionPreference = "Continue"

Write-Host "Starting parallel device gate for $($Serials.Count) devices..."

$jobs = @()
foreach ($serial in $Serials) {
    $jobs += Start-Job -ScriptBlock {
        param($s, $pkg, $timeout)

        function Check-Device {
            param($serial, $package)

            $result = @{
                serial = $serial
                healthy = $true
                checks = @{}
                timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
            }

            # Check 1: ADB state
            $adbState = (adb -s $serial get-state 2>&1 | Out-String).Trim()
            $result.checks.adbState = $adbState
            if ($adbState -notmatch "device") {
                $result.healthy = $false
                $result.reason = "ADB state is $adbState"
                return $result
            }

            # Check 2: Boot completed
            $bootCompleted = (adb -s $serial shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
            $result.checks.bootCompleted = $bootCompleted
            if ($bootCompleted -ne "1") {
                $result.healthy = $false
                $result.reason = "Boot not completed"
                return $result
            }

            # Check 3: Package manager ready
            $pmList = (adb -s $serial shell pm list packages 2>&1 | Out-String)
            $result.checks.packageManagerReady = ($pmList -match "^package:")
            if ($pmList -notmatch "^package:") {
                $result.healthy = $false
                $result.reason = "Package manager not ready"
                return $result
            }

            # Check 4: Screen awake
            $dumpsysWindow = (adb -s $serial shell dumpsys window 2>&1 | Out-String)
            $result.checks.screenAwake = ($dumpsysWindow -match "mAwake=true")

            # Check 5: No system ANR
            $systemAnr = ($dumpsysWindow -match "System UI.*isn't responding|Launcher.*ANR")
            $result.checks.systemAnr = $systemAnr
            if ($systemAnr) {
                $result.healthy = $false
                $result.reason = "System ANR detected"
                return $result
            }

            # Check 6: Package resolvable
            if ($package) {
                $resolve = (adb -s $serial shell cmd package resolve-activity --brief $package 2>&1 | Out-String)
                $result.checks.packageResolvable = ($resolve -match [regex]::Escape($package))
                if ($resolve -notmatch [regex]::Escape($package)) {
                    $result.healthy = $false
                    $result.reason = "Package $package not resolvable"
                    return $result
                }
            }

            return $result
        }

        Check-Device -serial $s -package $pkg

    } -ArgumentList $serial, $PackageName, $TimeoutSec
}

Write-Host "Waiting for $($jobs.Count) parallel checks to complete..."

$results = @()
$jobs | Wait-Job -Timeout $TimeoutSec | ForEach-Object {
    $jobResult = Receive-Job -Job $_
    if ($jobResult) {
        $results += $jobResult
    }
    Remove-Job -Job $_
}

# Summary
$healthyCount = ($results | Where-Object { $_.healthy }).Count
$unhealthyCount = $results.Count - $healthyCount

Write-Host "Device gate complete: $healthyCount healthy, $unhealthyCount unhealthy"

$summary = @{
    totalDevices = $Serials.Count
    healthyDevices = $healthyCount
    unhealthyDevices = $unhealthyCount
    results = $results
    timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
}

return $summary | ConvertTo-Json -Depth 5
