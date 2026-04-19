AI 代码修改指令集
1. 概览
文件路径: `network/transport/src/main/java/com/smslink/network/transport/TcpServer.kt`、`network/transport/src/main/java/com/smslink/network/transport/Connection.kt`、`network/transport/src/main/java/com/smslink/network/transport/TcpClient.kt`、`app/src/main/AndroidManifest.xml`、`PROJECT_STATUS.md`

主要任务: 修复 TCP 传输层的事件时序、并发写流与重连状态缺陷，同时收紧过度声明的权限并纠正文档中的错误实现状态。

技术栈: Android + Kotlin + Coroutines + Flow + Jetpack Compose + Hilt + Gradle Kotlin DSL

2. 修改任务清单
[任务 #01]
定位锚点: `private val _connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 0, extraBufferCapacity = 16)`

当前代码:
```kotlin
private val _connectionFlow = MutableSharedFlow<ConnectionEvent>(replay = 0, extraBufferCapacity = 16)
val connectionFlow: SharedFlow<ConnectionEvent> = _connectionFlow.asSharedFlow()

connectionMonitorJobs[connectionId] = scope.launch {
    _connectionFlow.emit(ConnectionEvent.ClientConnected(connectionId, connection))

    val state = connection.connectionStateFlow.first {
        it is Connection.ConnectionState.Disconnected
    } as Connection.ConnectionState.Disconnected

    connections.remove(connectionId)
    connectionMonitorJobs.remove(connectionId)
    _connectionFlow.emit(ConnectionEvent.ClientDisconnected(connectionId, state.cause))
}
```
修改方案:
```kotlin
private val _connectionFlow = MutableSharedFlow<ConnectionEvent>(
    replay = 1,
    extraBufferCapacity = 16
)
val connectionFlow: SharedFlow<ConnectionEvent> = _connectionFlow.asSharedFlow()

connectionMonitorJobs[connectionId] = scope.launch {
    _connectionFlow.emit(ConnectionEvent.ClientConnected(connectionId, connection))

    val state = connection.connectionStateFlow.first {
        it is Connection.ConnectionState.Disconnected
    } as Connection.ConnectionState.Disconnected

    connections.remove(connectionId)
    connectionMonitorJobs.remove(connectionId)
    _connectionFlow.emit(ConnectionEvent.ClientDisconnected(connectionId, state.cause))
}
```
修改逻辑: 当前 `SharedFlow` 不保留最近一次连接事件，调用方如果在 `client.connect()` 之后才订阅，就会永久错过 `ClientConnected`，这正好对应 `PROJECT_STATUS.md` 中列出的 5 个超时用例。至少要让最近一次连接事件对晚订阅者可见，否则 `connectionFlow.first { it is ClientConnected }` 的设计本身就不成立。

严重程度: 高

[任务 #02]
定位锚点: `suspend fun sendMessage(message: Message)` in `Connection.kt`

当前代码:
```kotlin
suspend fun sendMessage(message: Message) {
    if (!isConnected.get()) {
        throw IOException("Connection is closed")
    }

    try {
        val encoded = MessageCodec.encode(message)
        socket.getOutputStream().write(encoded)
        socket.getOutputStream().flush()
    } catch (e: Exception) {
        handleDisconnection(e)
        throw e
    }
}
```
修改方案:
```kotlin
private val outputStream = socket.getOutputStream()
private val writeMutex = Mutex()

suspend fun sendMessage(message: Message) {
    if (!isConnected.get()) {
        throw IOException("Connection is closed")
    }

    try {
        val encoded = MessageCodec.encode(message)
        writeMutex.withLock {
            outputStream.write(encoded)
            outputStream.flush()
        }
    } catch (e: Exception) {
        handleDisconnection(e)
        throw e
    }
}
```
修改逻辑: `sendMessage()` 既会被业务协程调用，也会被心跳协程调用。当前实现没有任何串行化保护，多个协程同时向同一个 `SocketOutputStream` 写入时，TCP 字节流可能交错，导致帧边界损坏、解码失败、随机断线。必须显式串行化写操作。

严重程度: 阻断

[任务 #03]
定位锚点: `private val userInitiatedDisconnect = AtomicBoolean(false)` and `suspend fun connect()` in `TcpClient.kt`

当前代码:
```kotlin
private val userInitiatedDisconnect = AtomicBoolean(false)

suspend fun connect() {
    check(connection == null) { "TcpClient is already connected" }
    if (!isConnecting.compareAndSet(false, true)) return

    try {
        _connectionStateFlow.emit(ClientState.Connecting)

        val socket = Socket(host, port)
        val newConnection = Connection(socket)

        connection = newConnection
        reconnectAttempts.set(0)
        _connectionStateFlow.emit(ClientState.Connected)
        observeConnection(newConnection)
    } catch (e: Exception) {
        _connectionStateFlow.emit(ClientState.Failed(e))
        if (autoReconnect && reconnectAttempts.get() < maxReconnectAttempts) {
            scheduleReconnect()
        }
        throw e
    } finally {
        isConnecting.set(false)
    }
}

fun disconnect() {
    userInitiatedDisconnect.set(true)
    reconnectJob?.cancel()
    connectionMonitorJob?.cancel()
    connection?.close()
    connection = null
    scope.launch {
        _connectionStateFlow.emit(ClientState.Disconnected(IOException("Disconnected by user")))
    }
}
```
修改方案:
```kotlin
private val userInitiatedDisconnect = AtomicBoolean(false)

suspend fun connect() {
    check(connection == null) { "TcpClient is already connected" }
    if (!isConnecting.compareAndSet(false, true)) return

    try {
        userInitiatedDisconnect.set(false)
        _connectionStateFlow.emit(ClientState.Connecting)

        val socket = Socket(host, port)
        val newConnection = Connection(socket)

        connection = newConnection
        reconnectAttempts.set(0)
        _connectionStateFlow.emit(ClientState.Connected)
        observeConnection(newConnection)
    } catch (e: Exception) {
        _connectionStateFlow.emit(ClientState.Failed(e))
        if (autoReconnect && reconnectAttempts.get() < maxReconnectAttempts) {
            scheduleReconnect()
        }
        throw e
    } finally {
        isConnecting.set(false)
    }
}

fun disconnect() {
    userInitiatedDisconnect.set(true)
    reconnectJob?.cancel()
    connectionMonitorJob?.cancel()
    connection?.close()
    connection = null
    scope.launch {
        _connectionStateFlow.emit(ClientState.Disconnected(IOException("Disconnected by user")))
    }
}
```
修改逻辑: 手动断开后 `userInitiatedDisconnect` 永远停留在 `true`，如果用户之后再次主动 `connect()`，一旦新连接再掉线，自动重连会被永久禁用。这不是一次性断开语义，而是“熔断”了客户端后续生命周期。手动断开标志必须在新连接开始前复位。

严重程度: 高

[任务 #04]
定位锚点: `<!-- 电话权限 -->` in `app/src/main/AndroidManifest.xml`

当前代码:
```xml
<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- 电话权限 -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />

<!-- 音频权限 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- 通知权限 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 前台服务权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
修改方案:
```xml
<!-- 当前基础版本仅保留已实现能力必需的权限；危险权限在对应功能真正落地时再下沉到功能模块 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```
修改逻辑: 当前应用入口只有一个 `Text("SMS-Link")`，设备发现、电话、音频、蓝牙、通知、前台服务均未实现，却在主清单里预先声明了一整组高风险权限。这既违反最小权限原则，也会提高审核/上架风险，并扩大未来安全面。基础 App Manifest 只能保留“现在真正用到”的权限，其余权限等功能模块落地后再声明。

严重程度: 高

[任务 #05]
定位锚点: `16字节消息头` and `TCP 传输层实现` in `PROJECT_STATUS.md`

当前代码:
```md
- ✅ **Message.kt** - 消息数据结构 (16字节消息头 + 可变长度负载)

#### 核心特性:
- 16 字节消息头: Magic (0x534C) + Version (0x01) + Type + Flags + Length + MessageID

#### 待解决问题:
- 🔧 测试超时问题 (5个测试用例在等待 ClientConnected 事件时超时)
  - client and server exchange messages
  - server handles multiple clients
  - connection handles large messages
  - heartbeat keeps connection alive
  - connection rejects oversized payload length
```
修改方案:
```md
- ✅ **Message.kt** - 消息数据结构 (20字节消息头 + 可变长度负载)

#### 核心特性:
- 20 字节消息头: Magic (2) + Version (1) + Type (1) + Flags (1) + Padding (3) + Length (4) + MessageID (8)

#### 当前状态:
- `network:transport` 仍处于“修复中”，`ClientConnected` 事件时序问题尚未解决
- 凡是“TCP 已完成”“100KB 大消息已通过”的结论，都必须以最新单元测试结果重新核对后再保留
```
修改逻辑: 这个状态文档会被后续 AI 当作事实来源，但它一边写协议头已经改成 20 字节，一边又保留“16字节消息头”的旧描述；一边承认 transport 测试超时，一边又在多个位置暗示 TCP 已完成。继续把这种文档喂给下一个 AI，只会让它在错误前提上继续修改代码。

严重程度: 中

3. 约束规则 (强制执行)
编码规范: 禁止使用 `any`/弱类型替代；Kotlin 并发路径必须显式串行化共享 I/O；保留现有 public 类名、方法名和消息类型枚举值，不要引入隐式行为变化。

禁止变动: 严禁修改现有消息协议字段顺序、字段宽度、Magic、Version、`MessageType` 数值；严禁改动 `TcpClient.connect()/disconnect()/sendMessage()` 与 `TcpServer.start()/stop()/getConnection()` 的对外方法签名。

依赖限制: 禁止引入新的第三方包；只允许使用 Kotlin 标准库、JDK、项目中已存在的 `kotlinx.coroutines` 能力完成修复。

4. 验证标准
[ ] `TcpTransportTest` 中与 `ClientConnected` 相关的超时用例必须全部稳定通过，不能依赖增加 `delay()` 规避时序问题

[ ] 必须新增回归测试，覆盖“先连接后订阅 `connectionFlow` 仍能拿到 `ClientConnected` 事件”

[ ] 必须新增回归测试，覆盖“手动断开后重新 connect，再次异常断开时自动重连仍然生效”

[ ] 必须新增回归测试，覆盖“业务消息发送与心跳并发发送时不会破坏帧边界”

[ ] `PROJECT_STATUS.md` 中关于协议头大小、transport 完成度、测试状态的表述必须与当前代码和测试结果一致

[ ] `AndroidManifest.xml` 修改后不得再声明当前版本未实现功能对应的危险权限

[ ] `.\gradlew.bat :network:transport:test` 和 `.\gradlew.bat build` 必须可执行，不能再出现 `GradleWrapperMain` / `org.gradle.wrapper.IDownload` 初始化失败
