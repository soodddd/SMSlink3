# SMS-link UI 自动化测试指南

## 文档目的
本文档为 AI Agent 通过 ADB 进行自动化测试提供详细的 UI 元素定位和操作说明。

## 应用包名
- **Package Name**: `com.smslink`
- **Main Activity**: `com.smslink.MainActivity`

## 启动应用
```bash
adb shell am start -n com.smslink/.MainActivity
```

---

## 主界面导航结构

应用采用底部导航栏 (BottomNavigationBar) 设计，包含 4 个主要页面：

### 1. 设备管理页 (DeviceListScreen)
- **导航标签**: "设备" / "Devices"
- **访问方式**: 点击底部导航栏第一个图标

### 2. 互联页 (ConnectScreen)
- **导航标签**: "互联" / "Connect"
- **访问方式**: 点击底部导航栏第二个图标

### 3. 通知页 (NotificationListScreen)
- **导航标签**: "通知" / "Notifications"
- **访问方式**: 点击底部导航栏第三个图标

### 4. 设置页 (SettingsScreen)
- **导航标签**: "设置" / "Settings"
- **访问方式**: 点击底部导航栏第四个图标

---

## 详细页面测试指南

## 一、设备管理页 (DeviceListScreen)

### 页面标识
- **TopAppBar Title**: "设备管理"

### 关键按钮

#### 1. 二维码配对按钮
- **位置**: TopAppBar 右侧第一个按钮
- **文本**: "二维码"
- **图标**: QrCode
- **功能**: 打开二维码配对对话框
- **点击后**: 显示 QRCodeDialog，包含本机配对码和二维码占位符

#### 2. 搜索/停止搜索按钮
- **位置**: TopAppBar 右侧第二个按钮
- **图标**: Search (搜索状态) / Stop (发现状态)
- **功能**: 开始/停止 WiFi 设备发现
- **点击后**: 
  - 搜索状态 → 发现状态：开始扫描同网络设备
  - 发现状态 → 搜索状态：停止扫描

### 设备列表区域

#### 本机设备卡片 (DeviceCard)
- **标题**: "本机设备"
- **显示内容**: 
  - 设备名称
  - 连接状态: "已连接"
  - 角色: "主设备" / "副设备" / "蜂窝源设备"
- **操作按钮**:
  - 角色菜单按钮 (MoreVert 图标)
    - 下拉菜单选项:
      - "设为主设备"
      - "设为副设备"
      - "设为蜂窝源设备"

#### 已配对设备卡片 (DeviceCard)
- **标题**: "已配对设备 (N)"
- **每个设备显示**:
  - 设备图标 (PhoneAndroid/Tablet)
  - 设备名称
  - 连接状态: "已连接" / "未连接"
  - 角色标签
- **操作按钮**:
  - 角色菜单按钮 (MoreVert)
  - 删除按钮 (Delete 图标)

#### 发现的设备卡片 (DiscoveredDeviceCard)
- **标题**: "发现的设备 (N)" (仅在搜索状态显示)
- **每个设备显示**:
  - 设备图标
  - 设备名称
  - IP 地址
- **操作按钮**:
  - "配对" 按钮
  - **点击后**: 打开 PairingDialog

#### 配对请求卡片 (PairRequestCard)
- **标题**: "配对请求"
- **显示内容**: "{设备名} 请求配对"
- **操作按钮**:
  - "拒绝" 按钮
  - "接受" 按钮

### 对话框

#### QRCodeDialog (二维码配对对话框)
- **标题**: "配对二维码"
- **显示内容**:
  - 设备名称
  - 设备 ID
  - 二维码占位符 (200dp 高度)
  - 配对码文本 (JSON 格式)
  - 提示文字: "在另一台设备上扫描此二维码或复制配对码进行配对"
- **按钮**:
  - 关闭按钮 (右上角 Close 图标)
  - "关闭" 文本按钮

