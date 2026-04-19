param(
  [string]$PackRoot = (Split-Path -Parent $PSScriptRoot),
  [string]$WorkspaceRoot = ""
)

Write-Host "SMS-link Agent Pack installer"
Write-Host "Pack root: $PackRoot"

if (-not (Test-Path $PackRoot)) {
  throw "Pack root not found: $PackRoot"
}

Write-Host ""
Write-Host "1) Make sure these tools are installed and callable:"
Write-Host "   - adb"
Write-Host "   - emulator"
Write-Host "   - maestro"
Write-Host "   - node / npm or pnpm for agent-device"
Write-Host ""

if ($WorkspaceRoot -and (Test-Path $WorkspaceRoot)) {
  $target = Join-Path $WorkspaceRoot ".agents\smslink_agent_pack"
  New-Item -ItemType Directory -Force -Path $target | Out-Null
  Copy-Item -Recurse -Force "$PackRoot\*" $target
  Write-Host "Copied pack to $target"
} else {
  Write-Host "WorkspaceRoot not supplied or missing."
  Write-Host "Manual step: copy this folder into your repo, for example:"
  Write-Host "  <repo>\.agents\smslink_agent_pack"
}

Write-Host ""
Write-Host "2) Copy your project-specific files into the pack or workspace docs folder:"
Write-Host "   - acceptance-rules.md"
Write-Host "   - log-playbook.md"
Write-Host "   - test-matrix.md"
Write-Host "   - prepare_android_emulator.ps1"
Write-Host "   - collect_android_logs.ps1"
Write-Host ""
Write-Host "3) Load skills from the skills folder if your agent supports local skill loading."
Write-Host "4) Start with smslink-orchestrator."
