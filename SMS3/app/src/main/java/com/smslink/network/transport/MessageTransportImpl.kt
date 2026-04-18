package com.smslink.network.transport

import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.device.IDeviceManager
import com.smslink.network.IConnectionManager
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.ConnectionType
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.model.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageTransportImpl @Inject constructor(
    private val connectionManager: IConnectionManager,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger
) : IMessageTransport {
    constructor(
        connectionManager: IConnectionManager,
        logger: ILogger
    ) : this(
        connectionManager = connectionManager,
        deviceManager = object : IDeviceManager {
            override fun startDiscovery() = Unit
            override fun stopDiscovery() = Unit
            override fun pairDevice(deviceId: String, qrCode: String) = kotlinx.coroutines.flow.emptyFlow<com.smslink.core.model.PairResult>()
            override fun getConnectedDevices() = kotlinx.coroutines.flow.flowOf(emptyList<com.smslink.core.model.Device>())
            override suspend fun setDeviceRole(deviceId: String, role: com.smslink.core.model.DeviceRole) = Unit
            override suspend fun removeDevice(deviceId: String) = Unit
            override fun getLocalDevice() = com.smslink.core.model.Device(
                id = "local",
                name = "local",
                type = com.smslink.core.model.DeviceType.PHONE,
                role = com.smslink.core.model.DeviceRole.MAIN,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )
        },
        logger = logger
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private val sentMessages = ConcurrentHashMap<String, SentMessage>()
    private val receiveFlow = MutableSharedFlow<NetworkMessage>(
        replay = 0,
        extraBufferCapacity = 100
    )

    init {
        startMessageProcessor()
        startReceiveProcessor()
        startAckTimeoutChecker()
    }

    override fun sendMessage(deviceId: String, message: NetworkMessage): Flow<SendResult> = flow {
        logger.i(TAG, "Sending message ${message.messageId} to device: $deviceId")

        try {
            ensureConnection(deviceId)
        } catch (e: Exception) {
            logger.w(TAG, "Proceeding without confirmed connection for $deviceId: ${e.message}")
        }

        val immediateSuccess = runCatching {
            connectionManager.sendData(deviceId, message.toJson().toByteArray())
        }.getOrDefault(false)

        if (immediateSuccess) {
            sentMessages[message.messageId] = SentMessage(
                deviceId = deviceId,
                message = message,
                timestamp = System.currentTimeMillis()
            )
            pendingMessages.removeIf { it.message.messageId == message.messageId }
            emit(
                SendResult(
                    success = true,
                    messageId = message.messageId,
                    timestamp = System.currentTimeMillis()
                )
            )
            return@flow
        }

        pendingMessages.offer(
            PendingMessage(
                deviceId = deviceId,
                message = message,
                retryCount = 0,
                timestamp = System.currentTimeMillis()
            )
        )

        var attempts = 0
        while (attempts < MAX_SEND_ATTEMPTS) {
            delay(100)

            sentMessages[message.messageId]?.let { sent ->
                emit(
                    SendResult(
                        success = true,
                        messageId = message.messageId,
                        timestamp = sent.timestamp
                    )
                )
                return@flow
            }

            if (!pendingMessages.any { it.message.messageId == message.messageId }) {
                emit(
                    SendResult(
                        success = false,
                        messageId = message.messageId,
                        error = "Failed to send message"
                    )
                )
                return@flow
            }

            attempts++
        }

        emit(
            SendResult(
                success = false,
                messageId = message.messageId,
                error = "Send timeout"
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun receiveMessages(): Flow<NetworkMessage> = receiveFlow.asSharedFlow()

    override suspend fun sendAck(messageId: String, deviceId: String) {
        logger.d(TAG, "Sending ACK for message: $messageId to device: $deviceId")

        val ackMessage = NetworkMessage(
            messageType = MessageType.ACK,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = deviceManager.getLocalDevice().id,
            targetDevice = deviceId,
            timestamp = System.currentTimeMillis(),
            payload = JsonObject().apply {
                addProperty("ackFor", messageId)
            }
        )

        try {
            connectionManager.sendData(deviceId, ackMessage.toJson().toByteArray())
            logger.d(TAG, "ACK sent for message: $messageId")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send ACK for message: $messageId", e)
        }
    }

    override fun getPendingMessageCount(): Int = pendingMessages.size

    override suspend fun clearQueue() {
        logger.i(TAG, "Clearing message queue")
        pendingMessages.clear()
        sentMessages.clear()
    }

    private fun startMessageProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    val message = pendingMessages.poll()
                    if (message != null) {
                        processMessage(message)
                    } else {
                        delay(100)
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Error processing message", e)
                }
            }
        }
    }

    private suspend fun processMessage(pendingMessage: PendingMessage) {
        val message = pendingMessage.message
        val deviceId = pendingMessage.deviceId

        if (sentMessages.containsKey(message.messageId)) {
            logger.d(TAG, "Skipping already sent message ${message.messageId}")
            return
        }

        try {
            val success = connectionManager.sendData(deviceId, message.toJson().toByteArray())
            if (success) {
                sentMessages[message.messageId] = SentMessage(
                    deviceId = deviceId,
                    message = message,
                    timestamp = System.currentTimeMillis()
                )
                logger.i(TAG, "Message ${message.messageId} sent successfully")
            } else if (pendingMessage.retryCount < MAX_RETRY_COUNT) {
                pendingMessages.offer(
                    pendingMessage.copy(
                        retryCount = pendingMessage.retryCount + 1,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                logger.e(TAG, "Message ${message.messageId} send failed after max retries")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error processing message ${message.messageId}", e)
            if (pendingMessage.retryCount < MAX_RETRY_COUNT) {
                pendingMessages.offer(
                    pendingMessage.copy(
                        retryCount = pendingMessage.retryCount + 1,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun ensureConnection(deviceId: String) {
        val activeConnections = runCatching { connectionManager.getActiveConnections().first() }
            .getOrDefault(emptyList())

        val existing = activeConnections.firstOrNull {
            it.deviceId == deviceId && it.state == ConnectionState.CONNECTED
        }
        if (existing != null) {
            return
        }

        val connectionType = activeConnections.firstOrNull { it.deviceId == deviceId }?.type
            ?: ConnectionType.WIFI

        val connectJob = scope.launch {
            connectionManager.connect(deviceId, connectionType).collect { }
        }

        val connected = withTimeoutOrNull(15_000L) {
            connectionManager.getActiveConnections()
                .map { connections ->
                    connections.firstOrNull {
                        it.deviceId == deviceId && it.state == ConnectionState.CONNECTED
                    }
                }
                .filterNotNull()
                .first()
        }

        connectJob.cancel()

        if (connected == null) {
            throw IllegalStateException("Unable to establish connection for $deviceId")
        }
    }

    private fun startReceiveProcessor() {
        scope.launch {
            connectionManager.receiveData().collect { (deviceId, data) ->
                try {
                    val message = NetworkMessage.fromJson(String(data))
                    if (message.messageType == MessageType.ACK) {
                        handleAck(message)
                    } else {
                        receiveFlow.emit(message)
                        sendAck(message.messageId, deviceId)
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Error parsing received message from device: $deviceId", e)
                }
            }
        }
    }

    private fun handleAck(ackMessage: NetworkMessage) {
        try {
            ackMessage.payload.get("ackFor")?.asString?.let { ackFor ->
                sentMessages.remove(ackFor)
                logger.d(TAG, "Received ACK for message: $ackFor")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error handling ACK message", e)
        }
    }

    private fun startAckTimeoutChecker() {
        scope.launch {
            while (isActive) {
                delay(ACK_CHECK_INTERVAL)

                val now = System.currentTimeMillis()
                val timedOut = sentMessages.filterValues { now - it.timestamp > ACK_TIMEOUT }.keys

                timedOut.forEach { messageId ->
                    sentMessages.remove(messageId)?.let { sentMessage ->
                        pendingMessages.offer(
                            PendingMessage(
                                deviceId = sentMessage.deviceId,
                                message = sentMessage.message,
                                retryCount = 0,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    private data class PendingMessage(
        val deviceId: String,
        val message: NetworkMessage,
        val retryCount: Int,
        val timestamp: Long
    )

    private data class SentMessage(
        val deviceId: String,
        val message: NetworkMessage,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "MessageTransport"
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_SEND_ATTEMPTS = 50
        private const val ACK_TIMEOUT = 10_000L
        private const val ACK_CHECK_INTERVAL = 5_000L
    }
}