#### PairingDialog (配对输入对话框)
- **标题**: "配对设备"
- **显示内容**:
  - "正在配对: {设备名}"
  - 提示文字
  - 配对码输入框 (OutlinedTextField, 多行)
- **按钮**:
  - "配对" 按钮 (需要输入非空配对码才启用)
  - "取消" 按钮

### 测试流程示例

```bash
# 1. 进入设备管理页
adb shell input tap <底部导航栏设备图标坐标>

# 2. 点击搜索按钮开始发现设备
adb shell input tap <TopAppBar 搜索按钮坐标>

# 3. 等待发现设备 (3-5秒)
sleep 5

# 4. 点击第一个发现的设备的"配对"按钮
adb shell input tap <配对按钮坐标>

# 5. 在配对对话框中输入配对码
adb shell input text "配对码JSON字符串"

# 6. 点击"配对"按钮
adb shell input tap <配对按钮坐标>
```

---

## 二、互联页 (ConnectScreen)

### 页面标识
- **TopAppBar Title**: "互联"

### Tab 导航
互联页包含 3 个子页面，通过 TabRow 切换：

#### Tab 1: 短信 (SmsListScreen)
- **Tab 文本**: "短信"
- **索引**: 0

#### Tab 2: 文件 (FileTransferScreen)
- **Tab 文本**: "文件"
- **索引**: 1

#### Tab 3: 通话 (CallHistoryScreen)
- **Tab 文本**: "通话"
- **索引**: 2

---

### 2.1 短信页面 (SmsListScreen)

#### TopAppBar 按钮

##### 1. 设备选择按钮
- **位置**: TopAppBar 右侧
- **图标**: Smartphone
- **显示条件**: 有已连接设备时显示
- **功能**: 打开设备选择对话框
- **点击后**: 显示 DeviceSelectorDialog

##### 2. 视图模式切换按钮
- **位置**: TopAppBar 右侧
- **图标**: ViewList (会话模式) / Forum (列表模式)
- **文本**: "列表" / "会话"
- **功能**: 切换消息显示模式

##### 3. 刷新按钮
- **位置**: TopAppBar 右侧
- **图标**: Refresh
- **功能**: 从系统同步短信

##### 4. 新建消息按钮 (FloatingActionButton)
- **位置**: 右下角
- **图标**: Add
- **功能**: 打开新建消息页面

#### 消息列表 (MessageList)
- **每条消息显示**:
  - 发件人/收件人号码
  - 消息内容 (最多 2 行)
  - 时间戳
  - 消息类型: "Received" / "Sent" / "Draft" / "Sending" / "Failed"
  - 未读消息背景色不同
- **点击消息**: 标记为已读并打开详情

#### 设备选择对话框 (DeviceSelectorDialog)
- **标题**: "Select Device"
- **选项**:
  - "This device" (本机)
  - 已连接设备列表 (显示设备名和类型)
- **按钮**: "Close"

#### 状态显示
- **Loading**: 中央显示 CircularProgressIndicator
- **Empty**: "No messages" + "Your messages will appear here"
- **PermissionRequired**: "Permission Required" + 权限说明
- **Error**: 错误消息 + "Retry" 按钮

---

### 2.2 文件传输页面 (FileTransferScreen)

#### TopAppBar 按钮

##### 刷新按钮
- **位置**: TopAppBar 右侧
- **图标**: Refresh
- **文本**: "刷新"
- **功能**: 重新加载传输历史

##### 发送文件按钮 (FloatingActionButton)
- **位置**: 右下角
- **图标**: Add
- **功能**: 打开文件选择器
- **点击后**: 启动系统文件选择器

#### 活动传输区域 (Active Transfers)
- **标题**: "Active Transfers"
- **显示条件**: 有正在进行的传输时显示
- **每个传输项显示**:
  - 文件名
  - 文件大小
  - 进度条 (LinearProgressIndicator)
  - 进度百分比
  - 操作按钮:
    - 取消按钮 (Close 图标)
    - 重试按钮 (Refresh 图标，仅失败时显示)

