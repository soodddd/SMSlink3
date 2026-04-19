# SMS-Link 通信协议规范

## 版本
- 协议版本: 1.0
- 文档日期: 2026-04-09

## 概述
SMS-Link 使用自定义二进制协议进行设备间通信，支持 TCP、UDP 和蓝牙传输。

## 消息格式

### 消息头 (16 字节)

```
+--------+--------+--------+--------+--------+--------+--------+--------+
| Magic  | Version| Type   | Flags  |      Payload Length (4 bytes)    |
+--------+--------+--------+--------+--------+--------+--------+--------+
|                    Message ID (8 bytes)                              |
+--------+--------+--------+--------+--------+--------+--------+--------+
```

- **Magic** (2 bytes): 0x534C ('SL' for SMS-Link)
- **Version** (1 byte): 协议版本号 (当前为 0x01)
- **Type** (1 byte): 消息类型
- **Flags** (1 byte): 标志位
  - Bit 0: 是否需要 ACK
  - Bit 1: 是否加密
  - Bit 2-7: 保留
- **Payload Length** (4 bytes): 负载长度 (大端序)
- **Message ID** (8 bytes): 消息唯一标识符

### 消息类型

```kotlin
enum class MessageType(val value: Byte) {
    // 设备管理 (0x00-0x0F)
    DEVICE_DISCOVERY(0x00),
    DEVICE_PAIR_REQUEST(0x01),
    DEVICE_PAIR_RESPONSE(0x02),
    DEVICE_HEARTBEAT(0x03),
    DEVICE_ROLE_SWITCH(0x04),
    
    // 通知同步 (0x10-0x1F)
    NOTIFICATION_SYNC(0x10),
    NOTIFICATION_ACTION(0x11),
    NOTIFICATION_DISMISS(0x12),
    
    // 通话功能 (0x20-0x2F)
    CALL_INCOMING(0x20),
    CALL_ANSWER(0x21),
    CALL_END(0x22),
    CALL_AUDIO_DATA(0x23),
    
    // 文件传输 (0x30-0x3F)
    FILE_TRANSFER_REQUEST(0x30),
    FILE_TRANSFER_ACCEPT(0x31),
    FILE_TRANSFER_REJECT(0x32),
    FILE_TRANSFER_DATA(0x33),
    FILE_TRANSFER_COMPLETE(0x34),
    
    // 控制消息 (0xF0-0xFF)
    ACK(0xF0),
    ERROR(0xF1)
}
```

## 负载格式

### 设备发现 (DEVICE_DISCOVERY)

```json
{
  "deviceId": "uuid",
  "deviceName": "string",
  "deviceType": "PHONE|TABLET",
  "capabilities": ["NOTIFICATION", "CALL", "TRANSFER"],
  "timestamp": 1234567890
}
```

### 通知同步 (NOTIFICATION_SYNC)

```json
{
  "notificationId": "string",
  "packageName": "string",
  "appName": "string",
  "title": "string",
  "text": "string",
  "timestamp": 1234567890,
  "icon": "base64_encoded_image",
  "actions": [
    {
      "id": "string",
      "title": "string"
    }
  ]
}
```

### 通话音频数据 (CALL_AUDIO_DATA)

```
+--------+--------+--------+--------+
| Sequence Number (4 bytes)        |
+--------+--------+--------+--------+
| Timestamp (8 bytes)              |
+--------+--------+--------+--------+
| Opus Encoded Audio Data          |
| (variable length)                |
+--------+--------+--------+--------+
```

### 文件传输数据 (FILE_TRANSFER_DATA)

```
+--------+--------+--------+--------+
| Transfer ID (16 bytes UUID)      |
+--------+--------+--------+--------+
| Chunk Index (4 bytes)            |
+--------+--------+--------+--------+
| Total Chunks (4 bytes)           |
+--------+--------+--------+--------+
| Chunk Data (variable length)     |
+--------+--------+--------+--------+
```

## 传输层选择

### TCP
- 用于: 设备配对、文件传输、控制消息
- 端口: 8888
- 特点: 可靠传输、有序到达

### UDP
- 用于: 音频数据传输、心跳
- 端口: 8889
- 特点: 低延迟、允许丢包

### 蓝牙
- 用于: 备用传输通道
- UUID: 00001101-0000-1000-8000-00805F9B34FB
- 特点: 无需网络、功耗较低

## 安全性

### 配对过程
1. 设备 A 发送 DEVICE_PAIR_REQUEST
2. 设备 B 显示配对码
3. 用户在设备 A 输入配对码
4. 设备 A 发送加密的配对确认
5. 双方交换会话密钥

### 加密
- 算法: AES-256-GCM
- 密钥交换: ECDH (Curve25519)
- 会话密钥轮换: 每 24 小时

## 错误处理

### 错误码

```kotlin
enum class ErrorCode(val value: Int) {
    UNKNOWN_ERROR(0),
    INVALID_MESSAGE(1),
    UNSUPPORTED_VERSION(2),
    AUTHENTICATION_FAILED(3),
    DEVICE_NOT_PAIRED(4),
    PERMISSION_DENIED(5),
    RESOURCE_NOT_FOUND(6),
    TRANSFER_FAILED(7)
}
```

## 示例流程

### 设备配对流程

```
Device A                    Device B
   |                           |
   |-- DEVICE_DISCOVERY ------>|
   |<-- DEVICE_DISCOVERY ------|
   |                           |
   |-- PAIR_REQUEST ---------->|
   |                           | (显示配对码)
   |                           |
   |-- PAIR_CONFIRM ---------->|
   |<-- PAIR_RESPONSE ---------|
   |                           |
   |<-- HEARTBEAT ------------>|
```

### 通知同步流程

```
Primary Device          Secondary Device
   |                           |
   | (收到通知)                 |
   |-- NOTIFICATION_SYNC ----->|
   |<-- ACK -------------------|
   |                           | (显示镜像通知)
```

### 通话流程

```
Primary Device          Secondary Device
   |                           |
   | (来电)                     |
   |-- CALL_INCOMING --------->|
   |                           | (显示来电界面)
   |<-- CALL_ANSWER -----------|
   |                           |
   |<-- CALL_AUDIO_DATA ------>|
   |<-- CALL_AUDIO_DATA ------>|
   |                           |
   |-- CALL_END -------------->|
```
