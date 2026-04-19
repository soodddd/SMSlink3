# Execution Ledger Manager
# Purpose: Read/write shared state across agents
# Usage:
#   Initialize-Ledger -RoundId "2026-04-19-001"
#   Update-DeviceState -Serial "emulator-5554" -HealthCheckPassed $true
#   Update-ScenarioState -ScenarioId "file_transfer_5554_to_5556" -Status "failed" -Classification "link"
#   Add-Patch -File "ConnectionManagerImpl.kt" -Reason "TLS timeout too short"

param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("init", "read", "update-device", "update-scenario", "add-patch", "get-device", "get-scenario")]
    [string]$Action = "read",
    [string]$LedgerPath = "artifacts/current-round/execution-ledger.json",
    [string]$RoundId = "",
    [string]$Serial = "",
    [string]$DeviceId = "",
    [string]$ScenarioId = "",
    [hashtable]$DeviceUpdate = @{},
    [hashtable]$ScenarioUpdate = @{},
    [hashtable]$PatchInfo = @{}
)

$ErrorActionPreference = "Stop"

function Get-Ledger {
    param([string]$Path)
    if (Test-Path $Path) {
        return Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    return $null
}

function Save-Ledger {
    param([object]$Ledger, [string]$Path)
    $Ledger.lastUpdate = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    $Ledger | ConvertTo-Json -Depth 10 | Out-File -FilePath $Path -Encoding UTF8 -Force
}

switch ($Action) {
    "init" {
        if (-not $RoundId) {
            $RoundId = "round-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        }

        $ledger = @{
            roundId = $RoundId
            startTime = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
            lastUpdate = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
            status = "running"
            devices = @{}
            scenarios = @{}
            patches = @()
            metadata = @{
                maxRestartPerDevice = 1
                maxScenarioAttempts = 2
                requireFreshDiscovery = $true
            }
        }

        Save-Ledger -Ledger $ledger -Path $LedgerPath
        Write-Host "Ledger initialized: $RoundId"
        return $ledger | ConvertTo-Json -Depth 10
    }

    "read" {
        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger) {
            Write-Error "Ledger not found at $LedgerPath. Run with -Action init first."
        }
        return $ledger | ConvertTo-Json -Depth 10
    }

    "update-device" {
        if (-not $Serial) {
            Write-Error "Serial is required for update-device"
        }

        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger) {
            Write-Error "Ledger not found. Initialize first."
        }

        # Convert PSCustomObject to hashtable for manipulation
        $devicesHash = @{}
        if ($ledger.devices) {
            $ledger.devices.PSObject.Properties | ForEach-Object {
                $devicesHash[$_.Name] = $_.Value
            }
        }

        if (-not $devicesHash.ContainsKey($Serial)) {
            $devicesHash[$Serial] = @{
                serial = $Serial
                deviceId = ""
                healthCheckPassed = $false
                restartCount = 0
                lastHealthCheck = ""
                lastFailure = ""
            }
        }

        # Apply updates
        foreach ($key in $DeviceUpdate.Keys) {
            $devicesHash[$Serial][$key] = $DeviceUpdate[$key]
        }

        $ledger.devices = $devicesHash
        Save-Ledger -Ledger $ledger -Path $LedgerPath

        Write-Host "Device $Serial updated"
        return $devicesHash[$Serial] | ConvertTo-Json -Depth 5
    }

    "update-scenario" {
        if (-not $ScenarioId) {
            Write-Error "ScenarioId is required for update-scenario"
        }

        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger) {
            Write-Error "Ledger not found. Initialize first."
        }

        # Convert PSCustomObject to hashtable
        $scenariosHash = @{}
        if ($ledger.scenarios) {
            $ledger.scenarios.PSObject.Properties | ForEach-Object {
                $scenariosHash[$_.Name] = $_.Value
            }
        }

        if (-not $scenariosHash.ContainsKey($ScenarioId)) {
            $scenariosHash[$ScenarioId] = @{
                scenarioId = $ScenarioId
                status = "pending"
                attempts = 0
                lastFailure = ""
                classification = ""
                evidencePath = ""
                firstAttempt = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
            }
        }

        # Apply updates
        foreach ($key in $ScenarioUpdate.Keys) {
            $scenariosHash[$ScenarioId][$key] = $ScenarioUpdate[$key]
        }

        # Increment attempts if status changed to failed
        if ($ScenarioUpdate.ContainsKey("status") -and $ScenarioUpdate["status"] -eq "failed") {
            $scenariosHash[$ScenarioId]["attempts"] = $scenariosHash[$ScenarioId]["attempts"] + 1
            $scenariosHash[$ScenarioId]["lastAttempt"] = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
        }

        $ledger.scenarios = $scenariosHash
        Save-Ledger -Ledger $ledger -Path $LedgerPath

        Write-Host "Scenario $ScenarioId updated"
        return $scenariosHash[$ScenarioId] | ConvertTo-Json -Depth 5
    }

    "add-patch" {
        if (-not $PatchInfo.ContainsKey("file")) {
            Write-Error "PatchInfo must contain 'file' key"
        }

        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger) {
            Write-Error "Ledger not found. Initialize first."
        }

        $patch = @{
            file = $PatchInfo["file"]
            reason = $PatchInfo["reason"]
            timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
            scenarioId = $PatchInfo["scenarioId"]
        }

        # Convert patches array
        $patchesArray = @()
        if ($ledger.patches) {
            $patchesArray = @($ledger.patches)
        }
        $patchesArray += $patch

        $ledger.patches = $patchesArray
        Save-Ledger -Ledger $ledger -Path $LedgerPath

        Write-Host "Patch recorded: $($patch.file)"
        return $patch | ConvertTo-Json -Depth 5
    }

    "get-device" {
        if (-not $Serial) {
            Write-Error "Serial is required for get-device"
        }

        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger -or -not $ledger.devices.$Serial) {
            return "{}"
        }

        return $ledger.devices.$Serial | ConvertTo-Json -Depth 5
    }

    "get-scenario" {
        if (-not $ScenarioId) {
            Write-Error "ScenarioId is required for get-scenario"
        }

        $ledger = Get-Ledger -Path $LedgerPath
        if (-not $ledger -or -not $ledger.scenarios.$ScenarioId) {
            return "{}"
        }

        return $ledger.scenarios.$ScenarioId | ConvertTo-Json -Depth 5
    }
}
