---
name: android-auto-qa-loop
description: 全自动真机测试修复循环 - 自动配对、测试所有核心功能、测试降级场景、自动修复代码缺陷
---

# Android 全自动测试修复循环

**单一入口 Skill**：真正的全自动测试修复循环，包括设备配对、通知同步、短信流转、文件传输、通话转接、连接降级测试，以及自动代码修复。

## 使用方式

```
Use $android-auto-qa-loop to run full automated testing and fixing loop on real Android devices
```

## 核心功能

1. ✅ **自动设备配对** - 通过Intent自动完成两台设备的BLE配对
2. ✅ **通知同步测试** - 发送测试通知，验证镜像功能
3. ✅ **短信流转测试** - 模拟短信接收和代发功能
4. ✅ **文件传输测试** - 测试不同大小文件的传输
5. ✅ **通话转接测试** - 测试通话控制命令
6. ✅ **连接降级测试** - 测试WiFi→蓝牙降级和自动重连
7. ✅ **智能日志分析** - 从logcat自动提取错误信息
8. ✅ **自动代码修复** - 根据错误类型应用修复补丁
9. ✅ **循环验证** - 修复后重新测试，直到通过

## 执行流程

### Phase 1: 初始化（~30秒）

1. **检测真机设备**
   ```bash
   adb devices -l
   ```
   - 要求：至少2台设备，状态为 `device`
   - 检查：boot_completed=1, package manager可用

2. **创建证据目录**
   ```bash
   mkdir -p artifacts/auto-$(date +%Y%m%d-%H%M%S)
   ```

3. **授予必要权限**
   ```bash
   # 蓝牙权限
   adb -s <serial> shell pm grant com.smslink android.permission.BLUETOOTH_SCAN
   adb -s <serial> shell pm grant com.smslink android.permission.BLUETOOTH_CONNECT
   adb -s <serial> shell pm grant com.smslink android.permission.BLUETOOTH_ADVERTISE
   
   # 位置权限
   adb -s <serial> shell pm grant com.smslink android.permission.ACCESS_FINE_LOCATION
   adb -s <serial> shell pm grant com.smslink android.permission.ACCESS_COARSE_LOCATION
   
   # 短信权限
   adb -s <serial> shell pm grant com.smslink android.permission.READ_SMS
   adb -s <serial> shell pm grant com.smslink android.permission.SEND_SMS
   adb -s <serial> shell pm grant com.smslink android.permission.RECEIVE_SMS
   
   # 通知监听（需要用户手动授予）
   echo "请在设备上手动授予通知监听权限"
   ```

### Phase 2: 构建与安装（~60秒）

1. **清理并构建**
   ```bash
   ./gradlew clean assembleDebug
   ```

