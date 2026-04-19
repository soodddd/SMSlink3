# SMS-link 测试 Agent 升级问题复盘

## 1. 目的

本文总结 SMS-link 在模拟机自动化测试过程中反复出现、耗时较长、容易误判的问题，并给出测试 agent 的升级规则。目标是让后续 agent 在执行发现、配对、文件传输、通知流转、短信流转、多设备主设备切换时，能够自动完成环境判定、证据采集、失败归因、代码缺陷确认与回归闭环。

本文面向测试 agent 升级，不是产品功能说明。所有规则都应服务于三个目标：

- 不把模拟器、系统服务、脚本缺陷误判为产品缺陷。
- 不把源端日志成功误判为端到端功能通过。
- 每个功能必须同时具备源端证据、目标端证据、崩溃/ANR 扫描证据。

## 2. 高耗时与反复问题总览

| 问题 | 典型表现 | 根因或风险 | 测试 agent 升级规则 |
| --- | --- | --- | --- |
| 模拟机离线或半离线 | `emulator-5556 offline`，安装/启动失败，设备列表存在但无法交互 | 模拟器状态不稳定，继续等待会浪费大量时间 | 每轮测试前执行设备健康门禁：`adb devices -l`、`sys.boot_completed`、应用可安装、可启动、可 dump UI。离线设备直接移出本轮矩阵并重新选择可用设备 |
| 平台 ANR 与产品 ANR 混杂 | `Pixel Launcher isn't responding`、`SMS-link isn't responding`、输入超时 | 可能是系统桌面卡死，也可能是 app 主线程阻塞 | 必须采集 `logcat`、`dumpsys activity`、`dropbox`，按进程和栈归类。Launcher/SystemUI ANR 归为平台阻塞；出现 app 主线程、service callback 阻塞才归为产品缺陷 |
| 旧证据污染 | 旧截图、旧 XML、旧 relay peer、旧 logcat 被重复使用 | 多轮测试目录和设备 ID 混淆，导致假阳性 | 每个场景创建独立时间戳 artifact 目录。每个证据文件名必须包含 serial、功能名、阶段名。每个场景开始先 `adb logcat -c`，禁止使用上一轮截图或 XML 判定当前结果 |
| 发现状态短时间过期 | UI 刚显示连接，几秒后功能同步失败 | `getConnectedDevices()` 依赖 `lastSeen`，连接有效窗口短，超过窗口后目标设备不再被认为在线 | 增加 `ensureConnected(A,B)`：两端打开设备页、触发搜索/发现、确认双方 `已连接` 后 10 秒内立即执行功能动作 |
| 通知权限与监听状态混淆 | 已授予通知权限但页面仍显示 Permission Required，或设置页显示 Active 但功能未启动 | `POST_NOTIFICATIONS`、Notification Listener、应用页面状态、后台 service 是不同层级 | 权限门禁必须同时验证 runtime permission、secure enabled listener、页面状态、service 状态。授权后重启 app 或刷新页面再判定 |
| 通知同步只验证源端 | 源端日志出现 `Notification synced successfully`，目标端 UI 仍为空 | 源端发送成功不等于目标端收到、入库、显示 | 通知流转必须四段证据齐全：源端收到系统通知、源端选择目标并发送、目标端收到 remote payload 并处理、目标 UI 或系统通知出现镜像通知 |
| 通知回流或重复发送 | 一条通知反复同步，日志刷屏，UI 卡顿甚至 ANR | 同步后的远端通知再次被监听并转发；连续 Flow 被反复 collect | 测试 agent 发现重复发送时必须检查 `isSynced` 过滤、Flow 是否使用快照、ViewModel 是否存在重复 collect |
| SMS 本地接收与跨设备同步混为一谈 | `adb emu sms send` 成功，本地 DB 有短信，但远端无短信 | 本地短信接收链路与设备间网络传输链路是两个功能段 | SMS 测试拆成三段：本地接收、传输发送、远端接收显示。只有三段都通过才算短信流转通过 |
| 1716/1816 端口混淆 | 连接 `10.0.2.2:1716` 超时，或 relay 端口被错误占用 | 1716 是 TLS/数据连接端口，1816 是模拟器 HTTP relay 端口 | agent 必须区分端口职责：检查 1816 relay 进程是否可用，检查 1716 数据连接是否能建立，禁止用 1816 结果替代 1716 传输结果 |
| 文件传输 EOF 误判 | 日志出现 EOF/reset，但传输已完成 | 连接在传输完成后关闭，可能属于正常收尾 | 文件传输通过标准必须以发送端 `Transfer progress: 1.0`/`File sent successfully` 和接收端 `File received successfully` 为准。完成后的 EOF 不单独判失败 |
| 主设备切换受旧配对状态影响 | UI 中多个历史设备显示主设备，或切换后本机未降级 | 历史配对记录残留，或远端主设备赋值未触发本机降级 | 切换测试前记录当前参与设备 ID，只判定本轮两台/四台设备。远端设为主设备后，必须验证本机角色降为副设备，远端角色为主设备 |
| UI 坐标漂移 | 不同分辨率下设置、搜索、返回按钮点击失败 | 手机/平板分辨率不同，硬编码坐标不可靠 | 所有点击优先基于 UI XML 的 text、content-desc、resource-id、bounds，计算中心点点击。坐标只能作为最后兜底 |
| 页面栈未回到目标页 | 在通知设置页时点击底部设备 tab 无效，导致后续步骤失效 | 嵌套页面或设置页没有退出 | 每个场景开始前执行页面归位：检查当前页面标题/关键文本，不匹配时按 Back 到根页，再进入目标 tab |
| PowerShell 脚本变量冲突 | `$Args`、`$pid`、参数名冲突导致脚本失败 | PowerShell 保留变量或自动变量被误用 | 测试脚本禁止使用 `$Args`、`$PID`、`$Host` 等保留名作为业务变量。统一使用 `$ScriptArgs`、`$ProcessIdValue` 等明确变量 |
| 搜索工具不可用 | `rg.exe` access denied | Windows 环境或权限导致工具不可执行 | agent 工具链必须有 fallback：`rg` 失败时自动切换 `Get-ChildItem` + `Select-String`，不得因此中断测试 |
| 中文编码混乱 | Markdown 或日志在 PowerShell 中显示乱码 | 控制台编码与文件 UTF-8 不一致 | 写入报告与证据统一 UTF-8。PowerShell 读取中文文件时显式使用 `-Encoding UTF8`，必要时切换 `[Console]::OutputEncoding` |
| 非导出 service 误启动 | `adb shell am start-foreground-service` 被拒绝 | service `exported=false` 是正常安全配置 | 不允许把非导出 service 无法被 shell 直接启动判为产品缺陷。应通过 app UI 或合法入口启动 |

