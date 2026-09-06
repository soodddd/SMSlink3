# SMSlink3 验证报告 — 2026-09-06

## 1. 验证范围

本报告记录本轮代码修复后的 JVM 单元测试、Android Lint 和 Debug APK 构建结果。代码位于 `SMS3/`，分支为 `codex/smslink-rebuild`。

## 2. 自动化结果

| 检查项 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `./gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain` | PASS |
| 测试数量 | XML 测试报告汇总 | 313 tests / 0 failures / 0 errors / 0 skipped |
| 静态检查 | `./gradlew.bat :app:lintDebug --console=plain` | PASS |
| Lint | `app/build/reports/lint-results-debug.xml` | 0 Error / 123 Warning / 1 Information |
| Debug 构建 | `./gradlew.bat :app:assembleDebug --console=plain` | PASS |
| 补丁检查 | `git diff --check` | PASS；仅有 Windows 换行转换提示 |

## 3. 产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`FF982E029754FCA11CBB3C77CBEAEF7B396673B658BD752FA3C02A570C617F3B`
- APK 大小：62,507,726 bytes

## 4. 关键回归点

- 设备删除会清理连接、配对令牌和证书。
- 网络帧、BLE 分片和文件包均有长度/顺序边界。
- 连接管理具备并发锁、重连和旧连接保护。
- 短信多段状态不会在第一段成功时提前汇总为成功。
- 通知回执绑定设备和内容版本，内容更新会使旧回执失效。
- 文件完成包校验失败会清理接收会话并进入失败状态。
- 异常时间戳不会通过整数溢出制造错误短信匹配。

## 5. 证据边界

上述 PASS 只代表本地代码、单元测试、Lint 和 Debug 构建通过，不代表真实 Android 双机现场 PASS。硬件/系统依赖功能仍需安装 APK 后按发布说明逐项验收，并记录设备型号、Android 版本、权限状态、网络类型、SIM 状态和实际日志。

