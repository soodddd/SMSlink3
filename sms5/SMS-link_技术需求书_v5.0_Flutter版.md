# SMS-link 多设备协同系统技术需求书
**版本：v5.0 (Flutter版 / 对等网络 / 简化架构)**

---

## 一、文档定位与目标

本文档是 SMS-link 多设备协同系统的完整技术规格说明，用于指导开发、测试和验收。

**核心目标**：
- 实现 Android 手机、Android 平板、Windows PC 之间的设备协同
- 支持通知镜像、短信协同、文件传输、剪贴板同步四大核心功能
- 采用对等网络架构，无需中心服务器
- 使用 Flutter 跨平台框架，最大化代码复用

**非目标**：
- 不支持跨公网连接（仅局域网）
- 不提供云端账号体系
- 不支持 iOS/macOS/Linux（当前版本）
- 不支持视频通话
- 不支持通话控制（仅通知来电状态）

---

## 二、产品定义

SMS-link 是一套运行于多设备之间的个人协同系统，让手机、平板、电脑之间完成消息、文件、状态和内容的安全流转与同步。

**四大核心能力**：

1. **通知镜像**：Android 设备的通知实时同步到其他设备
2. **短信协同**：手机短信在其他设备查看和代发
3. **文件传输**：设备间快速传输文件
4. **剪贴板同步**：剪贴板内容在设备间自动同步

---

## 三、设备模型与能力矩阵

### 3.1 设备类型

系统支持三种设备类型：
- **Android 手机**：有蜂窝网络和 SIM 卡
- **Android 平板**：无蜂窝网络
- **Windows PC**：桌面电脑

### 3.2 设备能力矩阵

| 能力 | Android手机 | Android平板 | Windows PC |
|------|------------|-------------|------------|
| 通知源 | ✅ | ✅ | ❌ |
| 通知接收 | ✅ | ✅ | ✅ |
| 短信源 | ✅ | ❌ | ❌ |
| 短信查看/代发 | ✅ | ✅ | ✅ |
| 来电通知源 | ✅ | ❌ | ❌ |
| 来电通知接收 | ✅ | ✅ | ✅ |
| 文件发送 | ✅ | ✅ | ✅ |
| 文件接收 | ✅ | ✅ | ✅ |
| 剪贴板发送 | ✅ | ✅ | ✅ |
| 剪贴板接收 | ✅ | ✅ | ✅ |
| 剪贴板自动监听 | ⚠️ (前台/尝试后台) | ⚠️ (前台/尝试后台) | ✅ |

### 3.3 对等网络架构

**核心原则**：
- 所有设备地位平等，无主副之分
- 设备间直接建立 1 对 1 连接
- 每个连接独立维护状态
- 无单点故障

**连接关系示例**：
```
手机A ←→ 手机B
手机A ←→ 平板C
手机A ←→ 电脑D
手机B ←→ 平板C
平板C ←→ 电脑D
```

---

## 四、技术架构

### 4.1 技术栈选择

**核心框架**：Flutter 3.x
- 单一代码库，支持 Android + Windows
- Dart 语言，开发效率高
- 代码共享率 90%+

**关键依赖包**：
```yaml
dependencies:
  # 状态管理
  riverpod: ^2.5.0
  
  # 网络
  multicast_dns: ^0.3.0      # mDNS 设备发现
  
  # 数据库
  sqflite: ^2.3.0            # Android
  sqlite3: ^2.4.0            # Windows
  
  # 二维码
  qr_flutter: ^4.1.0         # 生成二维码
  mobile_scanner: ^5.0.0     # 扫描二维码
  
  # 加密
  cryptography: ^2.7.0       # 密钥交换
  
  # 平台适配
  flutter_local_notifications: ^17.0.0
  system_tray: ^2.0.0
  clipboard_watcher: ^0.2.0
  permission_handler: ^11.0.0
  
  # 文件
  path_provider: ^2.1.0
  file_picker: ^8.0.0
```

### 4.2 架构分层

```
┌─────────────────────────────────────┐
│         UI 层 (Flutter)              │
│  Android: Material Design            │
│  Windows: Fluent Design              │
└─────────────────────────────────────┘
              ↓ ↑
┌─────────────────────────────────────┐
│       业务逻辑层 (Dart)               │
│  - 设备管理                          │
│  - 连接管理                          │
│  - 消息路由                          │
│  - 文件传输管理                      │
│  - 状态管理 (Riverpod)               │
└─────────────────────────────────────┘
              ↓ ↑
┌─────────────────────────────────────┐
│      平台适配层 (Method Channel)      │
│  Android: Kotlin                     │
│  Windows: C++                        │
└─────────────────────────────────────┘
              ↓ ↑
┌─────────────────────────────────────┐
│       网络层 (Dart)                  │
│  - mDNS 发现                         │
│  - TCP Socket                        │
│  - JSON 协议                         │
└─────────────────────────────────────┘
```