2. **安装到所有设备**
   ```bash
   adb -s <deviceA> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <deviceB> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **启动应用**
   ```bash
   adb -s <deviceA> shell am start -S -n com.smslink/.MainActivity
   adb -s <deviceB> shell am start -S -n com.smslink/.MainActivity
   ```

4. **验证启动**
   ```bash
   adb -s <deviceA> shell pidof -s com.smslink
   adb -s <deviceB> shell pidof -s com.smslink
   ```

### Phase 3: 设备配对（~30秒）

**目标**：自动完成两台设备的配对

**步骤**：

1. **清空logcat**
   ```bash
   adb -s <deviceA> logcat -c
   adb -s <deviceB> logcat -c
   ```

2. **在设备A上触发设备发现**
   ```bash
   # 通过UI自动化点击搜索按钮
   adb -s <deviceA> shell input tap 900 200
   ```

3. **在设备B上触发设备发现**
   ```bash
   adb -s <deviceB> shell input tap 900 200
   ```

4. **等待设备发现（10秒）**
   ```bash
   sleep 10
   ```

5. **从设备A的logcat中提取配对码**
   ```bash
   # 搜索包含配对码的日志
   adb -s <deviceA> logcat -d | grep -E "QR.*code|pair.*code" | tail -1
   
   # 提取JSON格式的配对码
   # 格式：{"deviceId":"xxx","pairKey":"xxx","timestamp":123456}
   ```

6. **通过Intent将配对码传递给设备B**
   ```bash
   # 方式1：直接传递JSON
   adb -s <deviceB> shell am start -n com.smslink/.MainActivity \
     --es debug_pair_code '{"deviceId":"xxx","pairKey":"xxx","timestamp":123456}'
   
   # 方式2：Base64编码传递
   PAIR_CODE_B64=$(echo -n '{"deviceId":"xxx","pairKey":"xxx","timestamp":123456}' | base64)
   adb -s <deviceB> shell am start -n com.smslink/.MainActivity \
     --es debug_pair_code_b64 "$PAIR_CODE_B64"
   ```

7. **等待配对完成（10秒）**
   ```bash
   sleep 10
   ```

8. **验证配对状态**
   ```bash
   # 检查设备A的日志
   adb -s <deviceA> logcat -d | grep -i "pair.*success"
   
   # 检查设备B的日志
   adb -s <deviceB> logcat -d | grep -i "pair.*success"
   
   # 检查数据库中是否保存了配对信息
   adb -s <deviceA> shell "run-as com.smslink sqlite3 /data/data/com.smslink/databases/smslink.db 'SELECT * FROM devices WHERE isPaired=1'"
   ```

**失败处理**：
- 如果配对失败，检查logcat中的错误信息
- 常见问题：
  - "Bluetooth not enabled" → 开启蓝牙
  - "Permission denied" → 授予蓝牙权限
  - "Device not found" → 增加等待时间，重试发现
  - "Invalid pair key" → 重新生成配对码

### Phase 4: 通知同步测试（~20秒）

**目标**：测试通知镜像功能

**前提条件**：
- 两台设备已配对并连接
- 已授予通知监听权限

**步骤**：

1. **清空logcat**
   ```bash
   adb -s <deviceA> logcat -c
   adb -s <deviceB> logcat -c
   ```

2. **在设备A上发送测试通知**
   ```bash
   adb -s <deviceA> shell cmd notification post \
     -t "SMS-link测试通知" \
     "这是一条测试通知内容，用于验证通知同步功能" \
     test_notification_tag
   ```

3. **等待同步（3秒）**
   ```bash
   sleep 3
   ```

4. **检查设备B是否收到通知**
   ```bash
   # 方式1：检查系统通知
   adb -s <deviceB> shell dumpsys notification | grep "SMS-link测试通知"
   
   # 方式2：检查logcat
   adb -s <deviceB> logcat -d | grep -i "notification.*receive"
   ```

5. **验证通知内容**
   ```bash
   # 检查标题、内容、时间戳是否正确
   adb -s <deviceB> logcat -d | grep "SMS-link测试通知"
   ```

**失败分析**：
- 如果设备B未收到通知，检查：
  ```bash
  # 检查NotificationListenerService是否启动
  adb -s <deviceA> logcat -d | grep "NotificationListener.*onListenerConnected"
  
  # 检查网络连接
  adb -s <deviceA> logcat -d | grep "Connection.*state"
  
  # 检查同步日志
  adb -s <deviceA> logcat -d | grep "NotificationManager.*sync"
  ```

- 从logcat中提取错误：
  ```bash
  # NullPointerException
  adb -s <deviceA> logcat -d | grep "NullPointerException"
  
  # NetworkException
  adb -s <deviceA> logcat -d | grep "NetworkException\|SocketException\|TimeoutException"
  ```

**自动修复示例**：
- 如果发现 `device.id is null`，修复代码：
  ```kotlin
  // 原代码
  val deviceId = device.id
  
  // 修复后
  val deviceId = device?.id ?: return
  ```

- 如果发现 `connection timeout`，修复代码：
  ```kotlin
  // 原代码
  val timeout = 5000L
  
  // 修复后
  val timeout = 10000L
  ```

### Phase 5: 短信流转测试（~30秒）

**目标**：测试短信同步和代发功能

**前提条件**：
- 两台设备已配对并连接
- 已授予短信权限
- 设备A有SIM卡（作为蜂窝源设备）

**步骤**：

1. **清空logcat**
   ```bash
   adb -s <deviceA> logcat -c
   adb -s <deviceB> logcat -c
   ```

2. **在设备A上模拟接收短信**
   ```bash
   # 注意：这个命令可能在某些设备上不工作
   # 替代方案：使用另一台手机发送真实短信
   adb -s <deviceA> shell service call isms 5 \
     s16 "10086" \
     s16 "SMS-link测试短信内容"
   ```

3. **等待同步（3秒）**
   ```bash
   sleep 3
   ```

4. **检查设备B是否收到短信同步**
   ```bash
   # 检查短信数据库
   adb -s <deviceB> shell content query \
     --uri content://sms/inbox \
     --projection address,body,date \
     | grep "SMS-link测试"
   
   # 检查logcat
   adb -s <deviceB> logcat -d | grep -i "sms.*receive"
   ```

5. **测试代发短信**
   ```bash
   # 在设备B上触发代发请求
   adb -s <deviceB> shell am broadcast \
     -a com.smslink.SEND_SMS \
     --es address "10086" \
     --es body "SMS-link代发测试"
   ```

6. **检查设备A是否发送了短信**
   ```bash
   adb -s <deviceA> logcat -d | grep "SmsManager.*send"
   ```

**失败分析**：
- 短信未同步 → 检查SmsReceiver是否注册
  ```bash
  adb -s <deviceA> shell dumpsys package com.smslink | grep "SmsReceiver"
  ```

- 代发失败 → 检查设备角色和权限
  ```bash
  adb -s <deviceA> logcat -d | grep "role.*CELLULAR_SOURCE"
  ```

**自动修复示例**：
- 如果发现 `SmsReceiver not registered`，检查AndroidManifest.xml
- 如果发现 `role is not CELLULAR_SOURCE`，自动设置角色

### Phase 6: 文件传输测试（~60秒）

**目标**：测试文件传输和降级功能

**前提条件**：
- 两台设备已配对并连接
- 已授予存储权限

**步骤**：

1. **创建测试文件**
   ```bash
   # 小文件（<1MB）
   adb -s <deviceA> shell "echo 'SMS-link文件传输测试内容' > /sdcard/test_small.txt"
   
   # 大文件（>10MB）
   adb -s <deviceA> shell "dd if=/dev/urandom of=/sdcard/test_large.bin bs=1M count=20"
   ```

2. **测试小文件传输**
   ```bash
   # 通过分享面板发送
   adb -s <deviceA> shell am start \
     -a android.intent.action.SEND \
     -t "text/plain" \
     --eu android.intent.extra.STREAM file:///sdcard/test_small.txt \
     -n com.smslink/.file.ui.ShareActivity
   ```

3. **等待传输完成（10秒）**
   ```bash
   sleep 10
   ```

4. **检查设备B是否收到文件**
   ```bash
   adb -s <deviceB> shell ls /sdcard/Download/ | grep test_small.txt
   ```

5. **测试大文件传输**
   ```bash
   adb -s <deviceA> shell am start \
     -a android.intent.action.SEND \
     -t "application/octet-stream" \
     --eu android.intent.extra.STREAM file:///sdcard/test_large.bin \
     -n com.smslink/.file.ui.ShareActivity
   ```

6. **监控传输进度**
   ```bash
   # 检查logcat中的传输进度
   adb -s <deviceA> logcat -d | grep "FileTransfer.*progress"
   ```

7. **测试降级场景**
   ```bash
   # 传输过程中断开WiFi
   adb -s <deviceA> shell svc wifi disable
   
   # 等待5秒，检查是否降级到蓝牙
   sleep 5
   adb -s <deviceA> logcat -d | grep "LinkSelector.*Switching.*BLUETOOTH"
   
   # 恢复WiFi
   adb -s <deviceA> shell svc wifi enable
   ```

**失败分析**：
- 文件未收到 → 检查接收确认流程
  ```bash
  adb -s <deviceB> logcat -d | grep "FileTransfer.*accept"
  ```

- 传输中断 → 检查重试逻辑
  ```bash
  adb -s <deviceA> logcat -d | grep "FileTransfer.*retry"
  ```

- 降级失败 → 检查链路选择逻辑
  ```bash
  adb -s <deviceA> logcat -d | grep "LinkSelector.*selectBestLink"
  ```

### Phase 7: 通话转接测试（~30秒）

**目标**：测试通话控制功能

**前提条件**：
- 两台设备已配对并连接
- 已授予电话权限
- 应用设置为默认电话应用（可选）

**步骤**：

1. **清空logcat**
   ```bash
   adb -s <deviceA> logcat -c
   adb -s <deviceB> logcat -c
   ```

2. **在设备A上模拟来电**
   ```bash
   # 注意：这个命令会真的拨打电话
   adb -s <deviceA> shell am start \
     -a android.intent.action.CALL \
     -d tel:10086
   ```

3. **等待来电通知（3秒）**
   ```bash
   sleep 3
   ```

4. **检查设备B是否收到来电通知**
   ```bash
   adb -s <deviceB> logcat -d | grep "CallManager.*incoming"
   ```

5. **在设备B上发送接听命令**
   ```bash
   # 通过UI自动化点击"接听"按钮
   # 或通过代码触发
   adb -s <deviceB> shell am broadcast \
     -a com.smslink.ANSWER_CALL
   ```

6. **检查设备A是否接听了电话**
   ```bash
   adb -s <deviceA> logcat -d | grep "InCallService.*answer"
   ```

7. **测试挂断命令**
   ```bash
   adb -s <deviceB> shell am broadcast \
     -a com.smslink.END_CALL
   ```

**失败分析**：
- 来电未同步 → 检查InCallService是否启动
  ```bash
  adb -s <deviceA> shell dumpsys telecom | grep "InCallService"
  ```

- 控制命令无效 → 检查权限和角色
  ```bash
  adb -s <deviceA> logcat -d | grep "permission.*ANSWER_PHONE_CALLS"
  ```

### Phase 8: 连接降级测试（~60秒）

**目标**：测试网络降级和自动重连

**测试场景**：

#### 场景1：WiFi → Bluetooth 降级

1. **记录当前连接类型**
   ```bash
   adb -s <deviceA> logcat -d | grep "LinkSelector.*Selected" | tail -1
   ```

2. **断开WiFi**
   ```bash
   adb -s <deviceA> shell svc wifi disable
   ```

3. **等待5秒，检查新的连接类型**
   ```bash
   sleep 5
   adb -s <deviceA> logcat -d | grep "LinkSelector.*Switching" | tail -1
   ```

4. **发送测试消息，验证功能正常**
   ```bash
   adb -s <deviceA> shell cmd notification post \
     -t "降级测试" "测试蓝牙连接下的通知同步" test_tag
   
   sleep 3
   adb -s <deviceB> shell dumpsys notification | grep "降级测试"
   ```

5. **恢复WiFi**
   ```bash
   adb -s <deviceA> shell svc wifi enable
   ```

6. **检查是否升级回WiFi**
   ```bash
   sleep 10
   adb -s <deviceA> logcat -d | grep "LinkSelector.*Switching.*WIFI" | tail -1
   ```

#### 场景2：自动重连测试

1. **断开所有连接**
   ```bash
   adb -s <deviceA> shell svc wifi disable
   adb -s <deviceA> shell svc bluetooth disable
   ```

2. **等待10秒**
   ```bash
   sleep 10
   ```

3. **恢复连接**
   ```bash
   adb -s <deviceA> shell svc wifi enable
   adb -s <deviceA> shell svc bluetooth enable
   ```

4. **检查自动重连**
   ```bash
   sleep 15
   adb -s <deviceA> logcat -d | grep "ConnectionManager.*reconnect.*success"
   ```

**失败分析**：
- 降级未触发 → 检查NetworkMonitor
  ```bash
  adb -s <deviceA> logcat -d | grep "NetworkMonitor.*Lost"
  ```

- 功能中断 → 检查消息队列和重试逻辑
  ```bash
  adb -s <deviceA> logcat -d | grep "MessageTransport.*retry"
  ```

- 重连失败 → 检查重连策略
  ```bash
   adb -s <deviceA> logcat -d | grep "ConnectionManager.*reconnect.*attempt"
  ```

### Phase 9: 日志分析和自动修复

**目标**：从logcat中自动提取错误并修复

**分析模式**：

1. **崩溃分析**
   ```bash
   # 搜索FATAL EXCEPTION
   adb -s <serial> logcat -d | grep -A 50 "FATAL EXCEPTION"
   
   # 提取堆栈跟踪
   # 示例输出：
   # FATAL EXCEPTION: main
   # Process: com.smslink, PID: 12345
   # java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.String com.smslink.Device.getId()' on a null object reference
   #     at com.smslink.notification.NotificationManagerImpl.syncNotification(NotificationManagerImpl.kt:123)
   ```

2. **ANR分析**
   ```bash
   # 搜索ANR
   adb -s <serial> logcat -d | grep -A 30 "ANR in"
   
   # 提取ANR原因
   # 示例输出：
   # ANR in com.smslink (com.smslink/.MainActivity)
   # Reason: Input dispatching timed out (Waiting to send non-key event because the touched window has not finished processing certain input events that were delivered to it over 500.0ms ago.)
   ```

3. **异常分析**
   ```bash
   # 搜索Exception和Error
   adb -s <serial> logcat -d | grep -E "Exception|Error" | grep "com.smslink"
   ```

**修复策略**：

1. **NullPointerException修复**
   - 定位：从堆栈跟踪中提取文件名和行号
   - 读取：读取相关源文件
   - 分析：找到空指针位置
   - 修复：添加空指针检查
   
   ```kotlin
   // 原代码（NotificationManagerImpl.kt:123）
   val deviceId = device.getId()
   
   // 修复后
   val deviceId = device?.getId() ?: run {
       logger.w(TAG, "Device is null, skipping notification sync")
       return
   }
   ```

2. **主线程阻塞（ANR）修复**
   - 定位：从ANR日志中找到阻塞的方法
   - 读取：读取相关源文件
   - 分析：识别阻塞操作（网络请求、数据库查询等）
   - 修复：将阻塞操作移到后台线程
   
   ```kotlin
   // 原代码
   fun syncNotification(notification: Notification) {
       val devices = deviceRepository.getConnectedDevices() // 阻塞操作
       // ... 同步逻辑
   }
   
   // 修复后
   fun syncNotification(notification: Notification) {
       viewModelScope.launch(Dispatchers.IO) {
           val devices = deviceRepository.getConnectedDevices()
           // ... 同步逻辑
       }
   }
   ```

3. **连接超时修复**
   - 定位：从异常日志中找到超时位置
   - 读取：读取相关源文件
   - 分析：找到超时配置
   - 修复：增加超时时间或添加重试逻辑
   
   ```kotlin
   // 原代码
   private const val CONNECTION_TIMEOUT = 5000L
   
   // 修复后
   private const val CONNECTION_TIMEOUT = 10000L
   ```

**修复流程**：

1. **读取相关源文件**
   ```
   Read <file_path>
   ```

2. **定位问题代码**
   - 根据行号定位
   - 根据方法名定位

3. **应用修复补丁**
   ```
   Edit <file_path>
   old_string: <原代码>
   new_string: <修复后的代码>
   ```

4. **重新编译**
   ```bash
   ./gradlew assembleDebug
   ```

5. **重新安装**
   ```bash
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

