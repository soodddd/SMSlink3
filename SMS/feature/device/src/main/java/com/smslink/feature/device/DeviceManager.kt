package com.smslink.feature.device

import android.content.Context
import com.smslink.core.model.DeviceCapability
import com.smslink.core.model.DeviceInfo
import com.smslink.core.model.DeviceType
import com.smslink.core.preferences.DevicePreferences
import com.smslink.network.discovery.DeviceDiscovery
import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import com.smslink.network.transport.Connection
import com.smslink.network.transport.TcpClient
import com.smslink.network.transport.TcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 设备管理器
 * 负责设备配对、角色管理、连接状态监控
 */
class DeviceManager(
    private val context: Context,
    private val repository: DeviceRepository,
    private val devicePreferences: DevicePreferences
) {

    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val deviceDiscovery = DeviceDiscovery(context)
    val pairingManager = PairingManager()

    private var tcpServer: TcpServer? = null
    private var tcpClient: TcpClient? = null

    private val _currentRole = MutableStateFlow(
        runBlocking {
            when (devicePreferences.getCurrentRole()) {
                DeviceRole.PRIMARY.name -> DeviceRole.PRIMARY
                DeviceRole.SECONDARY.name -> DeviceRole.SECONDARY
                else -> DeviceRole.UNPAIRED
            }
        }
    )
    val currentRole: StateFlow<DeviceRole> = _currentRole.asStateFlow()

    private val _connectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _pairedDevice = MutableStateFlow<DeviceInfo?>(null)
    val pairedDevice: StateFlow<DeviceInfo?> = _pairedDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Message> = _incomingMessages.asSharedFlow()

    private var discoveryJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var persistenceJob: Job? = null
    private var pendingPairingCode: String? = null

    // 使用持久化的设备 ID
    private val localDeviceId by lazy {
        runBlocking { devicePreferences.getOrCreateLocalDeviceId() }
    }

    // 消息处理回调（由其他模块注册）
    private val messageHandlers = mutableMapOf<MessageType, (Message) -> Unit>()

    /**
     * 注册消息处理器
     */
    fun registerMessageHandler(messageType: MessageType, handler: (Message) -> Unit) {
        messageHandlers[messageType] = handler
    }

    /**
     * 取消注册消息处理器
     */
    fun unregisterMessageHandler(messageType: MessageType) {
        messageHandlers.remove(messageType)
    }

    /**
     * 配对状态
     */
    val pairingState: StateFlow<PairingState> = pairingManager.pairingState

    /**
     * 获取已配对设备列表
     */
    fun getPairedDevices(): Flow<List<DeviceInfo>> = repository.getPairedDevices()

    /**
     * 生成配对码
     */
    fun generatePairingCode(): String = pairingManager.generatePairingCode()

    fun enterPairingCode(code: String) {
        pendingPairingCode = code
        pairingManager.startPairing()
    }

    /**
     * 取消配对
     */
    fun cancelPairing() {
        pairingManager.reset()
    }

    /**
     * 根据设备 ID 连接设备
     */
    suspend fun connectToDevice(deviceId: String) {
        val device = repository.getDeviceById(deviceId)
            ?: error("Unknown deviceId: $deviceId")
        connectToDevice(device)

        pendingPairingCode?.let { code ->
            val sent = sendPairingRequest(device, code)
            if (!sent) {
                pairingManager.pairingFailed("发送配对请求失败")
            }
            pendingPairingCode = null
        }
    }

    /**
     * 取消配对设备
     */
    suspend fun unpairDevice(deviceId: String) {
        repository.updatePairingStatus(deviceId, false)
        if (pairedDevice.value?.deviceId == deviceId) {
            disconnect()
            _pairedDevice.value = null
        }
    }

    /**
     * 断开设备连接
     */
    fun disconnectDevice(deviceId: String? = null) {
        if (deviceId == null || pairedDevice.value?.deviceId == deviceId) {
            disconnect()
        }
    }

    /**
     * 发送消息到已配对设备
     */
    suspend fun sendMessageToPairedDevice(message: Message) {
        tcpClient?.let {
            it.sendMessage(message)
            return
        }
        tcpServer?.getAllConnections()?.firstOrNull()?.sendMessage(message)
            ?: error("No active paired connection")
    }

    suspend fun sendMessage(message: Message) {
        sendMessageToPairedDevice(message)
    }

    /**
     * 初始化设备管理器
     */
    fun initialize(role: DeviceRole) {
        _currentRole.value = role
        scope.launch {
            devicePreferences.setCurrentRole(role.name)
        }

        // 启动持久化监听
        startPersistenceMonitoring()

        when (role) {
            DeviceRole.PRIMARY -> initializePrimary()
            DeviceRole.SECONDARY -> initializeSecondary()
            DeviceRole.UNPAIRED -> {}
        }
    }

    /**
     * 启动持久化监听
     */
    private fun startPersistenceMonitoring() {
        persistenceJob?.cancel()
        persistenceJob = scope.launch {
            // 加载已配对设备
            repository.getPairedDevices().collect { devices ->
                if (devices.isNotEmpty()) {
                    _pairedDevice.value = devices.first()
                }
            }
        }
    }

    /**
     * 初始化主设备（手机端）
     */
    private fun initializePrimary() {
        // 启动 TCP 服务器
        tcpServer = TcpServer(port = 8888, maxConnections = 1).apply {
            start()
        }

        // 注册 mDNS 服务
        val deviceInfo = DeviceInfo(
            deviceId = localDeviceId,
            deviceName = android.os.Build.MODEL,
            deviceType = DeviceType.PHONE,
            capabilities = emptySet(),
            ipAddress = null,
            port = 8888
        )
        deviceDiscovery.register(deviceInfo)

        // 监听连接事件
        monitorServerConnections()
    }

    /**
     * 初始化副设备（电脑端）
     */
    private fun initializeSecondary() {
        // 启动设备发现
        startDiscovery()
    }

    /**
     * 开始发现设备
     */
    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            deviceDiscovery.startDiscovery().collect { devices ->
                // 过滤掉本地设备
                val filtered = devices.filter { it.deviceId != localDeviceId }
                _discoveredDevices.value = filtered

                // 持久化发现的设备
                filtered.forEach { device ->
                    repository.saveDevice(device, isPaired = false)
                    repository.updateLastSeen(device.deviceId)
                }
            }
        }
    }

    /**
     * 停止发现设备
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        deviceDiscovery.stopDiscovery()
    }

    /**
     * 连接到设备
     */
    suspend fun connectToDevice(device: DeviceInfo) {
        val ipAddress = device.ipAddress
        if (ipAddress == null) {
            _connectionState.value = DeviceConnectionState.Failed(
                IllegalArgumentException("设备 IP 地址为空")
            )
            return
        }

        _connectionState.value = DeviceConnectionState.Connecting

        try {
            tcpClient = TcpClient(
                host = ipAddress,
                port = device.port,
                autoReconnect = true
            )

            tcpClient?.connect()
            _connectionState.value = DeviceConnectionState.Connected(device.deviceId)
            _pairedDevice.value = device

            // 持久化配对设备
            repository.saveDevice(device, isPaired = true)
            repository.updatePairingStatus(device.deviceId, isPaired = true)

            monitorClientConnection()
        } catch (e: Exception) {
            _connectionState.value = DeviceConnectionState.Failed(e)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connectionMonitorJob?.cancel()
        tcpClient?.disconnect()
        tcpClient = null
        _connectionState.value = DeviceConnectionState.Disconnected
    }

    /**
     * 监听服务器连接事件
     */
    private fun monitorServerConnections() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            tcpServer?.connectionFlow?.collect { event ->
                when (event) {
                    is TcpServer.ConnectionEvent.ClientConnected -> {
                        _connectionState.value = DeviceConnectionState.Connected(event.connectionId)

                        // 监听所有消息
                        launch {
                            event.connection.messageFlow.collect { message ->
                                // 分发到 incomingMessages
                                _incomingMessages.emit(message)

                                // 处理配对消息
                                handlePairingMessage(message, event.connection)
                            }
                        }
                    }
                    is TcpServer.ConnectionEvent.ClientDisconnected -> {
                        _connectionState.value = DeviceConnectionState.Disconnected
                    }
                }
            }
        }
    }

    /**
     * 处理配对消息
     */
    private suspend fun handlePairingMessage(message: Message, connection: Connection?) {
        when (message.type) {
            MessageType.DEVICE_PAIR_REQUEST -> {
                val request = pairingManager.parsePairingRequest(message) ?: return

                // 验证配对码
                if (pairingManager.verifyPairingCode(request.code)) {
                    pairingManager.pairingSuccess(request.deviceId)

                    // 发送接受响应
                    val response = pairingManager.createPairingResponse(
                        accepted = true,
                        deviceId = localDeviceId
                    )
                    connection?.sendMessage(response)

                    // 保存配对设备（包含正确的 IP 和端口）
                    val deviceInfo = DeviceInfo(
                        deviceId = request.deviceId,
                        deviceName = request.deviceName,
                        deviceType = DeviceType.PHONE,
                        capabilities = setOf(DeviceCapability.NOTIFICATION),
                        ipAddress = connection?.remoteAddress,
                        port = connection?.remotePort ?: TcpServer.DEFAULT_PORT
                    )
                    repository.saveDevice(deviceInfo, isPaired = true)
                    _pairedDevice.value = deviceInfo
                } else {
                    // 发送拒绝响应
                    val response = pairingManager.createPairingResponse(
                        accepted = false,
                        deviceId = localDeviceId
                    )
                    connection?.sendMessage(response)
                }
            }
            MessageType.DEVICE_PAIR_RESPONSE -> {
                val response = pairingManager.parsePairingResponse(message) ?: return

                if (response.accepted) {
                    pairingManager.pairingSuccess(response.deviceId)
                } else {
                    pairingManager.pairingFailed("配对被拒绝")
                }
            }
            else -> {
                // 分发到注册的消息处理器
                dispatchMessage(message)
            }
        }
    }

    /**
     * 分发消息到注册的处理器
     */
    private fun dispatchMessage(message: Message) {
        messageHandlers[message.type]?.invoke(message)
    }

    /**
     * 监听客户端连接状态
     */
    private fun monitorClientConnection() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            tcpClient?.connectionStateFlow?.collect { state ->
                when (state) {
                    is TcpClient.ClientState.Connected -> {
                        _connectionState.value = DeviceConnectionState.Connected(
                            _pairedDevice.value?.deviceId ?: ""
                        )

                        // 监听所有消息
                        launch {
                            tcpClient?.messageFlow?.collect { message ->
                                // 分发到 incomingMessages
                                _incomingMessages.emit(message)

                                // 客户端模式下，配对消息通过 messageFlow 接收
                                // 但没有 Connection 对象，使用 null
                                handlePairingMessage(message, null)
                            }
                        }
                    }
                    is TcpClient.ClientState.Disconnected -> {
                        _connectionState.value = DeviceConnectionState.Disconnected
                    }
                    is TcpClient.ClientState.Failed -> {
                        _connectionState.value = DeviceConnectionState.Failed(state.cause)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 切换设备角色
     */
    fun switchRole(newRole: DeviceRole) {
        // 清理当前角色资源
        cleanup()

        // 初始化新角色
        initialize(newRole)
    }

    /**
     * 发送配对请求
     */
    suspend fun sendPairingRequest(device: DeviceInfo, code: String): Boolean {
        val client = tcpClient ?: return false

        pairingManager.startPairing()

        val request = pairingManager.createPairingRequest(
            deviceId = localDeviceId,
            deviceName = android.os.Build.MODEL,
            code = code
        )

        return try {
            client.sendMessage(request)
            true
        } catch (e: Exception) {
            pairingManager.pairingFailed(e.message ?: "发送失败")
            false
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        discoveryJob?.cancel()
        connectionMonitorJob?.cancel()
        persistenceJob?.cancel()

        tcpServer?.stop()
        tcpServer = null

        tcpClient?.disconnect()
        tcpClient = null

        deviceDiscovery.stopDiscovery()
        deviceDiscovery.unregister()
    }

    /**
     * 关闭设备管理器
     */
    fun close() {
        cleanup()
    }
}
