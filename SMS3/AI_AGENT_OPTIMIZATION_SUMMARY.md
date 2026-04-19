# AI Agent 测试修复速度优化 - 实施完成总结

## 已完成的优化工具

### Phase 1: 快速胜利 ✅

1. **快速失败分类器** (`fast-blocker-classifier.ps1`)
   - 12 类平台问题的快速识别
   - 预期收益：5-10 分钟 → 5-10 秒

2. **预检超时优化** (`prepare_android_emulator.ps1` 已修改)
   - 独立超时配置：adbWait=10s, boot=60s, pm=30s, launch=20s, process=15s
   - 预期收益：健康设备 180 秒 → 15 秒

3. **懒惰证据收集器** (`lazy-evidence-collector.ps1`)
   - 两阶段收集：最小分类 2-5 秒，完整证据仅对产品缺陷
   - 预期收益：30 秒 → 5 秒（非产品缺陷）

### Phase 2: 上下文保持 ✅

4. **共享状态账本** (`execution-ledger-manager.ps1`)
   - 持久化执行状态，防止重复操作
   - 跟踪设备重启次数、场景尝试次数、补丁历史
   - 预期收益：节省 5-10 分钟/轮

5. **设备身份缓存** (`device-identity-cache-template.json`)
   - 轮次内复用设备身份验证结果
   - 预期收益：节省 50-100 秒/轮

### Phase 3: 并行化 ✅

6. **并行设备门禁** (`parallel-device-gate.ps1`)
   - PowerShell 并行作业同时检查所有设备
   - 预期收益：4 设备从 60 秒 → 15 秒

7. **智能重启策略** (`restart-policy.md`)
   - 区分瞬态 vs 持久故障
   - 每设备最多重启 1 次/轮
   - 预期收益：节省 2-4 分钟/轮

### Phase 4: 诊断增强 ✅

8. **自动日志解析器** (`log-parser.ps1`)
   - 20+ 失败模式的自动识别
   - 生成结构化诊断报告
   - 预期收益：节省 3-5 分钟/失败

9. **证据索引生成器** (`evidence-index-generator.ps1`)
   - 自动生成证据清单 JSON
   - 按场景、设备、文件类型组织
   - 预期收益：节省 2-5 分钟/轮

---

## 使用指南

### 1. 快速失败分类器

```powershell
# 使用最小证据快速分类
.\fast-blocker-classifier.ps1 -Serial "emulator-5554"

# 使用已收集的证据文件
.\fast-blocker-classifier.ps1 `
    -Serial "emulator-5554" `
    -LogcatPath "artifacts/logcat-5554.txt" `
    -DumpsysWindowPath "artifacts/window-5554.txt" `
    -BootCompleted "1" `
    -PackageManagerOutput "package:com.android.systemui..."
```

**输出示例**：
```json
{
  "serial": "emulator-5554",
  "classification": "platform_ui",
  "reason": "System UI ANR detected in dumpsys window",
  "confidence": "high",
  "timestamp": "2026-04-19T11:30:00"
}
```

### 2. 优化后的预检脚本

```powershell
# 使用优化后的超时配置（健康设备 ~15 秒）
.\prepare_android_emulator.ps1 `
    -Serial "emulator-5554" `
    -PackageName "com.smslink" `
    -ActivityComponent "com.smslink/.MainActivity" `
    -DisableAnimations `
    -ClearLogcat

# 自定义超时（如果需要）
.\prepare_android_emulator.ps1 `
    -Serial "emulator-5554" `
    -AdbWaitTimeoutSec 10 `
    -BootTimeoutSec 60 `
    -PackageManagerTimeoutSec 30 `
    -AppLaunchTimeoutSec 20 `
    -ProcessStableTimeoutSec 15
```

### 3. 懒惰证据收集器

```powershell
# Phase 1: 最小证据收集（2-5 秒）
$minimalResult = .\lazy-evidence-collector.ps1 `
    -Serial "emulator-5554" `
    -Phase "minimal" `
    -OutputDir "artifacts/current-round" `
    -Prefix "scenario1"

# 快速分类
$classification = .\fast-blocker-classifier.ps1 `
    -Serial "emulator-5554" `
    -LogcatPath "artifacts/current-round/scenario1-logcat-tail-emulator-5554.txt"

# Phase 2: 仅对产品缺陷收集完整证据（10-30 秒）
if ($classification.classification -eq "app_crash" -or $classification.classification -eq "app_anr") {
    $fullResult = .\lazy-evidence-collector.ps1 `
        -Serial "emulator-5554" `
        -Phase "full" `
        -OutputDir "artifacts/current-round" `
        -Prefix "scenario1"
}
```

### 4. 共享状态账本

