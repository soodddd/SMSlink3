# SMS-Link 测试指南

## 📱 测试设备信息

### 设备 1 (主测试设备)
- **设备 ID**: `400E7800HQ00000`
- **分辨率**: 2064x3096 像素
- **连接方式**: USB (emulator-5554 或直接连接)
- **角色**: 可配置为 PRIMARY 或 SECONDARY

### 设备 2 (副测试设备)
- **设备 ID**: `MZEAYX6LE67T995D`
- **分辨率**: 1080x2412 像素
- **连接方式**: WiFi ADB (192.168.31.x:5555)
- **角色**: 可配置为 PRIMARY 或 SECONDARY

## 🎯 关键 UI 元素坐标

### 设备 1 (2064x3096)
- **FAB 按钮 (添加设备)**: 
  - Bounds: [2956,1724][3016,1784]
  - 中心点: (2986, 1754)
  - 用途: 从首页进入配对流程

- **底部导航栏**:
  - 首页 Tab: 约 (258, 2900)
  - 传输 Tab: 约 (774, 2900)
  - 通知 Tab: 约 (1290, 2900)
  - 设置 Tab: 约 (1806, 2900)

### 设备 2 (1080x2412)
- **FAB 按钮 (添加设备)**:
  - Bounds: [912,2004][984,2076]
  - 中心点: (948, 2040)
  - 用途: 从首页进入配对流程

- **底部导航栏**:
  - 首页 Tab: 约 (135, 2280)
  - 传输 Tab: 约 (405, 2280)
  - 通知 Tab: 约 (675, 2280)
  - 设置 Tab: 约 (945, 2280)

## 🛠️ ADB 命令参考

### 设备连接和检测

```bash
# 列出所有连接的设备
adb devices

# 连接 WiFi 设备
adb connect 192.168.31.x:5555

# 断开 WiFi 设备
adb disconnect 192.168.31.x:5555

# 获取设备分辨率
adb -s <device_id> shell wm size

# 获取设备详细信息
adb -s <device_id> shell getprop ro.product.model
```

### 应用安装和启动

```bash
# 安装 APK
adb -s <device_id> install -r app/build/outputs/apk/debug/app-debug.apk

# 启动应用
adb -s <device_id> shell am start -n com.smslink/.MainActivity

# 强制停止应用
adb -s <device_id> shell am force-stop com.smslink

# 清除应用数据
adb -s <device_id> shell pm clear com.smslink
```

### UI 交互

```bash
# 点击坐标
adb -s <device_id> shell input tap <x> <y>

# 输入文本
adb -s <device_id> shell input text "123456"

# 按返回键
adb -s <device_id> shell input keyevent 4

# 按 Home 键
adb -s <device_id> shell input keyevent 3

# 隐藏软键盘
adb -s <device_id> shell input keyevent 111
```

### UI 检查

```bash
# 导出 UI 层级结构
adb -s <device_id> shell uiautomator dump /sdcard/window_dump.xml

# 拉取 UI 层级文件
adb -s <device_id> pull /sdcard/window_dump.xml

# 查找特定元素 (示例: 查找按钮)
grep -o 'text="[^"]*"' window_dump.xml

# 查找可点击元素的坐标
grep 'clickable="true"' window_dump.xml | grep -o 'bounds="[^"]*"'
```

### 日志抓取

```bash
# 实时查看应用日志
adb -s <device_id> logcat -s SmsLinkApp:* DeviceManager:* NotificationSync:*

# 清除日志缓冲区
adb -s <device_id> logcat -c

# 保存日志到文件
adb -s <device_id> logcat > device_log.txt

# 过滤特定标签
adb -s <device_id> logcat | grep "SmsLinkApp"
```

### 截图和录屏

```bash
# 截图
adb -s <device_id> shell screencap -p /sdcard/screenshot.png
adb -s <device_id> pull /sdcard/screenshot.png

# 录屏 (最长 180 秒)
adb -s <device_id> shell screenrecord /sdcard/recording.mp4
# Ctrl+C 停止录制
adb -s <device_id> pull /sdcard/recording.mp4
```

## 🧪 测试流程

### 1. 环境准备