## 3. 自动化门禁流程

测试 agent 每执行一个功能场景前，必须通过以下门禁。任何门禁失败都要先归类为环境阻塞、平台阻塞、脚本缺陷或产品缺陷，不能直接进入代码修改。

### 3.1 模拟机矩阵健康门禁

必须检查：

- `adb devices -l` 中设备为 `device`，不能是 `offline` 或 `unauthorized`。
- `adb -s <serial> shell getprop sys.boot_completed` 返回 `1`。
- `adb -s <serial> shell wm size`、`uiautomator dump` 可执行。
- app 可安装、可启动，启动后前台 activity 属于 `com.smslink`。
- 没有系统级 ANR 弹窗阻挡交互。

失败处理：

- 单台设备离线：从矩阵剔除并替换，不在原设备上无限重试。
- 多台设备系统 ANR：重启模拟器或重新选择矩阵，标记为平台阻塞。
- app 启动后立即 ANR：采集栈后进入产品缺陷分析。

### 3.2 权限门禁

通知、短信、文件测试前必须分别确认权限状态。

通知流转必须检查：

```powershell
adb -s <serial> shell pm grant com.smslink android.permission.POST_NOTIFICATIONS
adb -s <serial> shell cmd notification allow_listener com.smslink/.notification.NotificationListenerServiceImpl
adb -s <serial> shell dumpsys notification
adb -s <serial> shell dumpsys activity services com.smslink
```

判定规则：

- `POST_NOTIFICATIONS` 只代表 app 可发系统通知。
- Notification Listener 授权只代表系统允许 app 监听通知。
- 页面中的 `Start Listening`/`Active` 是 app 自身状态，必须单独验证。
- 授权后页面仍显示旧状态时，必须重启 app 或刷新页面后再判定。

### 3.3 发现与连接门禁

任何跨设备功能前，必须执行连接确认。

建议 agent 实现：

```text
ensureConnected(source, target):
  1. 两台设备打开 Devices 页面。
  2. 两台设备触发搜索或发现。
  3. 读取 UI/XML，确认目标设备显示“已连接”。
  4. 读取日志，确认 connectedDevices 数量大于 0。
  5. 在 10 秒有效窗口内立即执行业务动作。
```

注意：

- 不允许用一分钟前的连接 UI 判定当前可同步。
- 不允许只看 relay peers 判定 app 层已连接。
- 连接失败时先采集 1816 relay 与 1716 数据连接证据，再决定是否修改产品代码。

## 4. 专项测试升级规则

### 4.1 通知流转

通知流转是最容易误判的功能，必须做端到端闭环。

通过标准：

- 源设备收到测试通知，`NotificationListenerServiceImpl.onNotificationPosted` 有记录。
- 源设备将通知同步给目标设备，目标设备 ID 与本轮目标一致。
- 目标设备收到 remote notification payload，日志出现远端处理或系统通知展示证据。
- 目标设备 UI 或系统通知栏能看到镜像通知。
- 全流程结束后没有 app ANR、crash、重复同步风暴。

