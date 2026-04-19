# Network Protocol Module

## 功能概述
定义设备间通信协议，包括消息格式、序列化/反序列化。

## 已实现功能 ✅

### 1. 消息类型 (MessageType.kt)
- 设备管理 (0x00-0x0F): 发现、配对、心跳、角色切换
- 通知同步 (0x10-0x1F): 同步、操作、关闭
- 通话功能 (0x20-0x2F): 来电、接听、挂断、音频数据
- 文件传输 (0x30-0x3F): 请求、接受、拒绝、数据、完成
- 控制消息 (0xF0-0xFF): ACK、错误

### 2. 消息结构 (Message.kt)
- 16 字节消息头: Magic (0x534C) + Version + Type + Flags + Length + MessageID
- 标志位支持: RequiresAck, IsEncrypted
- 可变长度负载

### 3. 编解码器 (MessageCodec.kt)
- `encode()`: 消息序列化为字节数组 (大端序)
- `decode()`: 字节数组反序列化为消息
- 完整的格式验证和错误处理

### 4. 错误码 (ErrorCode.kt)
- 8 种标准错误码 (0-7)
- 支持错误码映射

## 使用示例

```kotlin
// 创建并编码消息
val message = Message(
    type = MessageType.DEVICE_DISCOVERY,
    flags = Message.FLAG_REQUIRES_ACK.toByte(),
    messageId = System.currentTimeMillis(),
    payload = jsonData.toByteArray()
)
val encoded = MessageCodec.encode(message)

// 解码消息
val result = MessageCodec.decode(receivedData)
if (result.isSuccess) {
    val msg = result.getOrThrow()
    when (msg.type) {
        MessageType.DEVICE_DISCOVERY -> handleDiscovery(msg)
        // ...
    }
}
```

## 依赖关系
- 无外部依赖 (仅使用 Java NIO)

## 协议设计

详见 `docs/protocol/PROTOCOL_SPEC.md`

## 测试

```bash
./gradlew :network:protocol:test
```

单元测试覆盖:
- ✅ 基本编解码
- ✅ 所有消息类型
- ✅ 空负载处理
- ✅ 标志位保留
- ✅ 格式验证

## 下一步
- [ ] TCP 传输层实现
- [ ] UDP 传输层实现
- [ ] 加密支持 (AES-256-GCM)