```powershell
# 初始化轮次
.\execution-ledger-manager.ps1 -Action init -RoundId "2026-04-19-001"

# 更新设备状态
.\execution-ledger-manager.ps1 `
    -Action update-device `
    -Serial "emulator-5554" `
    -DeviceUpdate @{
        deviceId = "61446e3099ea6717"
        healthCheckPassed = $true
        lastHealthCheck = (Get-Date -Format "yyyy-MM-ddTHH:mm:ss")
    }

# 更新场景状态
.\execution-ledger-manager.ps1 `
    -Action update-scenario `
    -ScenarioId "file_transfer_5554_to_5556" `
    -ScenarioUpdate @{
        status = "failed"
        classification = "connection_timeout"
        evidencePath = "artifacts/2026-04-19-001/file_transfer/"
    }

# 记录补丁
.\execution-ledger-manager.ps1 `
    -Action add-patch `
    -PatchInfo @{
        file = "ConnectionManagerImpl.kt"
        reason = "TLS timeout too short"
        scenarioId = "file_transfer_5554_to_5556"
    }

# 检查设备是否已重启过
$deviceState = .\execution-ledger-manager.ps1 -Action get-device -Serial "emulator-5554" | ConvertFrom-Json
if ($deviceState.restartCount -ge 1) {
    Write-Host "Device already restarted once this round. Marking as blocker."
}
```

### 5. 并行设备门禁

```powershell
# 并行检查多个设备
$result = .\parallel-device-gate.ps1 `
    -Serials @("emulator-5554", "emulator-5556", "emulator-5558", "emulator-5560") `
    -PackageName "com.smslink" `
    -TimeoutSec 120

$summary = $result | ConvertFrom-Json
Write-Host "Healthy devices: $($summary.healthyDevices) / $($summary.totalDevices)"

# 过滤出健康设备
$healthySerials = $summary.results | Where-Object { $_.healthy } | ForEach-Object { $_.serial }
```

### 6. 自动日志解析器

```powershell
# 解析日志并自动提取失败原因
$parseResult = .\log-parser.ps1 `
    -LogcatPath "artifacts/logcat-5554.txt" `
    -DumpsysWindowPath "artifacts/window-5554.txt"

$parsed = $parseResult | ConvertFrom-Json
Write-Host "Primary failure: $($parsed.primaryFailure)"
Write-Host "Recommendation: $($parsed.recommendation)"

# 根据推荐决定下一步
if ($parsed.recommendation -match "product_defect") {
    # 收集完整证据并修复代码
} elseif ($parsed.recommendation -match "platform") {
    # 标记为平台阻塞，不修改代码
}
```

### 7. 证据索引生成器

```powershell
# 为证据目录生成索引
.\evidence-index-generator.ps1 `
    -EvidenceDir "artifacts/2026-04-19-001" `
    -OutputPath "evidence-index.json"

# 读取索引查询证据
$index = Get-Content "artifacts/2026-04-19-001/evidence-index.json" | ConvertFrom-Json

# 查找特定场景的所有证据
$fileTransferEvidence = $index.scenarios.file_transfer

# 查找特定设备的所有证据
$device5554Evidence = $index.devices."emulator-5554"
```

---

## 集成到现有工作流

### 修改 smslink-executor skill

在 `.agents/smslink_agent_pack/skills/smslink-executor/SKILL.md` 中集成新工具：

```markdown
## Preflight Gate (优化后)

使用优化后的预检脚本：
- 健康设备 15 秒通过（vs 180 秒）
- 独立超时配置

## Evidence Collection (优化后)

两阶段证据收集：
1. 最小证据（2-5 秒）→ 快速分类
2. 完整证据（仅对产品缺陷）

## Fast Classification (新增)

在完整 triage 前使用快速分类器：
- 平台问题 5-10 秒识别
- 跳过完整 triage 流程

## Device Gate (优化后)

使用并行设备门禁：
- 4 设备从 60 秒 → 15 秒
```

### 修改 smslink-orchestrator skill

在 `.agents/smslink_agent_pack/skills/smslink-orchestrator/SKILL.md` 中集成状态账本：

```markdown
## Round Initialization (新增)

初始化执行账本：
- 创建轮次 ID
- 初始化设备和场景跟踪

## Restart Policy (新增)

检查账本防止重复重启：
- 每设备最多重启 1 次/轮
- 每场景最多尝试 2 次

## Patch Tracking (新增)

记录所有代码补丁：
- 文件、原因、时间戳
- 关联到场景 ID
```

### 修改 smslink-triage skill

在 `.agents/smslink_agent_pack/skills/smslink-triage/SKILL.md` 中集成日志解析器：

```markdown
## Automated Log Parsing (新增)