必须避免的误判：

- 只看到源端 `Notification synced successfully` 就判通过。
- 只看到通知设置页 `Active` 就判通过。
- 目标 UI 仍是 `No notifications` 时判通过。
- 目标端没被选中或已过连接窗口时继续测试。

已发现并应纳入回归的产品缺陷：

- 通知监听回调内执行重活导致 ANR。修复方向：`onNotificationPosted` 只做轻量调度，耗时逻辑放到 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`。
- 远端同步通知再次被转发，形成回流。修复方向：发送前过滤 `isSynced=true` 的通知。
- 单条通知持续收集 `getConnectedDevices()` Flow，设备状态变化时重复发送。修复方向：发送时使用一次性快照，例如 `.first()`。
- ViewModel 多次进入页面后重复 collect，造成 UI 压力。修复方向：取消旧 job 或使用一次性加载。
- connected devices 观察未去重，导致日志和 UI 刷新风暴。修复方向：对设备列表做稳定 key 去重和 `distinctUntilChanged`。

最新未完全闭环的风险：

- 曾出现源端已发送、目标端日志部分存在，但目标通知列表仍显示 `No notifications` 的情况。后续 agent 必须把“目标 UI 可见”作为单独必过项，不得用源端发送成功替代。

### 4.2 短信流转

短信流转必须拆分为本地短信接收和跨设备同步两个层级。

本地接收通过标准：

- `adb emu sms send <number> <body>` 成功。
- `SmsReceiver` 收到广播。
- `SmsManagerImpl` 或 repository 写入本地数据库。
- 本机短信 UI 可见。

跨设备同步通过标准：

- 源端本地短信入库。
- 源端选择目标设备并发送同步消息。
- 1716 数据连接建立成功，不能只有 1816 relay 成功。
- 目标端收到 SMS payload 并入库。
- 目标端 UI 可见同步短信。

反复问题与解决办法：

- 如果本地接收通过但远端没有短信，优先排查连接层，不要直接修改 SMS 解析逻辑。
- 如果日志指向 `TcpConnectionImpl.connect` 或 `ConnectionManagerImpl` 超时，先验证 1716 数据端口、目标 deviceId、当前连接窗口。
- 如果目标设备已掉出 connectedDevices 10 秒窗口，需要重新发现后再发短信。

### 4.3 文件传输

文件传输需要关注传输完成证据，而不是连接关闭的尾部日志。

通过标准：

- 发送端选择文件成功。
- 目标设备选择正确。
- 发送端进度达到 `1.0` 或出现 `File sent successfully`。
- 接收端出现 `File received successfully`。
- 接收端文件实际存在或 UI 展示接收记录。

误判修正：

- 传输完成后的 EOF、connection reset、socket close 不能单独判失败。
- DocumentsUI 或系统文件选择器日志不能直接判为 app 缺陷。
- 如果未到达完成日志，则再分析连接、权限、文件 URI、存储路径。

### 4.4 多设备主设备切换

主设备切换必须只围绕本轮参与设备判定，避免被旧配对记录干扰。

通过标准：

- 初始状态中记录四台设备的 deviceId、名称、角色。
- 对某一远端设备执行“设为主设备”。
- 本机角色被降级为副设备。
- 被设置的远端设备角色显示为主设备。
- 其他设备没有错误抢占主设备。
- 重启 app 后角色仍保持一致。

已发现并应纳入回归的缺陷：

- 远端设备被设置为 MAIN/CELLULAR_SOURCE 后，本机没有同步降级。修复方向：处理远端独占角色时同步 demote local device。

agent 升级规则：

- 测试前清理或标记历史配对设备，避免多个旧设备 MAIN 状态误导结果。
- UI 判定必须绑定 deviceId 或本轮设备名称，不能只搜索页面上任意一个“主设备”。

## 5. 失败归因分类器

测试 agent 必须把失败分成以下类型，并在报告中明确类型。

### 5.1 产品缺陷

满足以下条件之一才进入代码修复：

- app 进程 crash，堆栈指向业务代码。
- app ANR，栈指向 app 主线程、service 回调或 repository/ViewModel 阻塞。
- 源端和目标端连接健康，但业务 payload 未发送、未处理或未入库。
- UI 状态与 repository/log 明显不一致，刷新后仍不一致。

### 5.2 平台阻塞

典型情况：

- Pixel Launcher/SystemUI ANR。
- 模拟机离线、boot 未完成。
- 输入系统超时但 app 栈无阻塞。

处理方式：

- 记录证据，重启或替换模拟机。
- 不修改产品代码。

### 5.3 环境阻塞

典型情况：

- relay 1816 未启动或端口被占用。
- adb server 异常。
- 目标设备不在 connectedDevices 窗口。
- Windows 防火墙或端口绑定异常。

处理方式：

- 修复测试环境后重跑。
- 不直接改业务代码。

### 5.4 测试脚本缺陷

典型情况：

- PowerShell 变量名冲突。
- 坐标点击错误。
- 使用旧截图或旧 XML。
- 中文编码导致报告乱码。
- `rg` 不可用但脚本没有 fallback。

处理方式：

- 修复测试脚本或 agent 工具链。
- 原功能结果标记为未完成，而不是失败。

## 6. 日志与证据标准

每个场景必须生成一个独立 artifact 目录，例如：

```text
artifacts/<timestamp>_<scenario>/
  matrix.json
  preflight_<serial>.txt
  ui_before_<serial>.xml
  ui_after_<serial>.xml
  screen_before_<serial>.png
  screen_after_<serial>.png
  logcat_<serial>.txt
  dumpsys_activity_<serial>.txt
  dumpsys_services_<serial>.txt
  result.json