### 4.3 项目结构

```
smslink/
├── lib/
│   ├── main.dart
│   ├── core/                      # 核心业务逻辑
│   │   ├── device/                # 设备管理
│   │   ├── connection/            # 连接管理
│   │   ├── message/               # 消息路由
│   │   └── file_transfer/         # 文件传输
│   ├── features/                  # 功能模块
│   │   ├── notification/          # 通知镜像
│   │   ├── sms/                   # 短信协同
│   │   ├── clipboard/             # 剪贴板同步
│   │   └── pairing/               # 配对流程
│   ├── platform/                  # 平台适配
│   │   ├── android/
│   │   └── windows/
│   ├── network/                   # 网络层
│   │   ├── discovery.dart         # mDNS
│   │   ├── tcp_server.dart
│   │   ├── tcp_client.dart
│   │   └── protocol.dart
│   └── ui/                        # UI 层
│       ├── screens/
│       ├── widgets/
│       └── theme/
├── android/                       # Android 原生代码
│   └── app/src/main/kotlin/
│       └── com/smslink/
│           ├── NotificationListener.kt
│           ├── SmsReceiver.kt
│           └── ClipboardMonitor.kt
└── windows/                       # Windows 原生代码
    └── runner/
        ├── clipboard_plugin.cpp
        └── system_tray_plugin.cpp
```

---

## 五、设备发现与配对

### 5.1 设备发现 (mDNS)

**发现机制**：
- 使用 mDNS (Multicast DNS) 在局域网内广播和发现设备
- 服务类型：`_smslink._tcp`
- 默认端口：`37521`

**广播信息**：
```json
{
  "id": "device-uuid",
  "name": "我的手机",
  "type": "ANDROID_PHONE",
  "version": "1.0.0",
  "ip": "192.168.1.100",
  "port": 37521
}
```

### 5.2 配对流程

**配对方式**：
1. **扫描二维码**（主推）
2. **输入配对码**（备选）

**配对步骤**：
```
发起方                          接收方
  │                               │
  ├─ 1. 扫描局域网设备             ├─ 1. 广播自己
  │                               │
  ├─ 2. 选择目标设备               ├─ 2. 显示二维码 + 6位配对码
  │                               │     (有效期5分钟)
  │                               │
  ├─ 3. 扫码或输入配对码           │
  │                               │
  ├─ 4. 发送配对请求               ├─ 4. 弹窗确认
  │    (包含公钥)                  │
  │                               │
  │                               ├─ 5. 用户确认
  │                               │
  ├─ 6. 收到确认                   ├─ 6. 发送确认 + 公钥
  │    ECDH 密钥交换               │
  │                               │
  ├─ 7. 能力协商                   ├─ 7. 能力协商
  │                               │
  └─ 8. 配对完成                   └─ 8. 配对完成
       保存可信设备                     保存可信设备
```

**安全机制**：
- 使用 ECDH (X25519) 密钥交换
- 配对码 6 位数字，5 分钟有效期
- 接收方必须手动确认配对请求
- 配对成功后保存共享密钥

### 5.3 能力协商

配对完成后，设备交换能力信息：

```json
{
  "type": "CAPABILITY_ANNOUNCE",
  "deviceInfo": {
    "deviceId": "device-uuid",
    "deviceName": "我的手机",
    "deviceType": "ANDROID_PHONE",
    "capabilities": [
      "NOTIFICATION_SOURCE",
      "SMS_SOURCE",
      "CALL_SOURCE",
      "FILE_SOURCE",
      "FILE_RECEIVER",
      "CLIPBOARD_SOURCE",
      "CLIPBOARD_RECEIVER"
    ],
    "hasSim": true,
    "osVersion": "Android 14",
    "appVersion": "1.0.0",
    "protocolVersion": 1
  }
}
```

---

## 六、通知镜像模块

### 6.1 功能定义

将 Android 设备的通知实时同步到其他已连接设备。

**支持场景**：
- 手机 → 平板
- 手机 → 电脑
- 手机 → 手机
- 平板 → 手机
- 平板 → 平板
- 平板 → 电脑