在手动分析前使用自动解析器：
- 20+ 失败模式自动识别
- 生成结构化诊断报告
- 高置信度结果直接使用
```

---

## 预期性能提升

### 单轮测试（2 设备 + 3 失败 + 1 修复）

| 阶段 | 优化前 | 优化后 | 节省 |
|------|--------|--------|------|
| 预检 | 360s (6 分钟) | 30s | 330s |
| 设备门禁 | 30s | 15s | 15s |
| 证据收集（3 次） | 90s | 15s | 75s |
| 失败分类（3 次） | 900s (15 分钟) | 30s | 870s |
| 重建 | 120s | 30s | 90s |
| **总计** | **25 分钟** | **2 分钟** | **23 分钟（92%）** |

### 多设备测试（4 设备 + 多次修复）

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| 单轮时间 | 2+ 小时 | 20-30 分钟 | 75% |
| 平台问题识别 | 5-10 分钟 | 5-10 秒 | 97% |
| 重复操作 | 频繁 | 消除 | 100% |

---

## 验证步骤

### 1. 基准测试（优化前）

```powershell
$baseline = Measure-Command {
    # 运行标准测试场景
    # 2 设备配对 + 文件传输 + 通知同步
}
Write-Host "Baseline: $($baseline.TotalMinutes) minutes"
```

### 2. Phase 1 验证

```powershell
$phase1 = Measure-Command {
    # 使用快速失败 + 优化超时 + 懒惰证据收集
}
Write-Host "Phase 1: $($phase1.TotalMinutes) minutes (expected: 10-15 min)"
```

### 3. Phase 2 验证

```powershell
# 运行 2 轮连续测试
$round1 = Measure-Command { # 第一轮 }
$round2 = Measure-Command { # 第二轮 }

# 检查账本
$ledger = Get-Content "artifacts/current-round/execution-ledger.json" | ConvertFrom-Json
Write-Host "Devices tracked: $($ledger.devices.Count)"
Write-Host "Scenarios tracked: $($ledger.scenarios.Count)"
Write-Host "Patches recorded: $($ledger.patches.Count)"
```

### 4. Phase 3 验证（4 设备）

```powershell
$phase3 = Measure-Command {
    # 4 设备并行门禁 + 智能重启
}
Write-Host "Phase 3 (4 devices): $($phase3.TotalMinutes) minutes (expected: 30-40 min)"
```

### 5. Phase 4 验证

```powershell
# 检查自动生成的诊断报告
$parseResult = Get-Content "artifacts/current-round/log-parse-result.json" | ConvertFrom-Json
Write-Host "Auto-detected failure: $($parseResult.primaryFailure)"
Write-Host "Confidence: $($parseResult.confidence)"

# 检查证据索引
$index = Get-Content "artifacts/current-round/evidence-index.json" | ConvertFrom-Json
Write-Host "Indexed files: $($index.summary.totalFiles)"
Write-Host "Indexed scenarios: $($index.summary.totalScenarios)"
```

---

## 成功标准

- [x] 所有 9 个优化工具已创建
- [ ] 2 设备单轮测试从 30 分钟降至 10 分钟以内
- [ ] 4 设备测试从 2 小时降至 30 分钟以内
- [ ] 平台阻塞识别从 5 分钟降至 10 秒以内
- [ ] 不再出现重复执行相同失败操作
- [ ] 每次失败自动生成诊断报告

---

## 下一步

1. **集成到现有 skill**：修改 orchestrator、executor、triage skill 引用新工具
2. **运行基准测试**：记录优化前的性能基线
3. **逐阶段验证**：按 Phase 1-4 顺序验证效果
4. **调优参数**：根据实际测试结果调整超时、重试次数等参数
5. **文档更新**：更新 SKILL.md 和使用指南

---

## 文件清单

### 新增文件（9 个）

1. `.claude/skills/android-real-device-qa/fast-blocker-classifier.ps1`
2. `.claude/skills/android-real-device-qa/lazy-evidence-collector.ps1`
3. `.claude/skills/android-real-device-qa/execution-ledger-manager.ps1`
4. `.claude/skills/android-real-device-qa/parallel-device-gate.ps1`
5. `.claude/skills/android-real-device-qa/log-parser.ps1`
6. `.claude/skills/android-real-device-qa/evidence-index-generator.ps1`
7. `.claude/skills/android-real-device-qa/references/restart-policy.md`
8. `artifacts/current-round/execution-ledger-template.json`
9. `artifacts/current-round/device-identity-cache-template.json`

### 修改文件（1 个）

1. `scripts/prepare_android_emulator.ps1` - 优化超时配置

### 待修改文件（集成阶段）

1. `.agents/smslink_agent_pack/skills/smslink-orchestrator/SKILL.md`
2. `.agents/smslink_agent_pack/skills/smslink-executor/SKILL.md`
3. `.agents/smslink_agent_pack/skills/smslink-triage/SKILL.md`
4. `.claude/skills/android-real-device-qa/SKILL.md`
