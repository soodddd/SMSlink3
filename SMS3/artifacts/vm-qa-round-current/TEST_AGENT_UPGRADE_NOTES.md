# 测试 Agent 升级问题总结

日期: 2026-04-16

项目: SMS-link / SMS3

范围: 2 台 Android Emulator 第一阶段自动化测试、安装、抓 log、分析、修复、重编译、回归。

## 1. 总览

本轮最耗时的问题集中在 5 类：

1. Emulator / ADB 设备状态不稳定，设备会从在线变为完全消失。
2. `prepare_android_emulator.ps1` 在设备实际健康时仍会超时，导致预检阻塞。
3. Android Emulator NAT 不支持两台模拟机真实 LAN 直连，文件传输必须用 QA-only host proxy 桥接。
4. TLS 证书/私钥协商在 AndroidKeyStore + Conscrypt 上反复失败，错误表现多变。
5. UI 自动化坐标和 Windows 终端编码问题导致脚本误判或中断。

本文件记录每个问题的复现现象、影响、根因、已采用解决办法，以及建议加入测试 agent 的自动化策略。

## 2. 问题清单

### 问题 #1: ADB 设备列表突然为空

现象:

```text
adb devices -l
List of devices attached

adb get-state
error: no devices/emulators found
```

影响:

测试流程会误以为 app 无法启动或安装失败。实际上当时没有 emulator 进程，属于测试环境问题。

根因:

模拟机进程已退出或未启动，仅剩 `adb.exe` server。不是 app 缺陷。

本轮解决办法:

1. 检查 `adb devices -l`。
2. 检查本机进程中是否存在 `emulator` / `qemu`。
3. 查询 AVD 列表：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

4. 启动两台 AVD：

```powershell
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList @("-avd","SMSLink_Phone_API34","-no-boot-anim","-gpu","swiftshader_indirect","-noaudio","-camera-back","none")
Start-Process "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList @("-avd","SMSLink_Tablet_API34","-no-boot-anim","-gpu","swiftshader_indirect","-noaudio","-camera-back","none")
```

建议升级:

- 测试 agent 在发现 `adb devices` 为空时，不要直接报告测试失败。
- 先执行“环境恢复分支”：
  - 查 AVD 列表。
  - 启动首选 2 台 AVD。
  - 等待 `device` 状态。
  - 再继续 app 测试。
- 只有 AVD 不存在、启动失败或超过恢复预算，才标记为 `platform blocker`。

### 问题 #2: `prepare_android_emulator.ps1` 超时但设备实际健康

现象:

两台设备执行预检脚本都在 180 秒超时，但手动检查显示：

```text
adb get-state => device
getprop sys.boot_completed => 1
pm list packages => works
cmd package resolve-activity --brief com.smslink => com.smslink/.MainActivity
pidof -s com.smslink => has pid
dumpsys window => mCurrentFocus=com.smslink/com.smslink.MainActivity
```

影响:

预检脚本超时会浪费时间，并可能把健康设备误判为平台阻塞。

根因:

脚本中的某个 `dumpsys window` / `pm list packages` / `am start -W` 组合在特定模拟机状态下卡住或执行时间超过预算。设备本身并非不可用。

本轮解决办法:

脚本超时后转入手动健康门：

```powershell
adb -s emulator-5554 get-state
adb -s emulator-5554 shell getprop sys.boot_completed
adb -s emulator-5554 shell pm list packages
adb -s emulator-5554 shell cmd package resolve-activity --brief com.smslink
adb -s emulator-5554 shell pidof -s com.smslink
adb -s emulator-5554 shell dumpsys window | Select-String -Pattern 'mFocusedApp|mCurrentFocus|ANR'
```

建议升级:

- 预检脚本超时后不要立即终止测试。
- 加入 fallback preflight：
  - 每条 adb 命令独立超时。
  - 分项记录成功/失败。
  - 只要 boot、pm、resolve、launch、focus 均通过，即允许继续产品测试。