```bash
# 1. 检查设备连接
adb devices

# 2. 编译最新 APK
cd C:\Users\forek\Desktop\ccwork\SMS
./gradlew assembleDebug

# 3. 安装到两台设备
adb -s 400E7800HQ00000 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s MZEAYX6LE67T995D install -r app/build/outputs/apk/debug/app-debug.apk

# 4. 清除旧数据 (可选)
adb -s 400E7800HQ00000 shell pm clear com.smslink
adb -s MZEAYX6LE67T995D shell pm clear com.smslink
```

### 2. 首次启动测试

```bash
# 启动设备 1
adb -s 400E7800HQ00000 shell am start -n com.smslink/.MainActivity

# 启动设备 2
adb -s MZEAYX6LE67T995D shell am start -n com.smslink/.MainActivity

# 检查引导流程是否显示
adb -s 400E7800HQ00000 shell uiautomator dump /sdcard/window_dump.xml
adb -s 400E7800HQ00000 pull /sdcard/window_dump.xml device1_onboarding.xml

# 查看日志确认初始化
adb -s 400E7800HQ00000 logcat -s SmsLinkApp:* | grep "initialized"
```

### 3. 设备配对测试 (当前测试重点)

#### 步骤 1: 设备 2 生成配对码

```bash
# 1. 点击 FAB 按钮进入配对页面
adb -s MZEAYX6LE67T995D shell input tap 948 2040

# 2. 等待 1 秒
sleep 1

# 3. 检查是否进入配对页面
adb -s MZEAYX6LE67T995D shell uiautomator dump /sdcard/window_dump.xml
adb -s MZEAYX6LE67T995D pull /sdcard/window_dump.xml device2_pairing.xml
grep "生成配对码\|输入配对码" device2_pairing.xml

# 4. 点击"生成配对码"按钮 (需要根据实际 UI 确定坐标)
# 示例: adb -s MZEAYX6LE67T995D shell input tap <x> <y>

# 5. 截图保存配对码
adb -s MZEAYX6LE67T995D shell screencap -p /sdcard/pairing_code.png
adb -s MZEAYX6LE67T995D pull /sdcard/pairing_code.png

# 6. 从日志中获取配对码
adb -s MZEAYX6LE67T995D logcat -d | grep "Pairing code generated"
```

#### 步骤 2: 设备 1 输入配对码

```bash
# 1. 点击 FAB 按钮进入配对页面
adb -s 400E7800HQ00000 shell input tap 2986 1754

# 2. 等待 1 秒
sleep 1

# 3. 点击"输入配对码"按钮
# 需要先导出 UI 找到按钮坐标
adb -s 400E7800HQ00000 shell uiautomator dump /sdcard/window_dump.xml
adb -s 400E7800HQ00000 pull /sdcard/window_dump.xml device1_pairing.xml

# 4. 点击输入框
# 示例: adb -s 400E7800HQ00000 shell input tap <x> <y>

# 5. 输入配对码 (从设备 2 获取的 6 位数字)
adb -s 400E7800HQ00000 shell input text "123456"

# 6. 隐藏键盘
adb -s 400E7800HQ00000 shell input keyevent 111

# 7. 点击确认按钮
# 示例: adb -s 400E7800HQ00000 shell input tap <x> <y>
```

#### 步骤 3: 验证配对结果

```bash
# 1. 检查设备 1 日志
adb -s 400E7800HQ00000 logcat -d | grep -E "enterCode|startDiscovery|Pairing"

# 2. 检查设备 2 日志
adb -s MZEAYX6LE67T995D logcat -d | grep -E "Device discovered|Pairing|Connection"

# 3. 检查设备 1 UI 状态
adb -s 400E7800HQ00000 shell uiautomator dump /sdcard/window_dump.xml
adb -s 400E7800HQ00000 pull /sdcard/window_dump.xml device1_after_pairing.xml

# 4. 检查设备 2 UI 状态
adb -s MZEAYX6LE67T995D shell uiautomator dump /sdcard/window_dump.xml
adb -s MZEAYX6LE67T995D pull /sdcard/window_dump.xml device2_after_pairing.xml

# 5. 返回首页查看配对设备列表
adb -s 400E7800HQ00000 shell input keyevent 4
adb -s MZEAYX6LE67T995D shell input keyevent 4
```

### 4. 通知同步测试 (待配对成功后)

