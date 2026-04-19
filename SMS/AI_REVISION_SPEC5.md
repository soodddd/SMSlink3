AI 代码修改指令集

1. 概览
文件路径: `app/src/main/AndroidManifest.xml`, `feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt`, `feature/device/src/main/java/com/smslink/feature/device/PairingManager.kt`, `network/hotspot/src/main/java/com/smslink/network/hotspot/HotspotManager.kt`, `network/transport/src/main/java/com/smslink/network/transport/TcpClient.kt`, `network/transport/src/test/java/com/smslink/network/transport/ConnectionDiagnosticTest.kt`, `network/transport/src/test/java/com/smslink/network/transport/TcpTransportTest.kt`

主要任务: 修正“设备配对已完成”的虚假闭环，补齐配对协议、热点凭据、权限声明、设备持久化和 TCP 客户端生命周期管理，使当前已写代码真正支撑项目阶段目标。

技术栈: Kotlin + Android 13+ + Jetpack Compose + Coroutines/Flow + Room + Hilt

2. 修改任务清单
[任务 #01]
定位锚点: `fun createPairingRequest(deviceId: String, deviceName: String, code: String): Message` / `fun parsePairingRequest(message: Message): PairingRequest?`

当前代码:
```kotlin
fun createPairingRequest(deviceId: String, deviceName: String, code: String): Message {
    val payload = buildString {
        append("deviceId:$deviceId\n")
        append("deviceName:$deviceName\n")
        append("code:$code\n")
        append("timestamp:${System.currentTimeMillis()}")
    }.toByteArray()

    return Message(
        type = MessageType.DEVICE_DISCOVERY,
        flags = Message.FLAG_REQUIRES_ACK.toByte(),
        messageId = System.currentTimeMillis(),
        payload = payload
    )
}

fun parsePairingRequest(message: Message): PairingRequest? {
    if (message.type != MessageType.DEVICE_DISCOVERY) return null
    // ...
}
```
修改方案:
```kotlin
fun createPairingRequest(deviceId: String, deviceName: String, code: String): Message {
    val payload = buildString {
        append("deviceId:$deviceId\n")
        append("deviceName:$deviceName\n")
        append("code:$code\n")
        append("timestamp:${System.currentTimeMillis()}")
    }.toByteArray()

    return Message(
        type = MessageType.DEVICE_PAIR_REQUEST,
        flags = Message.FLAG_REQUIRES_ACK.toByte(),
        messageId = System.currentTimeMillis(),
        payload = payload
    )
}

fun parsePairingRequest(message: Message): PairingRequest? {
    if (message.type != MessageType.DEVICE_PAIR_REQUEST) return null

    val data = String(message.payload)
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                parts[0] to parts[1]
            } else {
                null
            }
        }
        .toMap()

    val timestamp = data["timestamp"]?.toLongOrNull() ?: return null
    if (System.currentTimeMillis() - timestamp > PAIRING_TIMEOUT_MS) return null

    return PairingRequest(
        deviceId = data["deviceId"] ?: return null,
        deviceName = data["deviceName"] ?: return null,
        code = data["code"] ?: return null,
        timestamp = timestamp
    )
}
```
修改逻辑: 当前配对请求错误复用了 `DEVICE_DISCOVERY`，会让发现消息和配对消息在协议层混淆，直接违背 `PROTOCOL_SPEC.md` 的消息类型定义，也让后续配对状态机无法和发现流量分离。必须把配对请求恢复为 `DEVICE_PAIR_REQUEST`，并在解析时做严格字段与时效校验。

严重程度: 高

[任务 #02]
定位锚点: `private val localDeviceId = UUID.randomUUID().toString()` / `private fun initializePrimary()` / `suspend fun connectToDevice(device: DeviceInfo)`

当前代码:
```kotlin
private val deviceDiscovery = DeviceDiscovery(context)
private val pairingManager = PairingManager()

private var tcpServer: TcpServer? = null
private var tcpClient: TcpClient? = null

private val localDeviceId = UUID.randomUUID().toString()

private fun initializePrimary() {
    tcpServer = TcpServer(port = 8888, maxConnections = 1).apply {
        start()
    }

    val deviceInfo = DeviceInfo(
        deviceId = localDeviceId,
        deviceName = android.os.Build.MODEL,
        deviceType = DeviceType.PHONE,
        capabilities = emptySet(),
        ipAddress = null,
        port = 8888
    )
    deviceDiscovery.register(deviceInfo)
    monitorServerConnections()
}

suspend fun connectToDevice(device: DeviceInfo) {
    tcpClient = TcpClient(
        host = device.ipAddress ?: return,
        port = device.port,
        autoReconnect = true
    )
    tcpClient?.connect()
    _connectionState.value = DeviceConnectionState.Connected(device.deviceId)
    _pairedDevice.value = device
}
```
修改方案:
```kotlin
class DeviceManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val hotspotManager: HotspotManager,
    private val wifiConnectionManager: WifiConnectionManager,
    private val localIdentityStore: LocalIdentityStore
) {
    private suspend fun getLocalDeviceId(): String = localIdentityStore.getOrCreateDeviceId()

    private suspend fun initializePrimary() {
        val hotspotInfo = hotspotManager.startHotspot().getOrThrow()

        tcpServer = TcpServer(port = 8888, maxConnections = 1).apply { start() }

        val deviceInfo = DeviceInfo(
            deviceId = getLocalDeviceId(),
            deviceName = android.os.Build.MODEL,
            deviceType = DeviceType.PHONE,
            capabilities = emptySet(),
            ipAddress = hotspotInfo.hostAddress,
            port = 8888
        )
        deviceDiscovery.register(deviceInfo)
        monitorServerConnections()
    }

    suspend fun connectToDevice(device: DeviceInfo, hotspotInfo: HotspotManager.HotspotInfo) {
        wifiConnectionManager.connectToNetwork(hotspotInfo.ssid, hotspotInfo.passphrase)
            .first { it is WifiConnectionManager.ConnectionState.Connected }

        tcpClient = TcpClient(
            host = device.ipAddress ?: error("Missing host address"),
            port = device.port,
            autoReconnect = true
        )
        tcpClient?.connect()

        deviceRepository.saveDevice(device, isPaired = true)
        _pairedDevice.value = device
        _connectionState.value = DeviceConnectionState.Connected(device.deviceId)
        monitorClientConnection()
    }
}
```
修改逻辑: `PROJECT_STATUS.md` 把 `feature:device` 描述成“协调 discovery/hotspot/transport、完成配对并持久化设备信息”，但当前 `DeviceManager` 既没有使用 `HotspotManager` / `WifiConnectionManager`，也没有接入 `DeviceRepository` / `SmsLinkDatabase`，甚至本机 `deviceId` 每次进程重启都会随机变化。现状只能“裸 TCP 连接某个 IP”，根本达不到“设备可配对通信”的阶段目标，必须改成真实编排器并把本机身份持久化。

严重程度: 阻断

[任务 #03]
定位锚点: `fun startHotspot(ssid: String, password: String): Result<Unit>` / `data class HotspotInfo(val ssid: String, val isEnabled: Boolean)`

当前代码:
```kotlin
fun startHotspot(ssid: String, password: String): Result<Unit> {
    if (hotspotReservation != null) return Result.success(Unit)
    if (ssid.isBlank() || password.length < 8) {
        val error = IllegalArgumentException("SSID 不能为空且密码长度必须不少于 8 位")
        _hotspotState.value = HotspotState.Error(error)
        return Result.failure(error)
    }

    _hotspotState.value = HotspotState.Enabling

    return runCatching {
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val activeSsid = reservation.softApConfiguration?.ssid ?: ssid
                    _hotspotState.value = HotspotState.Enabled(activeSsid)
                }
            },
            null
        )
    }
}

data class HotspotInfo(
    val ssid: String,
    val isEnabled: Boolean
)
```
修改方案:
```kotlin
fun startHotspot(): Result<HotspotInfo> {
    if (hotspotReservation != null) {
        return getHotspotInfo()?.let(Result.Companion::success)
            ?: Result.failure(IllegalStateException("Hotspot already active but info missing"))
    }

    _hotspotState.value = HotspotState.Enabling

    return suspendCancellableCoroutine { continuation ->
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val config = reservation.softApConfiguration
                    val info = HotspotInfo(
                        ssid = config?.ssid ?: error("Missing SSID"),
                        passphrase = config?.passphrase ?: error("Missing passphrase"),
                        isEnabled = true
                    )
                    _hotspotState.value = HotspotState.Enabled(info)
                    continuation.resume(Result.success(info)) {}
                }

                override fun onFailed(reason: Int) {
                    val error = IllegalStateException("LocalOnlyHotspot 启动失败: $reason")
                    _hotspotState.value = HotspotState.Error(error)
                    continuation.resume(Result.failure(error)) {}
                }
            },
            null
        )
    }
}

data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    val isEnabled: Boolean
)
```
修改逻辑: 当前 API 表面上接受 `ssid/password`，但 `LocalOnlyHotspot` 实际并没有使用这两个参数，调用方也拿不到真实口令，导致“热点管理已完成”只是伪闭环，副设备根本无法根据该模块返回的信息接入热点。必须去掉假的输入参数，改为返回系统实际生成的 SSID/密码，并让 `HotspotState.Enabled` 持有完整凭据。

严重程度: 阻断

[任务 #04]
定位锚点: `app/src/main/AndroidManifest.xml` 的权限声明区

当前代码:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```
修改方案:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
```
修改逻辑: 代码已经调用 `NsdManager`、`WifiNetworkSpecifier` 和 `WifiManager.startLocalOnlyHotspot()`，但 Manifest 只保留了最基础的网络权限，真机上热点创建和 Wi‑Fi 接入根本不具备所需授权。这里至少要补齐当前已实现模块真正需要的 Wi‑Fi 权限，同时继续保持“未实现功能的危险权限不要提前下沉到 app 模块”的边界。

严重程度: 高

[任务 #05]
定位锚点: `private fun observeConnection(activeConnection: Connection)` / `fun disconnect()` in `TcpClient`

当前代码:
```kotlin
private fun observeConnection(activeConnection: Connection) {
    connectionMonitorJob?.cancel()
    messageForwardJob?.cancel()

    messageForwardJob = scope.launch {
        activeConnection.messageFlow.collect { _messageFlow.emit(it) }
    }

    connectionMonitorJob = scope.launch {
        val state = activeConnection.connectionStateFlow.first {
            it is Connection.ConnectionState.Disconnected
        } as Connection.ConnectionState.Disconnected

        messageForwardJob?.cancel()
        if (connection === activeConnection) {
            connection = null
        }
        _connectionStateFlow.emit(ClientState.Disconnected(state.cause))
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
private fun clearActiveConnection(closeConnection: Boolean) {
    reconnectJob?.cancel()
    connectionMonitorJob?.cancel()
    messageForwardJob?.cancel()

    val activeConnection = connection
    connection = null

    if (closeConnection) {
        activeConnection?.close()
    }
}

private fun observeConnection(activeConnection: Connection) {
    clearActiveConnection(closeConnection = false)
    connection = activeConnection

    messageForwardJob = scope.launch {
        activeConnection.messageFlow.collect { _messageFlow.emit(it) }
    }

    connectionMonitorJob = scope.launch {
        val state = activeConnection.connectionStateFlow.first {
            it is Connection.ConnectionState.Disconnected
        } as Connection.ConnectionState.Disconnected

        clearActiveConnection(closeConnection = false)
        _connectionStateFlow.emit(ClientState.Disconnected(state.cause))

        if (!userInitiatedDisconnect.get() && autoReconnect && reconnectAttempts.get() < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }
}

fun disconnect() {
    userInitiatedDisconnect.set(true)
    clearActiveConnection(closeConnection = true)
    scope.launch {
        _connectionStateFlow.emit(ClientState.Disconnected(IOException("Disconnected by user")))
    }
}
```
修改逻辑: 当前 `disconnect()` 取消了 `connectionMonitorJob`，却没有取消 `messageForwardJob`。由于 `messageForwardJob` 在 `TcpClient` 自己的 `scope` 中收集 `SharedFlow`，主动断开后它不会自然结束，反复连接/断开会累积悬挂协程。这个问题会污染重连行为，也会让“无协程泄漏”的测试结论失真。

严重程度: 高

[任务 #06]
定位锚点: `ConnectionDiagnosticTest` / `TcpTransportTest` 中所有固定端口和 `delay(...)` 等待逻辑

当前代码:
```kotlin
val server = TcpServer(port = 8881)
server.start()
delay(100)

val clientSocket = Socket("localhost", 8881)
delay(500)
assertTrue(events.isNotEmpty())
```
修改方案:
```kotlin
private fun allocatePort(): Int = ServerSocket(0).use { it.localPort }

@Test
fun `server emits ClientConnected event`() = runBlocking {
    val port = allocatePort()
    val server = TcpServer(port = port)
    server.start()

    val eventDeferred = async {
        withTimeout(5_000) {
            server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
        }
    }

    Socket("localhost", port).use { /* trigger accept */ }

    assertTrue(eventDeferred.await() is TcpServer.ConnectionEvent.ClientConnected)
}
```
修改逻辑: 现有测试大量使用固定端口与拍脑袋 `delay`，`test_output.txt` 已经出现 `ConnectionDiagnosticTest > connection can be created from socket FAILED java.net.BindException`。这类测试即使业务代码没变，也会因为端口占用和调度时序随机失败，无法作为“网络层已完成”的证据。必须统一改成动态端口和事件驱动等待。

严重程度: 中

3. 约束规则 (强制执行)
编码规范: 必须保持 Kotlin 空安全；禁止使用 `!!`；新增并发清理逻辑必须抽到单一函数，避免多处复制取消/置空代码；协议消息类型必须与 `MessageType` 和 `docs/protocol/PROTOCOL_SPEC.md` 保持一致。

禁止变动: 严禁修改 `Message.HEADER_SIZE = 20`、`MessageCodec` 头布局、`devices` 表名及现有字段名；严禁把尚未实现的通知/电话/存储危险权限重新一次性加回 `app` 模块。

依赖限制: 禁止引入新的第三方包；只允许使用现有 AndroidX / Kotlin 标准库 / Room / DataStore；若需要结构化配对载荷，优先使用平台自带能力或手写解析，不新增 Gson、Moshi、kotlinx.serialization。

4. 验证标准
[ ] `PairingManager` 的配对请求与解析统一使用 `MessageType.DEVICE_PAIR_REQUEST`，并补充单元测试覆盖错误消息类型和过期时间戳。

[ ] `DeviceManager` 完成热点启动、Wi‑Fi 接入、TCP 建连、设备持久化和稳定本机 `deviceId` 的完整闭环，不能再只做“裸 TCP 连接 IP”。

[ ] `HotspotManager` 返回真实热点凭据，副设备可以消费该凭据调用 `WifiConnectionManager.connectToNetwork(...)`。

[ ] `app/src/main/AndroidManifest.xml` 至少补齐当前已实现 Wi‑Fi 功能所需权限，真机不会因为缺权限导致热点/接入流程直接失败。

[ ] `TcpClient` 主动断开后不残留 `messageForwardJob`，重复 connect/disconnect 不出现悬挂协程与重复消息转发。

[ ] `.\gradlew.bat :feature:device:compileDebugKotlin :network:hotspot:compileDebugKotlin :network:transport:testDebugUnitTest` 通过，且 `ConnectionDiagnosticTest` / `TcpTransportTest` 不再出现 `BindException`、固定端口冲突或基于 `delay` 的随机超时。