#### 传输历史区域 (Transfer History)
- **标题**: "Transfer History"
- **每条记录显示**:
  - 方向图标: Upload / Download
  - 文件名
  - 文件大小 + 时间戳
  - 状态标签: "Pending" / "Transferring" / "Completed" / "Failed" / "Cancelled"
- **点击记录**: 打开传输详情对话框

#### 文件接收确认对话框 (FileReceiveConfirmDialog)
- **触发条件**: 收到文件传输请求时自动弹出
- **显示内容**:
  - 发送设备名称
  - 文件名
  - 文件大小
- **按钮**:
  - "接受" 按钮
  - "拒绝" 按钮

#### 传输详情对话框 (TransferDetailsDialog)
- **标题**: "Transfer Details"
- **显示内容**:
  - File Name
  - File Size
  - Type (MIME)
  - Direction
  - State
  - Progress
  - Device ID
  - Time
- **按钮**: "Close"

#### 重要提示
⚠️ **文件传输目标设备解析逻辑**:
```kotlin
resolvedTargetDeviceId = 
    connectedDevices.firstOrNull()?.id?.takeIf { it.isNotBlank() }
    ?: pairedDevices.firstOrNull()?.id?.takeIf { it.isNotBlank() }
    ?: deviceId.takeIf { it.isNotBlank() }
    ?: ""
```
- 优先使用第一个已连接设备
- 其次使用第一个已配对设备
- 最后使用传入的 deviceId
- 如果都为空，则为空字符串（会导致传输失败）

---

### 2.3 通话记录页面 (CallHistoryScreen)

#### TopAppBar 按钮

##### 1. 设备选择按钮
- **位置**: TopAppBar 右侧
- **图标**: Devices
- **显示条件**: 有已连接设备时显示
- **功能**: 打开设备选择对话框

##### 2. 默认电话应用警告按钮
- **位置**: TopAppBar 右侧
- **图标**: Warning (红色)
- **显示条件**: Android M+ 且未设置为默认电话应用
- **功能**: 打开默认电话应用引导对话框

##### 3. 刷新按钮
- **位置**: TopAppBar 右侧
- **图标**: Refresh
- **功能**: 同步通话记录

#### 通话记录列表 (CallHistoryList)
- **每条记录显示**:
  - 通话类型图标:
    - CallReceived (来电，蓝色)
    - CallMade (去电，紫色)
    - CallMissed (未接，红色)
  - 联系人名称 / 电话号码
  - 时间戳
  - 通话时长 (如果 > 0)
  - 删除按钮 (Delete 图标)
- **点击记录**: 拨打该号码
- **点击删除**: 显示删除确认对话框

#### 当前通话卡片 (CurrentCallCard)
- **显示条件**: 有正在进行的通话时显示
- **位置**: 底部中央
- **显示内容**:
  - 通话状态: "来电中..." / "通话中" / "通话保持中"
  - 联系人名称 / 电话号码
  - 远程控制图标 (如果选择了设备)

##### 本地控制按钮 (需要默认电话应用权限)
- **来电状态**:
  - "接听" 按钮 (蓝色，Call 图标)
  - "挂断" 按钮 (红色，CallEnd 图标)
- **通话中状态**:
  - "挂断" 按钮
  - 静音/取消静音按钮 (Mic/MicOff 图标)
  - 保持/恢复按钮 (Pause/PlayArrow 图标)

##### 远程控制按钮 (需要选择设备)
- **展开/收起按钮**: "显示远程控制" / "隐藏远程控制"
- **远程操作**:
  - "远程接听" 按钮 (来电时)
  - "远程挂断" 按钮

#### 对话框