```bash
# 1. 确认通知权限已授予
adb -s 400E7800HQ00000 shell dumpsys notification_listener

# 2. 在设备 1 上触发测试通知
adb -s 400E7800HQ00000 shell cmd notification post -S bigtext -t "Test" "Tag" "Test notification"

# 3. 检查设备 1 日志
adb -s 400E7800HQ00000 logcat -s NotificationSync:*

# 4. 检查设备 2 是否收到通知
adb -s MZEAYX6LE67T995D logcat -s NotificationSync:*

# 5. 在设备 2 上查看通知历史
# 点击底部导航栏的"通知" Tab
adb -s MZEAYX6LE67T995D shell input tap 675 2280
```

### 5. 设置持久化测试

```bash
# 1. 进入设置页面
adb -s 400E7800HQ00000 shell input tap 1806 2900

# 2. 切换深色模式
# 需要根据实际 UI 确定开关坐标

# 3. 重启应用
adb -s 400E7800HQ00000 shell am force-stop com.smslink
adb -s 400E7800HQ00000 shell am start -n com.smslink/.MainActivity

# 4. 验证设置是否保存
adb -s 400E7800HQ00000 logcat -d | grep "isDarkTheme"
```

## 🐛 常见问题排查

### 问题 1: 点击无响应

**可能原因**:
- 坐标不准确
- 元素被遮挡 (如软键盘)
- UI 还在加载中

**排查方法**:
```bash
# 1. 导出 UI 层级确认元素位置
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml

# 2. 检查元素是否 clickable="true"
grep 'clickable="true"' window_dump.xml

# 3. 截图确认当前界面
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png
```

### 问题 2: 应用崩溃

**排查方法**:
```bash
# 1. 查看崩溃日志
adb logcat -s AndroidRuntime:E

# 2. 查看应用日志
adb logcat -s SmsLinkApp:*

# 3. 清除数据重试
adb shell pm clear com.smslink
```

### 问题 3: 设备无法发现

**排查方法**:
```bash
# 1. 确认两台设备在同一网络
adb -s 400E7800HQ00000 shell ip addr show wlan0
adb -s MZEAYX6LE67T995D shell ip addr show wlan0

# 2. 检查 mDNS 服务是否注册
adb logcat | grep "mDNS\|NsdService"

# 3. 检查防火墙设置
# (需要在设备上手动检查)
```

## 📊 测试状态

### ✅ 已完成
- [x] 编译环境搭建
- [x] 应用安装和启动
- [x] UI 稳定性验证
- [x] FAB 按钮布局修复
- [x] 配对页面导航

### 🚧 进行中
- [ ] 设备配对流程完整测试
  - [x] 配对码生成
  - [x] 配对码输入 UI
  - [ ] 设备发现验证
  - [ ] 配对成功状态验证
- [ ] 设备发现机制验证

### ⏳ 待测试
- [ ] 通知同步功能
- [ ] 设置持久化
- [ ] 首次启动引导流程
- [ ] 深色模式切换
- [ ] 错误处理和提示

## 🔍 当前测试重点

**配对流程验证** (优先级: 高)

当前阻塞点:
- 配对码输入后点击确认按钮，需要验证:
  1. `PairingViewModel.enterCode()` 是否被调用
  2. `DeviceBridge.enterPairingCode()` 是否执行成功
  3. `DeviceBridge.startDiscovery()` 是否启动设备发现
  4. 两台设备能否通过 mDNS 相互发现
  5. 配对成功后 UI 状态是否正确更新

下一步操作:
1. 在设备 1 上输入配对码并点击确认
2. 同时监控两台设备的日志输出
3. 检查设备发现相关日志
4. 验证配对成功后的设备列表更新

## 📝 测试记录模板

```markdown
### 测试日期: YYYY-MM-DD
### 测试人员: [姓名]
### 测试版本: [APK 版本]

#### 测试环境
- 设备 1: [设备 ID] - [分辨率]
- 设备 2: [设备 ID] - [分辨率]
- 网络: [WiFi SSID]

#### 测试步骤
1. [步骤描述]
2. [步骤描述]

#### 预期结果
- [预期行为]

#### 实际结果
- [实际行为]

#### 问题记录
- [问题描述]
- [日志片段]
- [截图路径]

#### 结论
- [ ] 通过
- [ ] 失败
- [ ] 阻塞
```

---

**文档版本**: 1.0  
**最后更新**: 2026-04-10  
**维护者**: AI Agent
