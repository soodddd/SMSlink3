package com.smslink.network.connection

import com.smslink.core.log.ILogger
import com.smslink.core.model.Connection as ConnectionModel
import com.smslink.core.model.ConnectionQuality
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.ConnectionType
import com.smslink.network.IConnectionManager
import com.smslink.network.encryption.IEncryption
import com.smslink.network.monitor.NetworkMonitor
import com.smslink.network.monitor.NetworkState
import com.smslink.network.monitor.NetworkType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket

/**
 * 连接管理器实现
 * 负责连接的建立、断开、状态监控、自动重连和链路自动切换
 * 支持 WiFi LAN、WiFi Hotspot 和 Bluetooth RFCOMM
 */
@Singleton
class ConnectionManagerImpl @Inject constructor(
    private val logger: ILogger,
    private val networkMonitor: NetworkMonitor,
    private val tcpConnectionFactory: TcpConnectionFactory,
    private val linkSelector: LinkSelector,
    private val encryption: IEncryption
) : IConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 活动连接映射
    private val activeConnections = ConcurrentHashMap<String, Connection>()

    // 当前链路类型映射
    private val currentLinkTypes = ConcurrentHashMap<String, LinkType>()

    // 连接状态流
    private val connectionStates = ConcurrentHashMap<String, MutableSharedFlow<ConnectionModel>>()

    // 链路切换通知流
    private val _linkSwitchFlow = MutableSharedFlow<LinkSwitchEvent>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 接收数据流
    private val _receiveDataFlow = MutableSharedFlow<Pair<String, ByteArray>>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 重连任务
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    init {
        startTcpListener()

        // 监听网络状态变化
        scope.launch {
            networkMonitor.observeNetworkState().collect { state ->
                handleNetworkStateChange(state)
            }
        }
    }

    override fun connect(deviceId: String, type: ConnectionType): Flow<ConnectionModel> = flow {
        logger.i(TAG, "Connecting to device: $deviceId, type: $type")

        // 如果已经连接，返回现有连接
        activeConnections[deviceId]?.let { connection ->
            if (connection.isConnected()) {
                logger.d(TAG, "Already connected to device: $deviceId")
                emit(createConnectionModel(deviceId, type, ConnectionState.CONNECTED, connection.getLinkType()))
                return@flow
            }
        }

        // 创建连接状态流
        val stateFlow = getOrCreateStateFlow(deviceId)

        // 发送连接中状态
        val connectingState = createConnectionModel(deviceId, type, ConnectionState.CONNECTING, null)
        emit(connectingState)
        stateFlow.emit(connectingState)

        try {
            // 直接按入参链路创建底层 TCP 连接，链路降级/切换仍由 LinkSelector 负责
            val connection = tcpConnectionFactory.create(deviceId, type)
            val linkType = mapConnectionTypeToLinkType(type)

            logger.i(TAG, "Selected link type: ${linkType.description} for device: $deviceId")

            // 建立连接
            connection.connect()

            // 保存连接和链路类型
            activeConnections[deviceId] = connection
            currentLinkTypes[deviceId] = linkType

            // 启动接收数据协程
            startReceivingData(deviceId, connection)

            // 启动心跳检测
            startHeartbeat(deviceId, connection)

            // 启动链路质量监控
            startLinkQualityMonitoring(deviceId)

            // 发送已连接状态
            val connectedState = createConnectionModel(deviceId, type, ConnectionState.CONNECTED, linkType)
            emit(connectedState)
            stateFlow.emit(connectedState)

            // 发送链路切换事件
            _linkSwitchFlow.emit(LinkSwitchEvent(deviceId, null, linkType, "Initial connection"))

            logger.i(TAG, "Successfully connected to device: $deviceId via ${linkType.description}")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect to device: $deviceId", e)

            // 发送失败状态
            val failedState = createConnectionModel(deviceId, type, ConnectionState.FAILED, null)
            emit(failedState)
            stateFlow.emit(failedState)

            // 启动自动重连
            scheduleReconnect(deviceId, type)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun disconnect(deviceId: String) {
        logger.i(TAG, "Disconnecting from device: $deviceId")

        // 取消重连任务
        reconnectJobs[deviceId]?.cancel()
        reconnectJobs.remove(deviceId)

        // 关闭连接
        activeConnections[deviceId]?.let { connection ->
            try {
                connection.close()
            } catch (e: Exception) {
                logger.e(TAG, "Error closing connection: $deviceId", e)
            }
        }

        activeConnections.remove(deviceId)
        currentLinkTypes.remove(deviceId)

        // 更新状态
        connectionStates[deviceId]?.emit(
            createConnectionModel(deviceId, ConnectionType.WIFI, ConnectionState.DISCONNECTED, null)
        )

        logger.i(TAG, "Disconnected from device: $deviceId")
    }

    override fun getConnectionState(deviceId: String): Flow<ConnectionModel> {
        return getOrCreateStateFlow(deviceId).asSharedFlow()
    }

    override fun getActiveConnections(): Flow<List<ConnectionModel>> = flow {
        while (currentCoroutineContext().isActive) {
            val connections = activeConnections.map { (deviceId, connection) ->
                createConnectionModel(
                    deviceId,
                    connection.getType(),
                    if (connection.isConnected()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    connection.getLinkType()
                )
            }
            emit(connections)
            delay(1000) // 每秒更新一次
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendData(deviceId: String, data: ByteArray): Boolean {
        val connection = activeConnections[deviceId]

        if (connection == null) {
            logger.w(TAG, "No connection found for device: $deviceId")
            return false
        }

        if (!connection.isConnected()) {
            logger.w(TAG, "Connection not established for device: $deviceId")
            return false
        }

        return try {
            connection.send(data)
            logger.d(TAG, "Sent ${data.size} bytes to device: $deviceId")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send data to device: $deviceId", e)

            // 连接可能已断开，触发链路切换或重连
            handleConnectionError(deviceId, connection.getType())
            false
        }
    }

    override fun receiveData(): Flow<Pair<String, ByteArray>> {
        return _receiveDataFlow.asSharedFlow()
    }

    /**
     * 观察链路切换事件
     */
    fun observeLinkSwitch(): Flow<LinkSwitchEvent> {
        return _linkSwitchFlow.asSharedFlow()
    }

    /**
     * 启动接收数据协程
     */
    private fun startReceivingData(deviceId: String, connection: Connection) {
        scope.launch {
            try {
                connection.receiveFlow().collect { data ->
                    logger.d(TAG, "Received ${data.size} bytes from device: $deviceId")
                    _receiveDataFlow.emit(deviceId to data)
                }
            } catch (e: Exception) {
                logger.e(TAG, "Error receiving data from device: $deviceId", e)
                handleConnectionError(deviceId, connection.getType())
            }
        }
    }

    private fun startTcpListener() {
        scope.launch {
            var serverSocket: ServerSocket? = null
            try {
                val sslContext = encryption.createSSLContext(LOCAL_TLS_CONTEXT_ID)
                serverSocket = (sslContext.serverSocketFactory.createServerSocket(DEFAULT_WIFI_PORT) as SSLServerSocket).apply {
                    reuseAddress = true
                    needClientAuth = false
                    enabledProtocols = TLS_PROTOCOLS
                    enabledCipherSuites = supportedCipherSuites.filter(::isSupportedTlsCipherSuite).toTypedArray()
                }
                logger.i(TAG, "TLS TCP listener started on port $DEFAULT_WIFI_PORT")

                while (isActive) {
                    val socket = withContext(Dispatchers.IO) { serverSocket.accept() }
                    val remoteAddress = socket.inetAddress?.hostAddress ?: "unknown"
                    val remotePort = socket.port
                    val inboundDeviceId = "tcp-$remoteAddress:$remotePort"
                    val connection = AcceptedTcpConnection(inboundDeviceId, socket, logger)

                    activeConnections[inboundDeviceId] = connection
                    currentLinkTypes[inboundDeviceId] = LinkType.WIFI_LAN

                    connectionStates[inboundDeviceId]?.emit(
                        createConnectionModel(
                            inboundDeviceId,
                            ConnectionType.WIFI,
                            ConnectionState.CONNECTED,
                            LinkType.WIFI_LAN
                        )
                    )

                    logger.i(TAG, "Accepted TCP connection from $inboundDeviceId")
                    startReceivingData(inboundDeviceId, connection)
                }
            } catch (e: Exception) {
                if (isActive) {
                    logger.e(TAG, "TCP listener failed", e)
                }
            } finally {
                runCatching { serverSocket?.close() }
            }
        }
    }

    /**
     * 启动心跳检测
     */
    private fun startHeartbeat(deviceId: String, connection: Connection) {
        scope.launch {
            while (isActive && connection.isConnected()) {
                delay(HEARTBEAT_INTERVAL)

                try {
                    // 发送心跳包
                    val heartbeat = "HEARTBEAT".toByteArray()
                    connection.send(heartbeat)
                    logger.d(TAG, "Sent heartbeat to device: $deviceId")
                } catch (e: Exception) {
                    logger.e(TAG, "Heartbeat failed for device: $deviceId", e)
                    handleConnectionError(deviceId, connection.getType())
                    break
                }
            }
        }
    }

    /**
     * 启动链路质量监控
     */
    private fun startLinkQualityMonitoring(deviceId: String) {
        scope.launch {
            while (isActive && activeConnections.containsKey(deviceId)) {
                delay(LINK_QUALITY_CHECK_INTERVAL)

                val connection = activeConnections[deviceId] ?: continue
                if (!connection.isConnected()) continue

                val currentLink = currentLinkTypes[deviceId] ?: continue

                // 评估当前链路质量
                val quality = linkSelector.evaluateLinkQuality(currentLink, null)

                // 如果质量差，尝试切换到更好的链路
                if (quality == LinkQuality.POOR) {
                    logger.w(TAG, "Poor link quality detected for device: $deviceId, attempting to switch")
                    attemptLinkSwitch(deviceId)
                }
            }
        }
    }

    /**
     * 尝试链路切换
     */
    private suspend fun attemptLinkSwitch(deviceId: String) {
        val currentConnection = activeConnections[deviceId] ?: return
        val currentLink = currentLinkTypes[deviceId] ?: return

        logger.i(TAG, "Attempting link switch for device: $deviceId from ${currentLink.description}")

        try {
            // 选择新的链路
            val newLink = linkSelector.selectBestLink(deviceId, null, null) ?: return
            val newConnection = tcpConnectionFactory.create(deviceId, mapLinkTypeToConnectionType(newLink))

            // 如果新链路与当前链路相同，跳过
            if (newLink == currentLink) {
                logger.d(TAG, "No better link available for device: $deviceId")
                return
            }

            // 如果新链路优先级更高，执行切换
            if (newLink.hasHigherPriorityThan(currentLink)) {
                logger.i(TAG, "Switching link for device: $deviceId from ${currentLink.description} to ${newLink.description}")

                // 建立新连接
                newConnection.connect()

                // 关闭旧连接
                currentConnection.close()

                // 更新连接
                activeConnections[deviceId] = newConnection
                currentLinkTypes[deviceId] = newLink

                // 重新启动接收和心跳
                startReceivingData(deviceId, newConnection)
                startHeartbeat(deviceId, newConnection)

                // 发送链路切换事件
                _linkSwitchFlow.emit(
                    LinkSwitchEvent(
                        deviceId,
                        currentLink,
                        newLink,
                        "Link quality degradation"
                    )
                )

                // 更新连接状态
                connectionStates[deviceId]?.emit(
                    createConnectionModel(deviceId, newConnection.getType(), ConnectionState.CONNECTED, newLink)
                )

                logger.i(TAG, "Successfully switched link for device: $deviceId to ${newLink.description}")
            }

        } catch (e: Exception) {
            logger.e(TAG, "Failed to switch link for device: $deviceId", e)
        }
    }

    /**
     * 处理连接错误
     */
    private fun handleConnectionError(deviceId: String, type: ConnectionType) {
        scope.launch {
            logger.w(TAG, "Handling connection error for device: $deviceId")

            // 更新状态为重连中
            connectionStates[deviceId]?.emit(
                createConnectionModel(deviceId, type, ConnectionState.RECONNECTING, null)
            )

            // 关闭旧连接
            activeConnections[deviceId]?.close()
            activeConnections.remove(deviceId)
            currentLinkTypes.remove(deviceId)

            // 尝试链路降级或重连
            scheduleReconnect(deviceId, type)
        }
    }

    /**
     * 调度重连
     */
    private fun scheduleReconnect(deviceId: String, type: ConnectionType) {
        // 取消现有重连任务
        reconnectJobs[deviceId]?.cancel()

        val job = scope.launch {
            var attempt = 0

            while (isActive && attempt < MAX_RECONNECT_ATTEMPTS) {
                attempt++
                val delay = calculateBackoffDelay(attempt)

                logger.i(TAG, "Reconnect attempt $attempt for device: $deviceId in ${delay}ms")
                delay(delay)

                try {
                    // 尝试重连（会自动选择最佳链路）
                    connect(deviceId, type).collect { connection ->
                        if (connection.state == ConnectionState.CONNECTED) {
                            logger.i(TAG, "Reconnected to device: $deviceId")
                            cancel()
                        }
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Reconnect attempt $attempt failed for device: $deviceId", e)
                }
            }

            logger.w(TAG, "Max reconnect attempts reached for device: $deviceId")
        }

        reconnectJobs[deviceId] = job
    }

    /**
     * 计算退避延迟
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        return minOf(
            INITIAL_RECONNECT_DELAY * (1 shl (attempt - 1)),
            MAX_RECONNECT_DELAY
        )
    }

    private fun mapConnectionTypeToLinkType(type: ConnectionType): LinkType {
        return when (type) {
            ConnectionType.WIFI -> LinkType.WIFI_LAN
            ConnectionType.HOTSPOT -> LinkType.WIFI_HOTSPOT
            ConnectionType.BLUETOOTH -> LinkType.BLUETOOTH
        }
    }

    private fun mapLinkTypeToConnectionType(linkType: LinkType): ConnectionType {
        return when (linkType) {
            LinkType.WIFI_LAN -> ConnectionType.WIFI
            LinkType.WIFI_HOTSPOT -> ConnectionType.HOTSPOT
            LinkType.BLUETOOTH -> ConnectionType.BLUETOOTH
        }
    }

    /**
     * 处理网络状态变化
     */
    private fun handleNetworkStateChange(state: NetworkState) {
        when (state) {
            is NetworkState.Available -> {
                logger.i(TAG, "Network available: ${state.type}")

                // 网络可用时，重新评估所有连接的链路
                activeConnections.keys.forEach { deviceId ->
                    scope.launch {
                        val connection = activeConnections[deviceId]
                        if (connection != null && connection.isConnected()) {
                            // 尝试切换到更好的链路
                            attemptLinkSwitch(deviceId)
                        } else if (connection != null && !connection.isConnected()) {
                            // 如果未连接，尝试重连
                            scheduleReconnect(deviceId, connection.getType())
                        }
                    }
                }
            }
            is NetworkState.Lost -> {
                logger.w(TAG, "Network lost, connections may be affected")
            }
        }
    }

    /**
     * 获取或创建状态流
     */
    private fun getOrCreateStateFlow(deviceId: String): MutableSharedFlow<ConnectionModel> {
        return connectionStates.getOrPut(deviceId) {
            MutableSharedFlow(
                replay = 1,
                extraBufferCapacity = 10,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
    }

    /**
     * 创建连接模型对象
     */
    private fun createConnectionModel(
        deviceId: String,
        type: ConnectionType,
        state: ConnectionState,
        linkType: LinkType?
    ): ConnectionModel {
        return ConnectionModel(
            deviceId = deviceId,
            type = type,
            state = state,
            quality = calculateConnectionQuality(deviceId, linkType),
            lastActivity = System.currentTimeMillis()
        )
    }

    /**
     * 计算连接质量
     */
    private fun calculateConnectionQuality(deviceId: String, linkType: LinkType?): ConnectionQuality {
        val connection = activeConnections[deviceId]

        if (connection == null || !connection.isConnected()) {
            return ConnectionQuality.POOR
        }

        // 根据链路类型估算质量
        return when (linkType) {
            LinkType.WIFI_LAN -> ConnectionQuality.EXCELLENT
            LinkType.WIFI_HOTSPOT -> ConnectionQuality.GOOD
            LinkType.BLUETOOTH -> ConnectionQuality.FAIR
            null -> ConnectionQuality.POOR
        }
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val DEFAULT_WIFI_PORT = 1716
        private const val LOCAL_TLS_CONTEXT_ID = "local_tls_listener"
        private const val HEARTBEAT_INTERVAL = 30_000L // 30秒
        private const val LINK_QUALITY_CHECK_INTERVAL = 60_000L // 60秒
        private const val INITIAL_RECONNECT_DELAY = 1_000L // 1秒
        private const val MAX_RECONNECT_DELAY = 60_000L // 60秒
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        private fun isSupportedTlsCipherSuite(cipherSuite: String): Boolean {
            return cipherSuite.startsWith("TLS_AES_") ||
                cipherSuite.startsWith("TLS_CHACHA20_") ||
                cipherSuite.contains("_ECDHE_")
        }
    }
}

/**
 * 链路切换事件
 */
data class LinkSwitchEvent(
    val deviceId: String,
    val fromLink: LinkType?,
    val toLink: LinkType,
    val reason: String
)