- 报告中应区分 `preflight_script_timeout` 和 `device_unhealthy`。

### 问题 #3: Android Emulator NAT 导致双机 LAN 文件传输不能直接连

现象:

发送端使用 `10.0.2.2:1716` 连接时，若没有桥接，会出现：

```text
ECONNREFUSED
Unable to establish connection
```

影响:

文件传输需求是首阶段核心项。如果不处理 emulator NAT，会把环境限制误判为产品网络失败。

根因:

Android Emulator 的 `10.0.2.2` 指向 host，不是另一台 emulator。两台 emulator 不能像两台真机一样处于真实同一 WiFi LAN。

本轮解决办法:

使用 QA-only host TCP proxy + `adb forward`：

```powershell
adb -s emulator-5556 forward tcp:2716 tcp:1716
```

host proxy 监听 `0.0.0.0:1716`，转发到 `127.0.0.1:2716`，让 sender 的 `10.0.2.2:1716` 桥接到 receiver 的 `tcp:1716`。

验证通过日志：

```text
sender_connected=True
sender_sent=True
sender_progress_complete=True
receiver_tls_listener=True
receiver_accepted=True
receiver_file_request=True
receiver_receiving=True
receiver_received=True
```

建议升级:

- 测试 agent 应内置“emulator 双机 TCP 桥接模式”。
- 触发条件：
  - 目标是 2-emulator 文件传输。
  - app 使用 TCP LAN 端口。
  - 设备不是物理机。
- 报告必须明确：
  - 这是 QA 环境代理。
  - 不写入产品代码。
  - 不能替代真机 WiFi LAN 验收。

### 问题 #4: TLS 使用 AndroidKeyStore 私钥反复失败

现象 1: RSA 私钥路径失败。

```text
javax.net.ssl.SSLHandshakeException
CryptoUpcalls: Preferred provider doesn't support key
RSA routines:OPENSSL_internal:internal error
```

现象 2: 切换 EC 私钥后仍失败。

```text
CryptoUpcalls: Could not find provider for algorithm: NONEwithECDSA
SSLHandshakeException
```

影响:

这是本轮最耗时的问题。表面像 cipher suite、证书、信任管理、socket proxy、服务端监听等多个方向的问题，容易反复试错。

根因:

AndroidKeyStore 私钥不可导出，Conscrypt 在 TLS 握手中需要 provider 支持特定签名/解密算法。模拟机环境中 RSA/EC AndroidKeyStore 私钥都触发了 provider 限制。

失败过的方案:

1. 只恢复 `TcpConnectionImpl` TLS。
2. 仅限制客户端 cipher suites。
3. 同时限制客户端和服务端 cipher suites。
4. AndroidKeyStore RSA 自签名证书。
5. AndroidKeyStore EC/P-256 自签名证书。
6. 把 AndroidKeyStore 私钥复制到默认内存 KeyStore。该方案会触发 BouncyCastle/BKS 私钥序列化异常。

最终解决办法:

使用 app 私有 PKCS12 keystore 保存软件 RSA 私钥和自签名 X.509 证书，用 BouncyCastle 生成证书：

```kotlin
KeyStore.getInstance("PKCS12")
KeyPairGenerator.getInstance("RSA")
JcaX509v3CertificateBuilder(...)
JcaContentSignerBuilder("SHA256withRSA")
```

TLS 仍使用 Android 标准：

```kotlin
SSLContext.getInstance("TLS")
KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
TrustManager
SSLSocket
SSLServerSocket
```

建议升级:

- 测试 agent 看到 `CryptoUpcalls` 时应快速归类为 `TLS private key provider incompatibility`。
- 不要继续在 AndroidKeyStore RSA/EC 路径上反复试。
- 推荐修复路径：
  - app 私有 PKCS12 keystore。
  - 软件 RSA 私钥。
  - BouncyCastle 仅用于证书生成。
  - TLS runtime 使用系统 `SSLContext`。