### 6.2 实现要点

**Android 端**：
- 使用 `NotificationListenerService` 监听通知
- 提取通知信息：应用名、包名、标题、正文、时间、图标、大图、操作按钮
- 实时发送到已连接设备

**接收端**：
- 显示通知，标注来源设备（"手机·设备名"、"平板·设备名"）
- 图标缓存机制（首次传输后缓存，后续仅传包名）
- 点击通知进入应用内详情页

**配置选项**：
- 黑名单：排除不想镜像的应用
- 去重策略：用户可选择是否镜像目标设备已安装的应用
- 敏感应用：内置黑名单（银行、支付类），用户可选择镜像
- 声音震动：用户可配置是否播放通知声音
- 交互能力：用户可配置是否支持通知交互

**数据管理**：
- 保存最近 100 条通知历史
- 不同步清除（源设备清除通知，不影响目标设备）

### 6.3 协议格式

```json
{
  "type": "NOTIFICATION",
  "messageId": "msg-uuid",
  "sourceDeviceId": "device-uuid",
  "sourceDeviceName": "我的手机",
  "sourceDeviceType": "ANDROID_PHONE",
  "timestamp": 1713504000000,
  "payload": {
    "packageName": "com.tencent.mm",
    "appName": "微信",
    "title": "张三",
    "content": "在吗？",
    "postTime": 1713504000000,
    "priority": "HIGH",
    "iconData": "base64...",  // 首次传输
    "largeIcon": "base64...", // 可选
    "actions": [              // 可选
      {"title": "回复", "actionId": "reply"}
    ]
  }
}
```

---

## 七、短信协同模块

### 7.1 功能定义

手机短信在其他设备查看和代发。

**支持场景**：
- 手机 → 平板（查看 + 代发）
- 手机 → 电脑（查看 + 代发）
- 手机 → 手机（查看 + 代发）

**限制**：
- 仅支持纯文本短信（不支持 MMS）
- 仅支持单发（不支持群发）

### 7.2 实现要点

**手机端（短信源）**：
- 使用 `SmsReceiver` 监听新收到的短信
- 实时同步到已连接设备
- 处理代发请求（配对后完全信任，直接发送）
- 双卡支持：用户选择用哪张卡发送

**副设备端**：
- 按联系人分组显示会话列表
- 会话内按时间排序
- 同步联系人姓名（从手机端获取）
- 支持全文搜索
- 自动识别验证码短信，高优先级通知

**状态同步**：
- 已读状态同步（副设备读了，手机也标记已读）
- 发送状态反馈（发送中、已发送、已送达、失败原因）

**数据管理**：
- 仅监听新收到的短信（不同步历史）
- 删除仅影响副设备镜像，不影响手机

### 7.3 协议格式

**短信同步**：
```json
{
  "type": "SMS_RECEIVED",
  "messageId": "msg-uuid",
  "sourceDeviceId": "phone-uuid",
  "timestamp": 1713504000000,
  "payload": {
    "address": "13812341234",
    "contactName": "张三",
    "body": "您的验证码是123456",
    "timestamp": 1713504000000,
    "isVerificationCode": true,
    "simSlot": 1
  }
}
```

**短信代发请求**：
```json
{
  "type": "SMS_SEND_REQUEST",
  "messageId": "msg-uuid",
  "senderDeviceId": "tablet-uuid",
  "receiverDeviceId": "phone-uuid",
  "timestamp": 1713504000000,
  "payload": {
    "recipient": "13812341234",
    "message": "好的，明天见",
    "simSlot": 1  // 可选，用户选择
  }
}
```

**短信发送状态**：
```json
{
  "type": "SMS_SEND_STATUS",
  "messageId": "msg-uuid",
  "status": "SENT",  // SENDING, SENT, DELIVERED, FAILED
  "timestamp": 1713504005000,
  "errorMessage": null
}
```

---

## 八、文件传输模块

### 8.1 功能定义

设备间快速传输文件。

**支持场景**：
- 任意设备 ↔ 任意设备（双向对等）

### 8.2 实现要点

**传输协议**：
- TCP Socket + 自定义协议
- 固定分块：1MB/块
- 不支持断点续传（局域网速度快，重传成本低）

**传输流程**：
1. 发送方选择文件
2. 发送文件元数据（文件名、大小、类型）
3. 接收方弹窗确认
4. 接收方确认后，开始传输
5. 分块传输，实时显示进度
6. 传输完成，双方记录日志

