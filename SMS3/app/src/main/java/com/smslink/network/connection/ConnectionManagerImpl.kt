package com.smslink.network.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.google.gson.JsonObject
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Connection as ConnectionModel
import com.smslink.core.model.ConnectionQuality
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.ConnectionType
import com.smslink.network.IConnectionManager
import com.smslink.network.encryption.CertificateCodec
import com.smslink.network.encryption.IEncryption
import com.smslink.network.monitor.NetworkMonitor
import com.smslink.network.monitor.NetworkState
import com.smslink.network.monitor.NetworkType
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.security.AuthProtocol
import com.smslink.device.DevicePairingImpl
import com.smslink.security.DeviceIdentityStore
import com.smslink.sync.CatchupSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
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

    /** Hilt supplies these after construction; direct JVM tests intentionally
     * leave them unset and exercise the transport without Android auth. */
    @Inject
    lateinit var identityStore: DeviceIdentityStore

    @Inject
    lateinit var deviceDao: DeviceDao

    @Inject
    lateinit var devicePairing: DevicePairingImpl

    @Inject
    lateinit var connectionPolicyStore: ConnectionPolicyStore

    // 使用懒加载避免循环依赖
    @Inject
    lateinit var catchupSyncManager: dagger.Lazy<CatchupSyncManager>

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
    private val linkQualityJobs = ConcurrentHashMap<String, Job>()
    // Serialize outgoing connect/disconnect operations per peer. Without
    // this, two callers can complete handshakes concurrently and one can
    // overwrite the other's live connection after it was already installed.
    private val deviceConnectLocks = ConcurrentHashMap<String, Mutex>()
    private var bluetoothListenerJob: Job? = null
    private val inboundHandshakeSlots = Semaphore(MAX_INBOUND_HANDSHAKES)

    init {
        startTcpListener()
        startBluetoothListener()

        // 监听网络状态变化
        scope.launch {
            networkMonitor.observeNetworkState().collect { state ->
                handleNetworkStateChange(state)
            }
        }
    }

    override fun connect(deviceId: String, type: ConnectionType): Flow<ConnectionModel> =
        connectInternal(deviceId, type, scheduleReconnectOnFailure = true)

    private fun connectInternal(
        deviceId: String,
        type: ConnectionType,
        scheduleReconnectOnFailure: Boolean
    ): Flow<ConnectionModel> = flow {
        val lock = deviceConnectLocks.getOrPut(deviceId) { Mutex() }
        lock.withLock {
            logger.i(TAG, "Connecting to device: $deviceId, type: $type")

            // 如果已经连接，返回现有连接
            activeConnections[deviceId]?.let { connection ->
                if (connection.isConnected()) {
                    logger.d(TAG, "Already connected to device: $deviceId")
                    emit(createConnectionModel(deviceId, type, ConnectionState.CONNECTED, connection.getLinkType()))
                    return@withLock
                }
            }

            // 创建连接状态流
            val stateFlow = getOrCreateStateFlow(deviceId)

            // 发送连接中状态
            val connectingState = createConnectionModel(deviceId, type, ConnectionState.CONNECTING, null)
            emit(connectingState)
            stateFlow.emit(connectingState)

            var connection: Connection? = null
            var installed = false
            try {
                // Start the RFCOMM accept loop lazily as well. This covers the
                // case where Bluetooth was disabled when the application started.
                if (type == ConnectionType.BLUETOOTH) startBluetoothListener()

                // The factory only creates a connection from a paired device's
                // persisted endpoint; no guessed address is allowed here.
                connection = tcpConnectionFactory.create(deviceId, type)
                val linkType = mapConnectionTypeToLinkType(type)

                logger.i(TAG, "Selected link type: ${linkType.description} for device: $deviceId")

                // 建立底层连接，再完成应用层身份握手。
                connection.connect()
                if (!authenticateOutgoing(connection, deviceId)) {
                    connection.close()
                    throw SecurityException("Peer authentication failed for $deviceId")
                }

                // An inbound connection may have won the race while this
                // outgoing handshake was running. Keep the already-live link
                // instead of silently replacing it with a second connection.
                val existingLive = activeConnections[deviceId]
                if (existingLive != null && existingLive.isConnected()) {
                    connection.close()
                    emit(
                        createConnectionModel(
                            deviceId,
                            existingLive.getType(),
                            ConnectionState.CONNECTED,
                            existingLive.getLinkType()
                        )
                    )
                    return@withLock
                }

                // 保存连接和链路类型
                val previous = activeConnections.put(deviceId, connection)
                installed = true
                currentLinkTypes[deviceId] = linkType
                if (previous != null && previous !== connection) {
                    previous.close()
                }
                markDeviceConnection(deviceId, true)
                if (scheduleReconnectOnFailure) {
                    reconnectJobs.remove(deviceId)?.cancel()
                } else {
                    // This invocation is made by the reconnect worker itself;
                    // remove its stale map entry without cancelling the
                    // currently executing worker.
                    reconnectJobs.remove(deviceId)
                }

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

                // 触发补同步
                if (::catchupSyncManager.isInitialized) scope.launch {
                    try {
                        catchupSyncManager.get().performCatchupSync(deviceId)
                        logger.i(TAG, "Catchup sync triggered for device: $deviceId")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to trigger catchup sync", e)
                    }
                }

                logger.i(TAG, "Successfully connected to device: $deviceId via ${linkType.description}")

            } catch (e: CancellationException) {
                // A caller commonly uses first { CONNECTED } and cancels the
                // flow immediately after receiving that state. Keep an
                // already-installed connection alive, but close a socket that
                // was cancelled during handshake and never became active.
                if (!installed) runCatching { connection?.close() }
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Failed to connect to device: $deviceId", e)
                if (installed && connection != null && activeConnections[deviceId] === connection) {
                    activeConnections.remove(deviceId, connection)
                    currentLinkTypes.remove(deviceId)
                    linkQualityJobs.remove(deviceId)?.cancel()
                }
                runCatching { connection?.close() }
                markDeviceConnection(deviceId, false)

                // 发送失败状态
                val failedState = createConnectionModel(deviceId, type, ConnectionState.FAILED, null)
                emit(failedState)
                stateFlow.emit(failedState)

                // Do not recursively schedule a new reconnect worker from
                // inside the worker that is already performing this attempt.
                if (scheduleReconnectOnFailure) {
                    scheduleReconnect(deviceId, type)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun disconnect(deviceId: String) {
        val lock = deviceConnectLocks.getOrPut(deviceId) { Mutex() }
        lock.withLock {
            logger.i(TAG, "Disconnecting from device: $deviceId")

            val previousType = activeConnections[deviceId]?.getType() ?: ConnectionType.WIFI

            // 取消重连任务
            reconnectJobs[deviceId]?.cancel()
            reconnectJobs.remove(deviceId)
            linkQualityJobs.remove(deviceId)?.cancel()

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
            markDeviceConnection(deviceId, false)

            // 更新状态
            connectionStates[deviceId]?.emit(
                createConnectionModel(deviceId, previousType, ConnectionState.DISCONNECTED, null)
            )

            logger.i(TAG, "Disconnected from device: $deviceId")
        }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send data to device: $deviceId", e)

            // 连接可能已断开，触发链路切换或重连
            handleConnectionError(deviceId, connection.getType(), connection)
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
                if (activeConnections[deviceId] === connection && !connection.isConnected()) {
                    handleConnectionError(deviceId, connection.getType(), connection)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Error receiving data from device: $deviceId", e)
                handleConnectionError(deviceId, connection.getType(), connection)
            }
        }
    }

    private fun authDependenciesReady(): Boolean =
        ::identityStore.isInitialized && ::deviceDao.isInitialized && ::devicePairing.isInitialized

    private suspend fun authenticateOutgoing(connection: Connection, deviceId: String): Boolean {
        if (!authDependenciesReady()) return true

        val peer = deviceDao.getById(deviceId)
            ?: return false
        val peerKey = peer.publicKey?.takeIf { it.isNotBlank() }
            ?: return false
        val hello = AuthProtocol.createHello(
            identity = identityStore,
            targetDevice = deviceId,
            sourceName = android.os.Build.MODEL.orEmpty().ifBlank { "Android device" },
            pairingToken = devicePairing.getPairingToken(deviceId),
            tlsCertificate = runCatching {
                CertificateCodec.encode(encryption.generateSelfSignedCertificate())
            }.getOrNull()
        )
        connection.send(AuthProtocol.encodeHello(hello))

        val responseBytes = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            connection.receiveOne()
        } ?: return false
        val response = AuthProtocol.decodeResponse(responseBytes) ?: return false
        val verified = AuthProtocol.verifyResponse(
            response = response,
            expectedSource = deviceId,
            expectedTarget = identityStore.deviceId(),
            expectedNonce = hello.nonce,
            expectedPublicKey = peerKey,
            identity = identityStore
        )
        if (!verified || !response.accepted) return false

        devicePairing.clearPairingToken(deviceId)
        return true
    }

    private suspend fun authenticateAndRegisterIncoming(
        connection: Connection,
        remoteAddress: String?,
        inboundType: ConnectionType
    ) {
        try {
            // Complete the transport handshake before reading the framed
            // application-level authentication message. Accepted TCP
            // connections are TLS sockets; RFCOMM connections simply report
            // their already-connected state here.
            connection.connect()

            if (!authDependenciesReady()) {
                connection.close()
                return
            }

            val hello = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                AuthProtocol.decodeHello(connection.receiveOne())
            }
            val localId = identityStore.deviceId()
            val basicValid = hello != null &&
                hello.targetDevice == localId &&
                hello.sourceName.isNotBlank() && hello.sourceName.length <= 128
            val remoteCertificate = hello?.tlsCertificate?.let(CertificateCodec::decode)
            val certificateValid = hello?.tlsCertificate.isNullOrBlank() ||
                (remoteCertificate != null && encryption.verifyCertificate(remoteCertificate))
            val existingCertificateMatches = if (hello != null && remoteCertificate != null) {
                val pinned = encryption.getDeviceCertificate(hello.sourceDevice)
                pinned == null || pinned.encoded.contentEquals(remoteCertificate.encoded)
            } else {
                true
            }
            val signed = basicValid && certificateValid && existingCertificateMatches &&
                AuthProtocol.verifyHello(hello!!, identityStore)

            val existing = if (hello != null) deviceDao.getById(hello.sourceDevice) else null
            val accepted = if (signed && hello != null) {
                if (existing?.isPaired == true && !existing.publicKey.isNullOrBlank()) {
                    // A legacy UI-only pairing may have left an isPaired row
                    // without the signed identity key.  A fully keyed row is
                    // immutable and can only authenticate with that exact key.
                    existing.publicKey == hello.publicKey
                } else {
                    // If the old row has no key, require the one-time QR
                    // capability and let acceptIncomingPairing upgrade it to
                    // the current signed-key/TLS-certificate format.
                    val token = hello.pairingToken
                    !token.isNullOrBlank() && devicePairing.acceptIncomingPairing(
                        sourceDeviceId = hello.sourceDevice,
                        sourceDeviceName = hello.sourceName,
                        sourcePublicKey = hello.publicKey,
                        pairingToken = token,
                        ipAddress = remoteAddress.takeIf {
                            inboundType == ConnectionType.WIFI || inboundType == ConnectionType.HOTSPOT
                        },
                        port = DEFAULT_TLS_PORT,
                        bluetoothAddress = remoteAddress.takeIf {
                            inboundType == ConnectionType.BLUETOOTH
                        },
                        tlsCertificate = hello.tlsCertificate
                    )
                }
            } else {
                false
            }

            val response = AuthProtocol.createResponse(
                identity = identityStore,
                targetDevice = hello?.sourceDevice.orEmpty(),
                nonce = hello?.nonce.orEmpty(),
                accepted = accepted,
                message = if (accepted) null else "Peer is not paired or authentication failed"
            )
            connection.send(AuthProtocol.encodeResponse(response))

            if (!accepted || hello == null) {
                connection.close()
                return
            }

            val deviceId = hello.sourceDevice
            val lock = deviceConnectLocks.getOrPut(deviceId) { Mutex() }
            lock.withLock {
                if (remoteCertificate != null) {
                    try {
                        encryption.saveDeviceCertificate(deviceId, remoteCertificate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(TAG, "Failed to pin inbound TLS certificate: ${e.message}")
                        connection.close()
                        return
                    }
                }
                // Reload after acceptIncomingPairing. The initial row may have
                // been unpaired; updating that stale object would otherwise
                // overwrite the just-paired record with isPaired=false.
                deviceDao.getById(deviceId)?.let { pairedDevice ->
                    val endpointUpdated = pairedDevice.copy(
                        ipAddress = remoteAddress.takeIf {
                            inboundType == ConnectionType.WIFI || inboundType == ConnectionType.HOTSPOT
                        } ?: pairedDevice.ipAddress,
                        port = if (inboundType == ConnectionType.WIFI || inboundType == ConnectionType.HOTSPOT) {
                            DEFAULT_TLS_PORT
                        } else {
                            pairedDevice.port
                        },
                        bluetoothAddress = remoteAddress.takeIf {
                            inboundType == ConnectionType.BLUETOOTH
                        } ?: pairedDevice.bluetoothAddress,
                        isConnected = true,
                        lastSeen = System.currentTimeMillis()
                    )
                    try {
                        deviceDao.update(endpointUpdated)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(TAG, "Failed to persist inbound endpoint: ${e.message}")
                    }
                }
                reconnectJobs.remove(deviceId)?.cancel()
                linkQualityJobs[deviceId]?.cancel()
                activeConnections.remove(deviceId)?.close()
                activeConnections[deviceId] = connection
                val linkType = mapConnectionTypeToLinkType(inboundType)
                currentLinkTypes[deviceId] = linkType
                markDeviceConnection(deviceId, true)
                getOrCreateStateFlow(deviceId).emit(
                    createConnectionModel(
                        deviceId,
                        inboundType,
                        ConnectionState.CONNECTED,
                        linkType
                    )
                )
                logger.i(TAG, "Accepted authenticated ${inboundType.name} connection from $deviceId")
                startHeartbeat(deviceId, connection)
                startReceivingData(deviceId, connection)
                startLinkQualityMonitoring(deviceId)
            }
        } catch (e: CancellationException) {
            connection.close()
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Rejected inbound TCP connection: ${e.message}")
            connection.close()
        }
    }

    private fun markDeviceConnection(deviceId: String, connected: Boolean) {
        if (!authDependenciesReady()) return
        scope.launch {
            try {
                if (!connected && activeConnections[deviceId]?.isConnected() == true) {
                    // A stale failure can finish after a replacement link was
                    // installed. Never persist that old failure over the
                    // currently live connection.
                    return@launch
                }
                val device = deviceDao.getById(deviceId) ?: return@launch
                deviceDao.update(
                    device.copy(
                        isConnected = connected,
                        lastSeen = if (connected) System.currentTimeMillis() else device.lastSeen
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Failed to persist connection state: ${e.message}")
            }
        }
    }

    private fun launchInboundHandshake(
        connection: Connection,
        remoteAddress: String?,
        inboundType: ConnectionType
    ) {
        if (!inboundHandshakeSlots.tryAcquire()) {
            logger.w(TAG, "Rejecting inbound handshake: capacity reached")
            connection.close()
            return
        }

        scope.launch {
            try {
                authenticateAndRegisterIncoming(connection, remoteAddress, inboundType)
            } finally {
                inboundHandshakeSlots.release()
            }
        }
    }

    private fun localDeviceId(): String =
        if (::identityStore.isInitialized) identityStore.deviceId() else "local"

    private fun startTcpListener() {
        scope.launch {
            while (isActive) {
                var serverSocket: ServerSocket? = null
                try {
                    val sslContext = encryption.createSSLContext(LOCAL_TLS_CONTEXT_ID)
                    serverSocket = (sslContext.serverSocketFactory.createServerSocket(DEFAULT_WIFI_PORT) as SSLServerSocket).apply {
                        reuseAddress = true
                        // TLS provides confidentiality; the signed application
                        // handshake below performs device authentication and key
                        // pinning without requiring a CA or a system certificate.
                        needClientAuth = false
                        enabledProtocols = TLS_PROTOCOLS
                        enabledCipherSuites = supportedCipherSuites.filter(::isSupportedTlsCipherSuite).toTypedArray()
                    }
                    logger.i(TAG, "TLS TCP listener started on port $DEFAULT_WIFI_PORT")

                    while (isActive) {
                        val socket = withContext(Dispatchers.IO) { serverSocket.accept() }
                        val connection = try {
                            AcceptedTcpConnection("pending", socket, logger)
                        } catch (e: Exception) {
                            runCatching { socket.close() }
                            logger.w(TAG, "Rejected TCP socket that could not be opened")
                            continue
                        }
                        launchInboundHandshake(
                            connection = connection,
                            remoteAddress = socket.inetAddress?.hostAddress,
                            inboundType = ConnectionType.WIFI
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        logger.e(TAG, "TCP listener failed; retrying", e)
                        delay(TCP_RETRY_DELAY_MS)
                    }
                } finally {
                    runCatching { serverSocket?.close() }
                }
            }
        }
    }

    /**
     * Accepts classic Bluetooth SPP connections. BLE GATT is deliberately
     * kept as the control/pairing plane; RFCOMM is the light-message fallback
     * for already bonded and authenticated devices.
     */
    @SuppressLint("MissingPermission")
    private fun startBluetoothListener() {
        if (bluetoothListenerJob?.isActive == true) return

        bluetoothListenerJob = scope.launch {
            // Keep the accept loop alive while Bluetooth is disabled. A user
            // can grant permission or enable Bluetooth after the app starts;
            // the listener must recover without requiring a process restart.
            while (isActive) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null || !adapter.isEnabled) {
                        logger.d(TAG, "Bluetooth RFCOMM listener waiting for an enabled adapter")
                        delay(BLUETOOTH_RETRY_DELAY_MS)
                        continue
                    }

                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                        RFCOMM_SERVICE_NAME,
                        RFCOMM_UUID
                    )
                    logger.i(TAG, "Bluetooth RFCOMM listener started")

                    while (isActive) {
                        val socket = withContext(Dispatchers.IO) { serverSocket.accept() }
                        val remoteAddress = runCatching { socket.remoteDevice.address }.getOrNull()
                        val connection = try {
                            AcceptedBluetoothConnection("pending", socket, logger)
                        } catch (e: Exception) {
                            runCatching { socket.close() }
                            logger.w(TAG, "Rejected RFCOMM socket that could not be opened: ${e.message}")
                            continue
                        }
                        launchInboundHandshake(
                            connection = connection,
                            remoteAddress = remoteAddress,
                            inboundType = ConnectionType.BLUETOOTH
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    logger.w(TAG, "Bluetooth RFCOMM listener permission unavailable: ${e.message}")
                    delay(BLUETOOTH_RETRY_DELAY_MS)
                } catch (e: Exception) {
                    if (isActive) {
                        logger.w(TAG, "Bluetooth RFCOMM listener stopped: ${e.message}")
                        delay(BLUETOOTH_RETRY_DELAY_MS)
                    }
                } finally {
                    runCatching { serverSocket?.close() }
                }
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
                    val heartbeat = NetworkMessage(
                        messageType = MessageType.HEARTBEAT,
                        messageId = java.util.UUID.randomUUID().toString(),
                        sourceDevice = localDeviceId(),
                        targetDevice = deviceId,
                        timestamp = System.currentTimeMillis(),
                        payload = JsonObject()
                    )
                    connection.send(heartbeat.toJson().toByteArray(Charsets.UTF_8))
                    logger.d(TAG, "Sent heartbeat to device: $deviceId")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.e(TAG, "Heartbeat failed for device: $deviceId", e)
                    handleConnectionError(deviceId, connection.getType(), connection)
                    break
                }
            }
        }
    }

    /**
     * 启动链路质量监控
     */
    private fun startLinkQualityMonitoring(deviceId: String) {
        linkQualityJobs[deviceId]?.cancel()
        linkQualityJobs[deviceId] = scope.launch {
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
        val lock = deviceConnectLocks.getOrPut(deviceId) { Mutex() }
        lock.withLock {
            attemptLinkSwitchLocked(deviceId)
        }
    }

    private suspend fun attemptLinkSwitchLocked(deviceId: String) {
        val currentConnection = activeConnections[deviceId] ?: return
        val currentLink = currentLinkTypes[deviceId] ?: return

        logger.i(TAG, "Attempting link switch for device: $deviceId from ${currentLink.description}")

        var replacement: Connection? = null
        var replacementPublished = false
        try {
            // 选择新的链路
            val endpoint = if (::deviceDao.isInitialized) deviceDao.getById(deviceId) else null
            val newLink = linkSelector.selectBestLink(
                deviceId,
                endpoint?.ipAddress,
                endpoint?.bluetoothAddress
            ) ?: return

            // 如果新链路与当前链路相同，跳过
            if (newLink == currentLink) {
                logger.d(TAG, "No better link available for device: $deviceId")
                return
            }

            // 如果新链路优先级更高，执行切换
            if (!newLink.hasHigherPriorityThan(currentLink)) {
                logger.d(TAG, "Candidate link is not better for device: $deviceId")
                return
            }

            val newConnection = tcpConnectionFactory.create(deviceId, mapLinkTypeToConnectionType(newLink))
            replacement = newConnection
            logger.i(TAG, "Switching link for device: $deviceId from ${currentLink.description} to ${newLink.description}")

            // 建立新连接并先完成身份认证。旧连接保持可用，直到新链路
            // 已经可以承载数据。
            newConnection.connect()
            if (!authenticateOutgoing(newConnection, deviceId)) {
                newConnection.close()
                throw SecurityException("Peer authentication failed during link switch")
            }

            // Publish the replacement before closing the old connection. Its
            // receive coroutine may report EOF asynchronously; publishing
            // first prevents that stale callback from tearing down the new link.
            activeConnections[deviceId] = newConnection
            replacementPublished = true
            currentLinkTypes[deviceId] = newLink
            currentConnection.close()

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

        } catch (e: CancellationException) {
            if (!replacementPublished) runCatching { replacement?.close() }
            throw e
        } catch (e: Exception) {
            if (!replacementPublished) runCatching { replacement?.close() }
            logger.e(TAG, "Failed to switch link for device: $deviceId", e)
        }
    }

    /**
     * 处理连接错误
     */
    private fun handleConnectionError(
        deviceId: String,
        type: ConnectionType,
        failedConnection: Connection? = null
    ) {
        scope.launch {
            val lock = deviceConnectLocks.getOrPut(deviceId) { Mutex() }
            lock.withLock {
                // A replaced connection can finish its receive coroutine after
                // the replacement is already active. Ignore that stale failure.
                if (failedConnection != null && activeConnections[deviceId] !== failedConnection) {
                    logger.d(TAG, "Ignoring stale connection failure for device: $deviceId")
                    return@withLock
                }
                logger.w(TAG, "Handling connection error for device: $deviceId")

                // 更新状态为重连中
                connectionStates[deviceId]?.emit(
                    createConnectionModel(deviceId, type, ConnectionState.RECONNECTING, null)
                )

                // 关闭旧连接. When a failing connection is supplied, close that
                // exact instance rather than whatever may have replaced it.
                (failedConnection ?: activeConnections[deviceId])?.close()
                if (failedConnection == null) {
                    activeConnections.remove(deviceId)
                } else {
                    activeConnections.remove(deviceId, failedConnection)
                }
                currentLinkTypes.remove(deviceId)
                linkQualityJobs.remove(deviceId)?.cancel()
                markDeviceConnection(deviceId, false)

                // 尝试链路降级或重连
                scheduleReconnect(deviceId, type)
            }
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

                val candidates = if (::connectionPolicyStore.isInitialized) {
                    connectionPolicyStore.get().orderedConnectionTypes()
                } else {
                    listOf(type)
                }
                var reconnected = false
                for (candidate in candidates.distinct()) {
                    if (!isActive || reconnected) break
                    try {
                        connectInternal(
                            deviceId = deviceId,
                            type = candidate,
                            scheduleReconnectOnFailure = false
                        ).collect { connection ->
                            if (connection.state == ConnectionState.CONNECTED) {
                                reconnected = true
                                logger.i(TAG, "Reconnected to device: $deviceId via ${candidate.name}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(
                            TAG,
                            "Reconnect via ${candidate.name} failed for device: $deviceId: ${e.message}"
                        )
                    }
                }
                if (reconnected) return@launch
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
        private const val DEFAULT_TLS_PORT = DEFAULT_WIFI_PORT
        private const val AUTH_TIMEOUT_MS = 10_000L
        private const val LOCAL_TLS_CONTEXT_ID = "local_tls_listener"
        private const val RFCOMM_SERVICE_NAME = "SMS-link"
        private val RFCOMM_UUID = java.util.UUID.fromString(
            "00001101-0000-1000-8000-00805F9B34FB"
        )
        private const val HEARTBEAT_INTERVAL = 30_000L // 30秒
        private const val LINK_QUALITY_CHECK_INTERVAL = 60_000L // 60秒
        private const val INITIAL_RECONNECT_DELAY = 1_000L // 1秒
        private const val MAX_RECONNECT_DELAY = 60_000L // 60秒
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BLUETOOTH_RETRY_DELAY_MS = 5_000L
        private const val TCP_RETRY_DELAY_MS = 5_000L
        private const val MAX_INBOUND_HANDSHAKES = 8
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