- 自动检测关键日志：
  - `CryptoUpcalls`
  - `NONEwithECDSA`
  - `RSA routines`
  - `SSLHandshakeException`

### 问题 #5: BouncyCastle 依赖引入后 Android 资源合并失败

现象:

```text
Execution failed for task ':app:mergeDebugJavaResource'
3 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'
```

影响:

证书修复后编译被 Java resource merge 阻塞。

根因:

`bcpkix-jdk18on` 会带入 `bcprov` 和 `bcutil`，这些 JAR 存在重复 `META-INF/versions/9` 资源。

本轮解决办法:

在 `app/build.gradle.kts` 中加入：

```kotlin
packaging {
    resources {
        excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        excludes += "META-INF/versions/9/module-info.class"
    }
}
```

建议升级:

- 当引入 BouncyCastle 后出现 `mergeDebugJavaResource`，agent 应自动检查重复 `META-INF`。
- 优先添加 packaging excludes，而不是回退证书实现。

### 问题 #6: 传输成功后 EOF / Connection reset 被误记为 error

现象:

文件已经发送和接收成功，但随后日志出现：

```text
Error receiving data from device
java.net.SocketException: Connection reset

Inbound TCP receive failed for peer
java.io.IOException: End of stream
```

影响:

自动化日志分析容易误判为传输失败。

根因:

对端完成传输后关闭 socket 是正常行为。接收循环把正常 EOF 当作异常错误记录。

本轮解决办法:

对 `SocketException` 和 `IOException("End of stream")` 做 expected close 分类，降级为 info：

```text
Peer closed connection for device
Inbound TCP peer closed connection
```

建议升级:

- 日志分析规则应区分：
  - 成功完成后的 EOF / connection reset: 正常关闭。
  - 未完成传输前的 EOF / reset: 传输失败。
- 判断顺序应优先看业务完成信号：
  - `File sent successfully`
  - `File received successfully`
  - `Transfer progress: 1.0`

### 问题 #7: Windows 终端 GBK 编码导致 Python UI dump 脚本崩溃

现象:

```text
UnicodeDecodeError: 'gbk' codec can't decode byte
UnicodeEncodeError: 'gbk' codec can't encode character '\u2022'
```

影响:

UI dump 已成功，但 Python 读取或打印时崩溃，导致测试流程中断。

根因:

Windows PowerShell 默认编码与 `uiautomator dump` 输出中的 UTF-8 / 特殊字符不兼容。

本轮解决办法:

Python subprocess 强制：

```python
subprocess.run(..., text=True, encoding="utf-8", errors="replace")
```

写文件强制：

```python
path.write_text(xml, encoding="utf-8", errors="replace")
```

避免直接向 GBK 控制台打印大量中文/特殊符号。

建议升级:

- 测试 agent 在 Windows 上执行 Python/PowerShell UI 自动化时，默认使用 UTF-8 + replace。
- 大型 XML 只写文件，不直接打印到终端。
- 控制台只输出简短 ASCII summary。

### 问题 #8: UI 坐标在手机和平板布局之间不一致

现象:

同样的坐标在平板布局可点击文件 FAB，在手机布局可能点到错误位置。短信 `New Message` 也出现首次坐标不准，实际页面停留在通知页。

影响:

容易误判为按钮无效或页面不可达。

根因:

模拟机分辨率、底部导航 slot 数量、Compose 布局在 phone/tablet 上不同。固定坐标不可靠。

本轮解决办法:

1. 先 dump UI XML。
2. 从 XML 中读取 `bounds`。
3. 根据当前页面实际坐标点击。
4. 失败后保存 XML，人工/脚本校正坐标。

建议升级:

- 优先通过 UI XML 的 `text` / `content-desc` / `bounds` 定位点击中心。
- 固定坐标只能作为 fallback。
- 每次关键点击后都 dump 当前 UI，确认页面是否真的变化。