**批量传输**：
- 逐个文件传输（不打包）
- 每个文件独立确认和进度

**限制**：
- 单文件最大 4GB
- 不限制文件类型
- 不加密（局域网内认为安全）

**数据管理**：
- 保存最近 100 条传输历史

### 8.3 协议格式

**文件传输请求**：
```json
{
  "type": "FILE_TRANSFER_REQUEST",
  "messageId": "msg-uuid",
  "senderDeviceId": "device-uuid",
  "receiverDeviceId": "device-uuid",
  "timestamp": 1713504000000,
  "payload": {
    "fileName": "photo.jpg",
    "fileSize": 2048576,
    "mimeType": "image/jpeg",
    "totalChunks": 2
  }
}
```

**文件传输响应**：
```json
{
  "type": "FILE_TRANSFER_RESPONSE",
  "messageId": "msg-uuid",
  "status": "ACCEPTED",  // ACCEPTED, REJECTED
  "timestamp": 1713504001000
}
```

**文件数据块**：
```json
{
  "type": "FILE_CHUNK",
  "messageId": "msg-uuid",
  "chunkIndex": 0,
  "chunkData": "base64..."
}
```

**传输完成**：
```json
{
  "type": "FILE_TRANSFER_COMPLETE",
  "messageId": "msg-uuid",
  "status": "SUCCESS",  // SUCCESS, FAILED
  "timestamp": 1713504010000
}
```

---

## 九、剪贴板同步模块

### 9.1 功能定义

剪贴板内容在设备间自动同步。

**支持场景**：
- 任意设备 ↔ 任意设备（双向对等）

### 9.2 实现要点

**监听机制**：
- Android：尝试后台监听（可能在某些 ROM 失效）
- Windows：自动监听剪贴板变化

**同步流程**：
1. 检测到剪贴板变化
2. 自动发送到所有已连接设备
3. 接收方自动写入剪贴板
4. 频率限制：每 5 秒最多同步一次

**支持类型**：
- 纯文本
- 图片

**限制**：
- 文本最大 10MB
- 图片最大 5MB

**配置选项**：
- 全局开关：可以完全关闭剪贴板同步
- 同步方向：双向同步（默认）
- 内容预览：用户可配置是否在通知中显示预览

**数据管理**：
- 保存最近 20 条剪贴板历史
- 不过滤敏感内容（用户自己判断）

### 9.3 协议格式

**剪贴板同步**：
```json
{
  "type": "CLIPBOARD_SYNC",
  "messageId": "msg-uuid",
  "senderDeviceId": "device-uuid",
  "timestamp": 1713504000000,
  "payload": {
    "contentType": "TEXT",  // TEXT, IMAGE
    "content": "复制的文本内容",
    "imageData": null  // 如果是图片，则为 base64
  }
}
```

---

## 十、连接管理与状态同步

### 10.1 连接状态

系统维护以下连接状态：
- `DISCONNECTED`：未连接
- `DISCOVERING`：搜索中
- `PAIRING`：配对中
- `CONNECTING`：连接中
- `CONNECTED`：已连接
- `RECONNECTING`：重连中

### 10.2 心跳机制

- 每 30 秒发送一次心跳
- 超过 60 秒未收到心跳，标记为断开
- 自动尝试重连（最多 3 次）

### 10.3 断线重连

- 检测到断开后，自动尝试重连
- 重连间隔：5 秒、10 秒、30 秒
- 重连成功后，恢复能力协商

---

## 十一、安全与隐私

### 11.1 可信设备模型

- 只有完成配对的设备才能通信
- 配对时使用 ECDH 密钥交换
- 保存共享密钥用于后续通信验证

### 11.2 数据存储

- 本地保存通知、短信、文件记录、剪贴板历史
- 用户可手动删除历史
- 支持撤销可信设备

### 11.3 权限管理

**Android 端必需权限**：
- 通知访问权限
- 短信读取和发送权限
- 存储权限
- 网络权限

**Windows 端必需权限**：
- 网络访问
- 文件系统访问
- 剪贴板访问

---

## 十二、UI 设计要求

### 12.1 Android 端页面

- 首页 / 设备状态总览
- 设备连接管理页
- 通知历史页
- 短信页
- 文件传输页
- 剪贴板历史页
- 设置页
- 权限引导页

### 12.2 Windows 端页面

- 设备连接页
- 通知历史页
- 文件收发页
- 剪贴板历史页
- 设置页

### 12.3 设计原则

