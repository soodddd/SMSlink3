# Network API 文档

## 状态说明

**本文件中的接口定义均为"阶段规划中的目标 API 草案"，当前仓库未提交对应实现。**

以下内容仅用于约束后续开发方向，不能视为当前可调用接口。

---

## network:protocol (Planned API)

### Message

```kotlin
data class Message(
    val type: MessageType,
    val payload: ByteArray,
    val flags: MessageFlags = MessageFlags(),
    val messageId: String = UUID.randomUUID().toString()
)

data class MessageFlags(
    val requiresAck: Boolean = false,
    val encrypted: Boolean = false
)
```

**状态**: 未实现

### MessageCodec (Planned)

```kotlin
interface MessageCodec {
    fun encode(message: Message): ByteArray
    fun decode(data: ByteArray): Result<Message>
}

class BinaryMessageCodec : MessageCodec {
    override fun encode(message: Message): ByteArray
    override fun decode(data: ByteArray): Result<Message>
}
```

**状态**: 未实现

---

## network:transport (Planned API)

### Transport 接口

```kotlin
interface Transport {
    suspend fun connect(address: String): Result<Connection>
    suspend fun disconnect()
    fun isConnected(): Boolean
    val connectionState: StateFlow<ConnectionState>
}

interface Connection {
    suspend fun send(data: ByteArray): Result<Unit>
    val received: Flow<ByteArray>
    suspend fun close()
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
```

**状态**: 未实现

---

## network:discovery (Planned API)

### DeviceDiscovery

```kotlin
class DeviceDiscovery(
    private val context: Context
) {
    fun register(deviceInfo: Device): Result<Unit>
    fun unregister()
    fun discover(): Flow<List<Device>>
    val discoveredDevices: StateFlow<List<Device>>
}
```

**状态**: 未实现

---

## network:hotspot (Planned API)

### HotspotManager

```kotlin
class HotspotManager(
    private val context: Context
) {
    suspend fun createHotspot(
        ssid: String,
        password: String
    ): Result<HotspotInfo>
    
    suspend fun stopHotspot(): Result<Unit>
    
    suspend fun connectToHotspot(
        ssid: String,
        password: String
    ): Result<Unit>
    
    val hotspotState: StateFlow<HotspotState>
}

data class HotspotInfo(
    val ssid: String,
    val password: String,
    val ipAddress: String
)

enum class HotspotState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING,
    ERROR
}
```

**状态**: 未实现

---

## 错误处理 (Planned)

所有网络操作返回 `Result<T>` 类型:

```kotlin
sealed class NetworkError : Exception() {
    object ConnectionFailed : NetworkError()
    object Timeout : NetworkError()
    object InvalidMessage : NetworkError()
    object DeviceNotFound : NetworkError()
    data class Unknown(val cause: Throwable) : NetworkError()
}
```

**状态**: 未实现
