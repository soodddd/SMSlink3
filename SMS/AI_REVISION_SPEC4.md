AI 代码修改指令集
1. 概览
文件路径: network/discovery/src/main/java/com/smslink/network/discovery/DeviceDiscovery.kt, network/hotspot/src/main/java/com/smslink/network/hotspot/HotspotManager.kt, network/hotspot/src/main/java/com/smslink/network/hotspot/WifiConnectionManager.kt, network/transport/src/main/java/com/smslink/network/transport/TcpClient.kt, network/protocol/src/test/java/com/smslink/network/protocol/MessageCodecTest.kt

主要任务: 修复网络发现、热点管理和 TCP 客户端中的功能失真、状态时序与测试盲区，使当前实现真正满足“第二阶段网络通信层”目标，而不是仅在文档中显示完成。

技术栈: Kotlin + Android SDK 33+ + Coroutines/Flow + Jetpack Compose + Hilt

2. 修改任务清单
[任务 #01]
定位锚点: `fun startDiscovery(): Flow<List<DeviceInfo>> = callbackFlow {` / `override fun onServiceLost(serviceInfo: NsdServiceInfo?)`

当前代码:
```kotlin
fun startDiscovery(): Flow<List<DeviceInfo>> = callbackFlow {
    discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.let { resolveService(it) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.let {
                discoveredDevices.remove(it.serviceName)
                _devicesFlow.value = discoveredDevices.values.toList()
                trySend(discoveredDevices.values.toList())
            }
        }
    }

    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

    awaitClose {
        stopDiscovery()
    }
}

private fun resolveService(serviceInfo: NsdServiceInfo) {
    val resolveListener = object : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.let {
                val deviceId = it.attributes["deviceId"]?.decodeToString() ?: return
                val deviceInfo = DeviceInfo(
                    deviceId = deviceId,
                    deviceName = it.serviceName,
                    deviceType = deviceType,
                    capabilities = capabilities,
                    ipAddress = it.host?.hostAddress,
                    port = it.port
                )

                discoveredDevices[deviceId] = deviceInfo
                _devicesFlow.value = discoveredDevices.values.toList()
            }
        }
    }

    nsdManager.resolveService(serviceInfo, resolveListener)
}
```

修改方案:
```kotlin
private val serviceNameToDeviceId = ConcurrentHashMap<String, String>()

fun startDiscovery(): Flow<List<DeviceInfo>> {
    if (discoveryListener != null) return devicesFlow

    discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            serviceInfo?.let(::resolveService)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            serviceInfo ?: return
            val deviceId = serviceNameToDeviceId.remove(serviceInfo.serviceName)
                ?: discoveredDevices.values.firstOrNull { it.deviceName == serviceInfo.serviceName }?.deviceId
                ?: return

            discoveredDevices.remove(deviceId)
            publishDevices()
        }

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            discoveredDevices.clear()
            serviceNameToDeviceId.clear()
            publishDevices()
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        override fun onDiscoveryStarted(serviceType: String?) = Unit
        override fun onDiscoveryStopped(serviceType: String?) = Unit
    }

    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    return devicesFlow
}

private fun publishDevices() {
    _devicesFlow.value = discoveredDevices.values.sortedBy { it.deviceId }
}

private fun resolveService(serviceInfo: NsdServiceInfo) {
    val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
            serviceInfo ?: return
            val deviceInfo = parseDeviceInfo(serviceInfo) ?: return

            serviceNameToDeviceId[serviceInfo.serviceName] = deviceInfo.deviceId
            discoveredDevices[deviceInfo.deviceId] = deviceInfo
            publishDevices()
        }
    }

    nsdManager.resolveService(serviceInfo, resolveListener)
}
```

修改逻辑: 当前实现有两个硬伤。第一，发现成功后只更新 `_devicesFlow`，却没有把新增结果推回 `callbackFlow`，导致调用 `startDiscovery()` 的消费者收不到新增设备。第二，`discoveredDevices` 以 `deviceId` 为 key，但 `onServiceLost()` 却按 `serviceName` 删除，设备丢失事件会静默失败，列表长期残留脏数据。必须统一 key 语义，并用单一发布函数维护状态。

严重程度: 高

[任务 #02]
定位锚点: `val deviceType = it.attributes["deviceType"]?.decodeToString()?.let { type -> DeviceType.valueOf(type) }`

当前代码:
```kotlin
val deviceType = it.attributes["deviceType"]?.decodeToString()?.let { type ->
    DeviceType.valueOf(type)
} ?: DeviceType.PHONE
val capabilities = it.attributes["capabilities"]?.decodeToString()?.split(",")?.mapNotNull { cap ->
    try { DeviceCapability.valueOf(cap) } catch (e: Exception) { null }
}?.toSet() ?: emptySet()
```

修改方案:
```kotlin
private fun parseDeviceInfo(serviceInfo: NsdServiceInfo): DeviceInfo? {
    val deviceId = serviceInfo.attributes["deviceId"]?.decodeToString()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val deviceType = serviceInfo.attributes["deviceType"]
        ?.decodeToString()
        ?.let { raw -> runCatching { DeviceType.valueOf(raw) }.getOrDefault(DeviceType.PHONE) }
        ?: DeviceType.PHONE

    val capabilities = serviceInfo.attributes["capabilities"]
        ?.decodeToString()
        ?.split(',')
        ?.mapNotNull { raw -> runCatching { DeviceCapability.valueOf(raw) }.getOrNull() }
        ?.toSet()
        ?: emptySet()

    return DeviceInfo(
        deviceId = deviceId,
        deviceName = serviceInfo.serviceName,
        deviceType = deviceType,
        capabilities = capabilities,
        ipAddress = serviceInfo.host?.hostAddress,
        port = serviceInfo.port
    )
}
```

修改逻辑: `DeviceType.valueOf()` 对任何脏数据都会直接抛 `IllegalArgumentException`。mDNS/TXT 记录来自外部设备，不可信，当前写法会让一个异常字段击穿整个发现回调，属于典型的输入边界缺失。必须把解析逻辑收敛到单一函数里，对未知枚举值降级，对空 `deviceId` 直接丢弃。

严重程度: 高

[任务 #03]
定位锚点: `fun startHotspot(ssid: String, password: String): Result<Unit>` / `fun stopHotspot(): Result<Unit>`

当前代码:
```kotlin
fun startHotspot(ssid: String, password: String): Result<Unit> {
    return try {
        _hotspotState.value = HotspotState.Enabling

        // TODO: 实现热点启动逻辑

        _hotspotState.value = HotspotState.Enabled(ssid)
        Result.success(Unit)
    } catch (e: Exception) {
        _hotspotState.value = HotspotState.Error(e)
        Result.failure(e)
    }
}

fun stopHotspot(): Result<Unit> {
    return try {
        _hotspotState.value = HotspotState.Disabling

        // TODO: 实现热点停止逻辑

        _hotspotState.value = HotspotState.Disabled
        Result.success(Unit)
    } catch (e: Exception) {
        _hotspotState.value = HotspotState.Error(e)
        Result.failure(e)
    }
}
```

修改方案:
```kotlin
private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

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
            context.mainExecutor,
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val activeSsid = reservation.softApConfiguration?.ssid ?: ssid
                    _hotspotState.value = HotspotState.Enabled(activeSsid)
                }

                override fun onFailed(reason: Int) {
                    hotspotReservation = null
                    _hotspotState.value = HotspotState.Error(
                        IllegalStateException("LocalOnlyHotspot 启动失败: $reason")
                    )
                }

                override fun onStopped() {
                    hotspotReservation = null
                    _hotspotState.value = HotspotState.Disabled
                }
            }
        )
    }
}

fun stopHotspot(): Result<Unit> = runCatching {
    _hotspotState.value = HotspotState.Disabling
    hotspotReservation?.close()
    hotspotReservation = null
    _hotspotState.value = HotspotState.Disabled
}
```

修改逻辑: 这是当前代码里最明显的“伪实现”。函数尚未完成，却直接返回 `Result.success(Unit)` 并把状态置为 `Enabled`，会让上层误判热点已经可用，后续连接逻辑全部建立在错误前提上。必须接入真实系统回调；如果当前阶段确实无法完成，也只能返回失败，绝不能伪造成功状态。

严重程度: 阻断

[任务 #04]
定位锚点: `fun connectToNetwork(ssid: String, password: String): Flow<ConnectionState> = callbackFlow {` / `fun disconnect()`

当前代码:
```kotlin
fun connectToNetwork(ssid: String, password: String): Flow<ConnectionState> = callbackFlow {
    trySend(ConnectionState.Connecting)

    val specifier = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .setWpa2Passphrase(password)
        .build()

    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(specifier)
        .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(ConnectionState.Connected(ssid))
        }

        override fun onLost(network: Network) {
            trySend(ConnectionState.Disconnected)
        }

        override fun onUnavailable() {
            trySend(ConnectionState.Failed(Exception("Network unavailable")))
        }
    }

    connectivityManager.requestNetwork(request, callback)

    awaitClose {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}

fun disconnect() {
    // Android 13+ 限制了直接断开 WiFi 的能力
}
```

修改方案:
```kotlin
private var activeCallback: ConnectivityManager.NetworkCallback? = null
private var activeNetwork: Network? = null

fun connectToNetwork(ssid: String, password: String): Flow<ConnectionState> = callbackFlow {
    disconnect()
    trySend(ConnectionState.Connecting)

    val specifier = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .setWpa2Passphrase(password)
        .build()

    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(specifier)
        .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeNetwork = network
            connectivityManager.bindProcessToNetwork(network)
            trySend(ConnectionState.Connected(ssid))
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                connectivityManager.bindProcessToNetwork(null)
                activeNetwork = null
            }
            trySend(ConnectionState.Disconnected)
        }

        override fun onUnavailable() {
            trySend(ConnectionState.Failed(IllegalStateException("Network unavailable")))
        }
    }

    activeCallback = callback
    connectivityManager.requestNetwork(request, callback)

    awaitClose { disconnect() }
}

fun disconnect() {
    activeCallback?.let {
        runCatching { connectivityManager.unregisterNetworkCallback(it) }
        activeCallback = null
    }
    connectivityManager.bindProcessToNetwork(null)
    activeNetwork = null
}
```

修改逻辑: 当前 `disconnect()` 是空实现，但文档和 API 名称都宣称支持断开。与此同时，`connectToNetwork()` 也没有记录当前 callback/network，导致外部根本无法主动解除连接，也不会解绑进程网络。这个问题会直接破坏后续“热点建立后自动接入”的链路。

严重程度: 高

[任务 #05]
定位锚点: `val messageFlow: SharedFlow<com.smslink.network.protocol.Message>? get() = connection?.messageFlow` / `if (!isConnecting.compareAndSet(false, true)) return`

当前代码:
```kotlin
private var connection: Connection? = null
private var reconnectJob: Job? = null
private var connectionMonitorJob: Job? = null

val messageFlow: SharedFlow<com.smslink.network.protocol.Message>?
    get() = connection?.messageFlow

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
    } finally {
        isConnecting.set(false)
    }
}
```

修改方案:
```kotlin
private var connection: Connection? = null
private var reconnectJob: Job? = null
private var connectionMonitorJob: Job? = null
private var messageForwardJob: Job? = null

private val _messageFlow = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 64)
val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()

suspend fun connect() {
    check(connection == null) { "TcpClient is already connected" }
    check(isConnecting.compareAndSet(false, true)) { "TcpClient is already connecting" }

    try {
        userInitiatedDisconnect.set(false)
        _connectionStateFlow.emit(ClientState.Connecting)

        val socket = Socket(host, port)
        val newConnection = Connection(socket)

        connection = newConnection
        reconnectAttempts.set(0)
        _connectionStateFlow.emit(ClientState.Connected)
        observeConnection(newConnection)
    } finally {
        isConnecting.set(false)
    }
}

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

        if (!userInitiatedDisconnect.get() &&
            autoReconnect &&
            reconnectAttempts.get() < maxReconnectAttempts
        ) {
            scheduleReconnect()
        }
    }
}
```

修改逻辑: 当前公开的 `messageFlow` 直接代理到“当前 Connection 的内部 Flow”。一旦发生自动重连，之前已经开始收集的上层协程会永久绑定到旧连接，重连成功后收不到任何新消息，这与 `autoReconnect = true` 的设计目标直接冲突。另外，重复调用 `connect()` 时，正在连接中的第二次调用会被静默吞掉，状态机不可观测，必须改为显式失败。

严重程度: 高

[任务 #06]
定位锚点: `fun decode rejects oversized payload length()` in `MessageCodecTest.kt`

当前代码:
```kotlin
@Test
fun `decode rejects oversized payload length`() {
    val buffer = java.nio.ByteBuffer.allocate(Message.HEADER_SIZE).order(java.nio.ByteOrder.BIG_ENDIAN)
    buffer.putShort(Message.MAGIC)
    buffer.put(Message.VERSION)
    buffer.put(MessageType.DEVICE_DISCOVERY.value)
    buffer.put(0)
    buffer.putInt(Message.MAX_PAYLOAD_SIZE + 1)
    buffer.putLong(1L)

    val result = MessageCodec.decode(buffer.array())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("Payload too large") == true)
}
```

修改方案:
```kotlin
@Test
fun `decode rejects oversized payload length`() {
    val buffer = java.nio.ByteBuffer
        .allocate(Message.HEADER_SIZE)
        .order(java.nio.ByteOrder.BIG_ENDIAN)

    buffer.putShort(Message.MAGIC)
    buffer.put(Message.VERSION)
    buffer.put(MessageType.DEVICE_DISCOVERY.value)
    buffer.put(0)
    buffer.put(0)
    buffer.put(0)
    buffer.put(0)
    buffer.putInt(Message.MAX_PAYLOAD_SIZE + 1)
    buffer.putLong(1L)

    val result = MessageCodec.decode(buffer.array())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("Payload too large") == true)
}
```

修改逻辑: 协议头已改成 20 字节，但这个回归测试仍按旧偏移拼装字段，`payloadLength` 实际没有写入偏移量 8。测试现在属于“以错误报文碰巧测对错误分支”的假阳性，无法真正保护协议头布局。必须把 3 字节 padding 写回去，确保测试和生产协议完全一致。

严重程度: 中

3. 约束规则 (强制执行)
编码规范: 禁止保留“TODO 但返回 success”的伪实现；所有外部输入（网络报文、mDNS 属性、系统回调）必须做显式边界处理；公共 Flow 必须是稳定引用，禁止直接暴露会随内部连接对象更换而失效的临时 Flow。

禁止变动: 严禁修改现有协议头格式（20 字节）、`MessageType` 的枚举值、`Message` 的编解码语义、以及 `TcpClient`/`TcpServer`/`DeviceDiscovery`/`HotspotManager`/`WifiConnectionManager` 的公开方法名和入参结构。

依赖限制: 禁止新增任何第三方依赖；仅允许使用现有 Android SDK、Kotlin 标准库、`kotlinx.coroutines` 和当前测试依赖。

4. 验证标准
[ ] `:network:protocol:testDebugUnitTest` 必须全部通过，且 `MessageCodecTest` 对 20 字节头布局的断言不能再依赖错位构造。

[ ] `:network:transport:testDebugUnitTest` 必须通过，尤其要覆盖自动重连后 `TcpClient.messageFlow` 仍可持续收到消息。

[ ] 必须新增或完善 `DeviceDiscovery` 的自动化测试，覆盖“发现新增设备”和“设备丢失移除”两条路径，确保列表不会残留脏数据。

[ ] `HotspotManager` 和 `WifiConnectionManager` 不允许再出现“功能未实现但返回成功”的分支；状态流必须只在真实系统回调或明确失败时更新。

[ ] `:app:assembleDebug` 必须可构建，且修改后不得引入新的 Android API 级别不兼容问题。