- 所有跨端动作都必须给出结果反馈
- 所有失败都必须能看到原因
- 平板、折叠屏和桌面端必须使用适配布局
- 设备列表显示设备类型图标（📱 手机、📱 平板、💻 电脑）

---

## 十三、性能指标

### 13.1 目标值

- 通知镜像延迟：< 1 秒
- 短信同步延迟：< 1 秒
- 文件传输速度：> 10MB/s（局域网 Wi-Fi）
- 剪贴板同步延迟：< 2 秒
- 设备发现时间：< 5 秒

### 13.2 资源占用

- Android 后台内存：< 100MB
- Windows 后台内存：< 150MB
- 安装包大小：Android < 50MB，Windows < 80MB

---

## 十四、测试与验收

### 14.1 测试环境

- 至少 2 台 Android 真机（手机 + 平板）
- 至少 1 台 Windows 设备
- 同一局域网环境

### 14.2 核心测试场景

**配对测试**：
- 扫描二维码配对
- 输入配对码配对
- 配对失败处理

**通知镜像测试**：
- 微信、QQ、短信等常见应用通知
- 黑名单过滤
- 图标缓存
- 来源设备显示

**短信协同测试**：
- 接收短信同步
- 代发短信
- 验证码识别
- 已读状态同步
- 双卡选择

**文件传输测试**：
- 小文件（< 1MB）
- 大文件（> 100MB）
- 批量文件
- 传输中断重试

**剪贴板同步测试**：
- 文本同步
- 图片同步
- 频率限制
- 全局开关

### 14.3 验收标准

- 核心功能真实可用
- 无明显 UI 不可用
- 日志可定位关键失败原因
- 至少完成一次完整的多设备联调

---

## 十五、开发阶段划分

### Phase 1：基础框架与配对（2周）
- Flutter 项目搭建
- mDNS 设备发现
- 二维码配对流程
- TCP 连接建立
- 能力协商

### Phase 2：通知镜像（2周）
- Android NotificationListenerService
- 通知提取和转发
- 接收端显示
- 黑名单和配置

### Phase 3：文件传输（2周）
- TCP 文件传输协议
- 分块传输
- 进度显示
- 批量传输

### Phase 4：短信协同（2周）
- Android SmsReceiver
- 短信同步
- 代发机制
- 会话列表

### Phase 5：剪贴板同步（1周）
- 剪贴板监听
- 自动同步
- 历史记录

### Phase 6：Windows 端适配（2周）
- Windows 平台适配层
- 系统托盘
- 本地通知
- 剪贴板监听

### Phase 7：优化与测试（2周）
- 性能优化
- 稳定性测试
- UI 优化
- 文档完善

---

## 十六、风险与限制

### 16.1 已知限制

- Android 后台剪贴板监听可能在某些 ROM 失效
- 通知镜像无法获取某些应用的完整通知内容
- 短信代发需要用户授予短信权限
- 局域网限制，无法跨公网使用

### 16.2 风险点

- 不同 Android ROM 行为差异
- Windows 防火墙可能阻止连接
- 用户可能拒绝授予必要权限
- 大文件传输可能因网络波动失败

---

## 十七、参考项目

- **KDE Connect**：设备发现、配对流程、插件架构
- **LocalSend**：Flutter 跨平台实现、mDNS + HTTP 文件传输
- **Snapdrop/Pairdrop**：WebRTC 数据通道（可选参考）

---

## 附录：协议消息类型汇总

| 消息类型 | 说明 |
|---------|------|
| `CAPABILITY_ANNOUNCE` | 能力协商 |
| `PAIRING_REQUEST` | 配对请求 |
| `PAIRING_RESPONSE` | 配对响应 |
| `HEARTBEAT` | 心跳 |
| `NOTIFICATION` | 通知镜像 |
| `NOTIFICATION_DISMISSED` | 通知清除 |
| `SMS_RECEIVED` | 短信接收 |
| `SMS_SEND_REQUEST` | 短信代发请求 |
| `SMS_SEND_STATUS` | 短信发送状态 |
| `SMS_READ_STATUS` | 短信已读状态 |
| `FILE_TRANSFER_REQUEST` | 文件传输请求 |
| `FILE_TRANSFER_RESPONSE` | 文件传输响应 |
| `FILE_CHUNK` | 文件数据块 |
| `FILE_TRANSFER_COMPLETE` | 文件传输完成 |
| `CLIPBOARD_SYNC` | 剪贴板同步 |
| `CALL_STATE` | 来电状态 |

---

**文档版本**：v5.0  
**最后更新**：2026-04-19