##### DefaultPhoneAppDialog
- **标题**: "设置默认电话应用"
- **内容**: 权限说明文字
- **按钮**:
  - "设置" 按钮
  - "取消" 按钮

##### DeviceSelectorDialog
- **标题**: "选择设备"
- **选项**:
  - "本机设备"
  - 已连接设备列表
- **按钮**: "关闭"

##### 删除确认对话框
- **标题**: "删除通话记录"
- **内容**: "确定要删除这条通话记录吗？"
- **按钮**:
  - "删除" 按钮
  - "取消" 按钮

#### 状态显示
- **Loading**: 中央显示 CircularProgressIndicator
- **Empty**: "暂无通话记录" + "点击右上角刷新按钮同步通话记录"
- **Syncing**: 顶部显示 LinearProgressIndicator
- **Error**: 底部显示 Snackbar + "关闭" 按钮

---

## 三、通知页 (NotificationListScreen)

### 页面标识
- **TopAppBar Title**: "通知同步"

### TopAppBar 按钮

#### 1. 设置按钮
- **位置**: TopAppBar 右侧
- **图标**: Settings
- **功能**: 打开通知设置页面

#### 2. 刷新按钮
- **位置**: TopAppBar 右侧
- **图标**: Refresh
- **功能**: 刷新通知列表

### 通知列表
- **每条通知显示**:
  - 应用图标
  - 应用名称
  - 通知标题
  - 通知内容 (最多 2 行)
  - 时间戳
- **点击通知**: 打开通知详情

### 状态显示
- **Loading**: 中央显示 CircularProgressIndicator
- **Empty**: "暂无通知"
- **PermissionRequired**: 权限引导界面

---

## 四、设置页 (SettingsScreen)

### 页面标识
- **TopAppBar Title**: "设置"

### 设置分组

#### 1. 权限与安全
- **权限中心**
  - 图标: Security
  - 标题: "权限中心"
  - 副标题: "管理应用权限"
  - 点击: 跳转到权限引导页面

#### 2. 连接
- **连接策略**
  - 图标: Link
  - 标题: "连接策略"
  - 副标题: "配置设备连接方式"
  - 点击: TODO (未实现)

#### 3. 同步
- **通知同步策略**
  - 图标: Sync
  - 标题: "通知同步策略"
  - 副标题: "配置通知同步规则"
  - 点击: 跳转到通知设置页面

#### 4. 显示
- **主题**
  - 图标: DarkMode
  - 标题: "主题"
  - 副标题: "深色模式" / "浅色模式"
  - 点击: 打开主题选择对话框
  
- **语言**
  - 图标: Language
  - 标题: "语言"
  - 副标题: "中文" / "English"
  - 点击: 打开语言选择对话框

#### 5. 高级
- **诊断日志**
  - 图标: BugReport
  - 标题: "诊断日志"
  - 副标题: "查看应用日志"
  - 点击: 跳转到诊断页面

#### 6. 关于
- **关于 SMS-link**
  - 图标: Info
  - 标题: "关于 SMS-link"
  - 副标题: "版本 1.0.0"
  - 点击: TODO (未实现)

### 对话框

#### 主题选择对话框
- **标题**: "选择主题"
- **选项**:
  - "浅色模式" (RadioButton)
  - "深色模式" (RadioButton)
- **按钮**: "取消"

#### 语言选择对话框
- **标题**: "选择语言"
- **选项**:
  - "中文" (RadioButton)
  - "English" (RadioButton)
- **按钮**: "取消"

---

## 测试注意事项

### 1. 权限要求
应用需要以下权限才能正常运行：
- `READ_SMS` - 读取短信
- `SEND_SMS` - 发送短信
- `READ_CALL_LOG` - 读取通话记录
- `CALL_PHONE` - 拨打电话
- `READ_CONTACTS` - 读取联系人
- `ACCESS_NOTIFICATION_POLICY` - 通知访问
- `BLUETOOTH` / `BLUETOOTH_ADMIN` - 蓝牙
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` - WiFi
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` - 存储
- `ROLE_CALL_SCREENING` (Android 10+) - 通话筛选
- 默认电话应用角色 (Android M+) - 通话控制

