# Evidence Index Generator
# Purpose: Create structured index of all evidence files for easy querying
# Expected: Save 2-5 minutes per round of evidence correlation

param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [string]$OutputPath = "evidence-index.json"
)

$ErrorActionPreference = "Continue"

Write-Host "Scanning evidence directory: $EvidenceDir"

if (-not (Test-Path $EvidenceDir)) {
    Write-Error "Evidence directory not found: $EvidenceDir"
    exit 1
}

$index = @{
    generatedAt = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    evidenceDir = $EvidenceDir
    scenarios = @{}
    devices = @{}
    summary = @{
        totalFiles = 0
        totalScenarios = 0
        totalDevices = 0
    }
}

# Scan all files in evidence directory
$allFiles = Get-ChildItem -Path $EvidenceDir -File -Recurse

foreach ($file in $allFiles) {
    $index.summary.totalFiles++

    $fileName = $file.Name
    $relativePath = $file.FullName.Replace($EvidenceDir, "").TrimStart("\", "/")

    # Extract device serial from filename (e.g., "logcat-5554.txt" -> "5554")
    $serialMatch = [regex]::Match($fileName, "(emulator-\d+|\d{4}|[A-Z0-9]{8,})")
    $serial = if ($serialMatch.Success) { $serialMatch.Groups[1].Value } else { "unknown" }

    # Track device
    if (-not $index.devices.ContainsKey($serial)) {
        $index.devices[$serial] = @{
            serial = $serial
            files = @()
        }
        $index.summary.totalDevices++
    }
    $index.devices[$serial].files += $relativePath

    # Infer scenario from filename or directory structure
    $scenarioId = "unknown"

    if ($fileName -match "file.*transfer|file.*send|file.*receive") {
        $scenarioId = "file_transfer"
    } elseif ($fileName -match "notif|notification") {
        $scenarioId = "notification_sync"
    } elseif ($fileName -match "sms|message") {
        $scenarioId = "sms_sync"
    } elseif ($fileName -match "pair|discover|connect") {
        $scenarioId = "device_pairing"
    } elseif ($fileName -match "preflight|health") {
        $scenarioId = "device_health"
    }

    # If in subdirectory, use directory name as scenario
    $parentDir = Split-Path -Parent $file.FullName
    $parentDirName = Split-Path -Leaf $parentDir
    if ($parentDirName -ne (Split-Path -Leaf $EvidenceDir)) {
        $scenarioId = $parentDirName
    }

    # Track scenario
    if (-not $index.scenarios.ContainsKey($scenarioId)) {
        $index.scenarios[$scenarioId] = @{
            scenarioId = $scenarioId
            files = @{}
        }
        $index.summary.totalScenarios++
    }

    # Categorize file type
    $fileType = switch -Regex ($fileName) {
        "logcat.*\.txt$" { "logcat" }
        "ui.*\.xml$" { "ui_dump" }
        "screen.*\.png$" { "screenshot" }
        "window.*\.txt$" { "window_dump" }
        "activity.*\.txt$" { "activity_dump" }
        "services.*\.txt$" { "services_dump" }
        "process.*\.txt$|meminfo.*\.txt$" { "process_info" }
        "preflight.*\.json$" { "preflight_result" }
        "state.*\.json$" { "device_state" }
        "crash.*\.txt$" { "crash_log" }
        default { "other" }
    }

    # Add to scenario index
    if (-not $index.scenarios[$scenarioId].files.ContainsKey($serial)) {
        $index.scenarios[$scenarioId].files[$serial] = @{}
    }
    if (-not $index.scenarios[$scenarioId].files[$serial].ContainsKey($fileType)) {
        $index.scenarios[$scenarioId].files[$serial][$fileType] = @()
    }
    $index.scenarios[$scenarioId].files[$serial][$fileType] += $relativePath
}

Write-Host "Evidence index generated:"
Write-Host "  Total files: $($index.summary.totalFiles)"
Write-Host "  Total scenarios: $($index.summary.totalScenarios)"
Write-Host "  Total devices: $($index.summary.totalDevices)"

# Save index
$outputFullPath = Join-Path $EvidenceDir $OutputPath
$index | ConvertTo-Json -Depth 10 | Out-File -FilePath $outputFullPath -Encoding UTF8 -Force

Write-Host "Index saved to: $outputFullPath"

return $index | ConvertTo-Json -Depth 10