```

`result.json` 至少包含：

```json
{
  "scenario": "notification_sync",
  "source": "emulator-5558",
  "target": "emulator-5554",
  "sourceDeviceId": "...",
  "targetDeviceId": "...",
  "preflight": "passed",
  "sourceEvidence": "passed",
  "targetEvidence": "failed",
  "anrCrashScan": "passed",
  "classification": "product_defect",
  "nextAction": "inspect target repository/UI refresh path"
}
```

## 7. 推荐执行状态机

测试 agent 不应线性盲测，应按状态机执行。

```text
START
  -> LOAD_REQUIREMENTS
  -> BUILD_TEST_MATRIX
  -> DEVICE_HEALTH_GATE
  -> INSTALL_OR_REINSTALL_APP
  -> PERMISSION_GATE
  -> FEATURE_SCENARIO
  -> SOURCE_EVIDENCE_GATE
  -> TARGET_EVIDENCE_GATE
  -> ANR_CRASH_GATE
  -> CLASSIFY_FAILURE
  -> PATCH_ONLY_CONFIRMED_PRODUCT_DEFECT
  -> REBUILD
  -> REGRESSION
  -> REPORT
```

关键规则：

- 任一门禁失败，先分类，不进入业务动作。
- 业务失败但连接不健康，先修环境，不改代码。
- 只有确认产品缺陷后才 patch。
- patch 后必须重新编译、重装、回归同一场景。
- 所有跨设备功能必须做目标端证据验证。

## 8. 不应误判为产品缺陷的情况

以下情况应优先归为平台、环境或脚本问题：

- `adb shell am start-foreground-service` 启动非导出 service 被拒绝。
- Launcher/SystemUI ANR，但 app 进程栈无阻塞。
- 设备处于 `offline`。
- 目标设备未显示连接或连接时间超过有效窗口。
- 文件传输完成后出现 socket EOF。
- DocumentsUI、系统设置页、权限页的噪声日志。
- 坐标点击落在错误页面或错误控件。
- 旧 artifact 中的成功截图。

## 9. 验收标准

升级后的测试 agent 必须满足：

- [ ] 能从技术需求书生成测试矩阵，覆盖发现、配对、文件传输、通知流转、短信流转、多设备主设备切换。
- [ ] 每个测试场景都有独立 artifact 目录。
- [ ] 每轮测试前执行模拟机健康门禁。
- [ ] 跨设备功能执行前强制执行连接门禁。
- [ ] 通知流转必须验证目标端 UI 或目标端系统通知可见。
- [ ] 短信流转必须分段验证本地接收、传输发送、远端显示。
- [ ] 主设备切换必须绑定本轮 deviceId 判定角色变化。
- [ ] 能区分产品缺陷、平台阻塞、环境阻塞、测试脚本缺陷。
- [ ] 只对确认的产品缺陷修改代码。
- [ ] 每次修改后自动编译、安装、重跑失败场景。
- [ ] 报告中必须包含失败分类、证据路径、下一步动作。

## 10. 对后续 agent 的强制提示词建议

建议在测试 agent 系统提示或技能文件中加入以下规则：

```text
你必须先完成设备健康门禁、权限门禁、连接门禁，才能执行跨设备功能测试。
你不能把源端发送成功当作端到端通过。
你不能使用旧截图、旧 XML、旧 logcat 判定当前结果。
你必须把失败分类为产品缺陷、平台阻塞、环境阻塞、测试脚本缺陷之一。
只有确认产品缺陷后才能修改代码。
通知流转必须证明目标设备收到并显示镜像通知。
短信流转必须证明目标设备收到并显示同步短信。
文件传输完成后的 EOF 不能单独判失败。
非导出 service 无法被 adb 直接启动不能判为产品缺陷。
```