### 2. 设备配对前置条件
- 两台设备必须连接到同一 WiFi 网络
- 两台设备都需要启动 WiFi 设备发现
- 配对码有效期: 6 位配对码 5 分钟，二维码 10 分钟

### 3. 文件传输前置条件
- 必须有已配对或已连接的设备
- 如果没有目标设备，文件传输会失败
- 文件大小限制: 最大 10MB (短信附件)

### 4. 通话功能前置条件
- Android M+ 需要设置为默认电话应用才能控制通话
- 远程控制需要选择目标设备

### 5. UI 状态检测
使用以下命令检测 UI 元素：
```bash
# 获取当前界面布局
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml

# 查找特定文本
adb shell uiautomator dump | grep "设备管理"

# 点击特定坐标
adb shell input tap X Y

# 输入文本
adb shell input text "文本内容"

# 滑动操作
adb shell input swipe X1 Y1 X2 Y2 duration
```

### 6. 常见问题排查

#### 问题 1: 点击按钮无响应
- 检查是否有对话框遮挡
- 检查按钮是否被禁用 (enabled = false)
- 检查是否需要权限

#### 问题 2: 设备发现失败
- 确认两台设备在同一 WiFi 网络
- 检查 WiFi 权限
- 检查防火墙设置

#### 问题 3: 文件传输失败
- 确认有目标设备 (resolvedTargetDeviceId 非空)
- 检查存储权限
- 检查文件大小

#### 问题 4: 通话控制失败
- 确认已设置为默认电话应用
- 检查通话权限
- 确认 Android 版本 >= P (API 28)

---

## ADB 自动化测试脚本示例

### 完整配对流程测试
```bash
#!/bin/bash

# 1. 启动应用
adb shell am start -n com.smslink/.MainActivity
sleep 2

# 2. 进入设备管理页 (点击底部导航第一个图标)
adb shell input tap 180 2200

# 3. 点击搜索按钮
adb shell input tap 980 150

# 4. 等待发现设备
sleep 5

# 5. 点击第一个发现设备的配对按钮
adb shell input tap 900 800

# 6. 输入配对码 (需要从另一台设备获取)
adb shell input tap 540 1000  # 点击输入框
adb shell input text "配对码JSON"

# 7. 点击配对按钮
adb shell input tap 800 1400

# 8. 等待配对完成
sleep 3

# 9. 验证配对成功 (检查已配对设备列表)
adb shell uiautomator dump | grep "已配对设备"
```

### 文件传输测试
```bash
#!/bin/bash

# 1. 进入互联页
adb shell input tap 360 2200

# 2. 切换到文件 Tab
adb shell input tap 540 300

# 3. 点击发送文件按钮
adb shell input tap 980 2000

# 4. 选择文件 (需要根据文件选择器 UI 调整)
# ...

# 5. 等待传输完成
sleep 10

# 6. 检查传输状态
adb shell uiautomator dump | grep "Completed"
```

---

## 附录：坐标参考 (基于 1080x2400 分辨率)

### 底部导航栏
- 设备: (180, 2200)
- 互联: (360, 2200)
- 通知: (720, 2200)
- 设置: (900, 2200)

### TopAppBar 按钮 (右侧)
- 第一个按钮: (850, 150)
- 第二个按钮: (980, 150)

### FloatingActionButton
- 位置: (980, 2000)

**注意**: 实际坐标需要根据设备分辨率和 UI 布局动态计算。建议使用 `uiautomator dump` 获取精确坐标。

---

## 版本信息
- **文档版本**: 1.0
- **应用版本**: 1.0.0
- **最后更新**: 2026-04-XX
- **适用平台**: Android 8.0+ (API 26+)