### 问题 #9: `dumpsys package` 服务检查误判

现象:

脚本检查 `dumpsys package com.smslink` 时没有命中 `SmsSyncService`，但 Manifest 中实际已声明。

影响:

可能误判为服务缺失。

根因:

`dumpsys package` 的 resolver table 主要展示带 intent-filter 的服务。`SmsSyncService` 是显式启动的非导出服务，没有 intent-filter 时不一定出现在同一段输出中。

本轮解决办法:

直接检查 `AndroidManifest.xml`：

```xml
<service
    android:name=".sms.SmsSyncService"
    android:enabled="true"
    android:exported="false" />
```

建议升级:

- 服务声明检查应结合：
  - 源码 Manifest。
  - merged manifest。
  - dumpsys package。
- 对 `exported=false` 且无 intent-filter 的服务，不要只靠 resolver table 判断。

### 问题 #10: 直接用 `adb shell am start-foreground-service` 启动非导出服务会失败

现象:

```text
Permission Denial: Accessing service com.smslink/.sms.SmsSyncService from uid=2000 that is not exported
Error: Requires permission not exported
```

影响:

容易误判为后台服务不可用。

根因:

服务 `android:exported="false"` 是正确安全配置，shell UID 不能直接启动。应通过 app 内 UI 或 app 进程调用启动。

本轮解决办法:

通过通知页 `Start Listening` 触发 app 内逻辑，日志显示：

```text
Notification listener started
NotificationListenerService created
```

建议升级:

- 对 `exported=false` 服务，agent 不应使用 shell 直接启动作为验收标准。
- 应通过 UI 入口、BroadcastReceiver、Activity 或 app 内逻辑触发。
- 如果必须用 shell，只能验证“非导出服务拒绝外部启动”，这属于安全通过项。

## 3. 推荐加入测试 Agent 的自动化流程

### 3.1 环境恢复流程

```text
if adb devices is empty:
    list AVDs
    start preferred 2 AVDs
    wait for device
    wait sys.boot_completed=1
    continue
else:
    run preflight
```

### 3.2 预检 fallback

```text
run prepare_android_emulator.ps1
if timeout:
    run manual health gates
    if boot + pm + resolve + launch + focus pass:
        classify preflight script timeout
        continue product QA
    else:
        classify platform blocker
```

### 3.3 双机文件传输测试流程

```text
if both targets are emulator:
    start receiver app
    adb forward receiver tcp:2716 tcp:1716
    start host proxy 0.0.0.0:1716 -> 127.0.0.1:2716
    start sender app
    use UI picker to select sample file
    assert TLS + transfer logs
else:
    use real LAN IP, no host proxy
```

### 3.4 TLS 失败分类规则

```text
if log contains CryptoUpcalls or NONEwithECDSA or RSA routines:
    classify TLS private key provider incompatibility
    recommend app-private PKCS12 software key
elif log contains Certificate mismatch:
    classify trust/pinning mismatch
elif log contains ECONNREFUSED:
    classify listener/proxy/network path
```

### 3.5 UI 自动化规则

```text
dump UI XML before click
find node by text/content-desc
click center of bounds
dump UI XML after click
assert expected page text
only fallback to hardcoded coordinates if node cannot be found
```

## 4. 建议测试报告固定输出

每轮测试报告应固定包含：

- 构建结果。
- 设备列表和健康门结果。
- 测试矩阵。
- 每项测试结果。
- 产品缺陷。
- 平台阻塞。
- 降级项。
- 修复项。
- 重新编译结果。
- 证据文件路径。
- 真机剩余验证项。

## 5. 本轮最终状态

第一阶段 emulator-testable 项已通过。

仍需真机验证：

- 2 台物理 Android 设备真实 WiFi LAN 文件传输，不使用 host proxy。
- 真实 SIM 短信收发。
- 默认电话应用角色下的真实通话控制。
- 蓝牙 RFCOMM 与热点 fallback。