6. **重新测试**
   - 重新执行失败的场景
   - 检查是否通过

### Phase 10: 循环终止条件

**终止条件**（满足任一即停止）：

1. ✅ **所有测试场景通过**
   - 设备配对成功
   - 通知同步正常
   - 短信流转正常
   - 文件传输正常
   - 通话转接正常
   - 连接降级正常

2. ✅ **达到最大循环次数（10次）**
   - 防止无限循环

3. ✅ **连续2轮无进展**
   - 通过场景数量连续2轮没有增加
   - 避免重复无效修复

4. ✅ **遇到无法自动修复的问题**
   - 标记为需要人工介入
   - 生成详细的问题报告

### Phase 11: 生成最终报告

生成测试报告，包含：

```json
{
  "roundId": "auto-20260419-120000",
  "startTime": "2026-04-19T12:00:00",
  "endTime": "2026-04-19T12:45:00",
  "duration": "45 minutes",
  "devices": {
    "deviceA": "400E7800HQ00000",
    "deviceB": "MZEAYX6LE67T995D"
  },
  "scenarios": {
    "total": 7,
    "passed": 6,
    "failed": 1,
    "blocked": 0
  },
  "results": [
    {
      "scenario": "设备配对",
      "status": "PASSED",
      "duration": "30s"
    },
    {
      "scenario": "通知同步",
      "status": "PASSED",
      "duration": "20s",
      "fixes": [
        {
          "file": "NotificationManagerImpl.kt",
          "line": 123,
          "issue": "NullPointerException",
          "fix": "Added null check"
        }
      ]
    },
    {
      "scenario": "短信流转",
      "status": "PASSED",
      "duration": "30s"
    },
    {
      "scenario": "文件传输",
      "status": "PASSED",
      "duration": "60s"
    },
    {
      "scenario": "通话转接",
      "status": "FAILED",
      "duration": "30s",
      "reason": "Permission denied: ANSWER_PHONE_CALLS",
      "classification": "permission_block"
    },
    {
      "scenario": "连接降级",
      "status": "PASSED",
      "duration": "60s"
    }
  ],
  "patches": [
    {
      "file": "NotificationManagerImpl.kt",
      "description": "Added null check for device object",
      "linesChanged": 3
    }
  ],
  "loops": 2,
  "evidencePath": "artifacts/auto-20260419-120000/"
}
```

## 预期结果

### 配对阶段
- 自动完成两台设备的配对
- 配对成功率 > 90%

### 功能测试
- 通知同步：100%通过
- 短信流转：100%通过
- 文件传输：100%通过
- 通话转接：100%通过（如果有权限）

### 降级测试
- WiFi → 蓝牙降级：100%通过
- 自动重连：100%通过
- 功能保持：100%通过

### 自动修复
- 常见问题自动修复率 > 70%
- 修复后重测通过率 > 80%

## 限制与注意事项

1. **权限限制**
   - 通知监听权限需要用户手动授予
   - 默认电话应用需要用户手动设置

2. **设备限制**
   - 需要2台真机
   - 需要至少一台有SIM卡

3. **网络限制**
   - WiFi热点可能需要手动开启
   - 某些厂商ROM可能限制热点功能

4. **时间限制**
   - 完整测试循环可能需要30-60分钟
   - 包括编译、安装、测试、修复

## 相关文档

- 计划文档：`C:\Users\forek\.claude\plans\peppy-percolating-grove.md`
- 技术需求书：`SMS-link_技术需求书_v3.1.md`
