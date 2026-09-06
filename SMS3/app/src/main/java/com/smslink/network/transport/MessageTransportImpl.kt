package com.smslink.network.transport

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionState
import com.smslink.device.IDeviceManager
import com.smslink.network.IConnectionManager
import com.smslink.network.connection.ConnectionPolicyStore
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.model.SendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reliable message layer above an authenticated connection.
 * A successful SendResult means the frame was written; the ACK tracker then
 * retries a bounded number of times if the peer never confirms delivery.
 */
@Singleton
class MessageTransportImpl @Inject constructor(
    private val connectionManager: IConnectionManager,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger,
    private val connectionPolicyStore: ConnectionPolicyStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context?
) : IMessageTransport {
    constructor(connectionManager: IConnectionManager, logger: ILogger) : this(
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
        logger = logger,
        connectionPolicyStore = ConnectionPolicyStore.forTests(),
        context = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMessages = ConcurrentLinkedQueue<PendingMessage>()
    private val awaitingAck = ConcurrentHashMap<String, SentMessage>()
    private val ackWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val completedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val receivedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val receivedMessageOrder = ConcurrentLinkedQueue<String>()
    private val receiveFlow = MutableSharedFlow<NetworkMessage>(replay = 0, extraBufferCapacity = 100)
    private val gson = Gson()
    private val outboxPreferences by lazy {
        context?.getSharedPreferences(TRANSPORT_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val outboxLock = Any()
    private val pendingQueueLock = Any()
    // Keep a queued item durable while the processor is establishing a
    // connection. This also serializes clearQueue() with that transition.
    private val pendingProcessingMutex = Mutex()

    init {
        restorePersistedMessages()
        startMessageProcessor()
        startReceiveProcessor()
        startAckTimeoutChecker()
    }

    override fun sendMessage(deviceId: String, message: NetworkMessage): Flow<SendResult> = flow {
        if (deviceId.isBlank() || message.targetDevice != deviceId) {
            emit(SendResult(false, message.messageId, "Target device mismatch"))
            return@flow
        }

        // A caller may retry the same logical message after the original ACK
        // arrived. Do not put an already-completed ID back on the wire.
        if (completedMessageIds.contains(message.messageId)) {
            emit(SendResult(true, message.messageId, timestamp = now()))
            return@flow
        }

        if (awaitingAck.containsKey(message.messageId)) {
            emit(SendResult(true, message.messageId, timestamp = awaitingAck[message.messageId]?.timestamp ?: now()))
            return@flow
        }
        if (pendingMessages.any { it.message.messageId == message.messageId }) {
            emit(SendResult(false, message.messageId, "Message queued for retry"))
            return@flow
        }

        // Fast path is important for a foreground UI and avoids waiting for a
        // connection-state flow when a link is already available.
        val (fastPathSent, fastPathTimestamp) = writeAndTrack(deviceId, message, 0)
        if (fastPathSent) {
            emit(SendResult(true, message.messageId, timestamp = fastPathTimestamp))
            return@flow
        }

        try {
            establishConnection(deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Unable to establish connection for $deviceId: ${e.message}")
        }
        var attempt = 0
        while (attempt < MAX_DIRECT_ATTEMPTS) {
            val (sent, sentAt) = writeAndTrack(deviceId, message, attempt)
            if (sent) {
                emit(SendResult(true, message.messageId, timestamp = sentAt))
                return@flow
            }
            attempt++
            if (attempt < MAX_DIRECT_ATTEMPTS) delay(DIRECT_RETRY_DELAYS[attempt - 1])
        }

        val queued = enqueuePending(PendingMessage(deviceId, message, 0, now()))
        emit(
            SendResult(
                success = false,
                messageId = message.messageId,
                error = if (queued) {
                    "No authenticated connection; queued for retry"
                } else {
                    "No authenticated connection; retry queue is full"
                }
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun receiveMessages(): Flow<NetworkMessage> = receiveFlow.asSharedFlow()

    override suspend fun sendAck(messageId: String, deviceId: String) {
        if (messageId.isBlank() || deviceId.isBlank()) return
        val ack = NetworkMessage(
            messageType = MessageType.ACK,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = localDeviceId(),
            targetDevice = deviceId,
            timestamp = now(),
            payload = JsonObject().apply { addProperty("ackFor", messageId) }
        )
        if (!tryWrite(deviceId, ack)) logger.w(TAG, "Could not send ACK for $messageId")
    }

    override suspend fun awaitDelivery(messageId: String, timeoutMs: Long): Boolean {
        if (messageId.isBlank()) return false
        if (completedMessageIds.contains(messageId)) return true
        if (!awaitingAck.containsKey(messageId)) return false

        val waiter = ackWaiters.computeIfAbsent(messageId) {
            CompletableDeferred()
        }
        if (completedMessageIds.contains(messageId)) {
            ackWaiters.remove(messageId, waiter)
            return true
        }

        val result = withTimeoutOrNull(timeoutMs.coerceIn(1L, MAX_ACK_WAIT_MS)) {
            waiter.await()
        }
        if (result == null) ackWaiters.remove(messageId, waiter)
        return result == true
    }

    override fun getPendingMessageCount(): Int = pendingMessages.size

    override suspend fun clearQueue() {
        pendingProcessingMutex.withLock {
            pendingMessages.clear()
            awaitingAck.clear()
            completedMessageIds.clear()
            ackWaiters.values.forEach { it.complete(false) }
            ackWaiters.clear()
            persistOutbox()
            logger.i(TAG, "Message queue cleared")
        }
    }

    private suspend fun tryWrite(deviceId: String, message: NetworkMessage): Boolean = try {
        connectionManager.sendData(deviceId, message.toJson().toByteArray(Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Install the ACK waiter before writing so an immediate peer ACK cannot
     * race past the tracker. Remove it again when the write itself fails. */
    private suspend fun writeAndTrack(
        deviceId: String,
        message: NetworkMessage,
        retryCount: Int
    ): Pair<Boolean, Long> {
        val sentAt = now()
        val sent = SentMessage(deviceId, message, sentAt, retryCount)
        val existing = awaitingAck.putIfAbsent(message.messageId, sent)
        if (existing != null) return true to existing.timestamp
        persistOutbox()

        if (tryWrite(deviceId, message)) return true to sentAt

        awaitingAck.remove(message.messageId, sent)
        persistOutbox()
        return false to sentAt
    }

    private suspend fun establishConnection(deviceId: String) {
        val active = try {
            connectionManager.getActiveConnections().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (active.any { it.deviceId == deviceId && it.state == ConnectionState.CONNECTED }) return

        val existingType = active.firstOrNull { it.deviceId == deviceId }?.type
        val candidates = existingType?.let { listOf(it) }
            ?: connectionPolicyStore.get().orderedConnectionTypes()

        for (type in candidates.distinct()) {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                connectionManager.connect(deviceId, type)
                    .firstOrNull { state ->
                        state.deviceId == deviceId && state.state == ConnectionState.CONNECTED
                    } != null
            }
            if (connected == true) return
        }

        throw IllegalStateException("Unable to establish authenticated connection")
    }

    private fun startMessageProcessor() {
        scope.launch {
            while (isActive) {
                // Do not remove the item until the write is tracked. The
                // persisted outbox must survive process death during connect.
                val pending = pendingMessages.peek()
                if (pending == null) {
                    delay(QUEUE_POLL_INTERVAL_MS)
                    continue
                }
                processPending(pending)
            }
        }
    }

    private suspend fun processPending(pending: PendingMessage) {
        pendingProcessingMutex.withLock {
            // clearQueue() may have removed the snapshot after peek() but
            // before this coroutine acquired the processing lock.
            if (!pendingMessages.contains(pending)) return@withLock
            if (awaitingAck.containsKey(pending.message.messageId)) {
                pendingMessages.remove(pending)
                persistOutbox()
                return@withLock
            }

            try {
                establishConnection(pending.deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Retry connection unavailable for ${pending.deviceId}: ${e.message}")
            }
            if (writeAndTrack(pending.deviceId, pending.message, pending.retryCount).first) {
                pendingMessages.remove(pending)
                persistOutbox()
                return@withLock
            }
            if (pending.retryCount < MAX_QUEUE_RETRIES) {
                delay(QUEUE_RETRY_DELAY_MS)
                pendingMessages.remove(pending)
                if (!enqueuePending(pending.copy(retryCount = pending.retryCount + 1, timestamp = now()))) {
                    ackWaiters.remove(pending.message.messageId)?.complete(false)
                }
            } else {
                pendingMessages.remove(pending)
                logger.e(TAG, "Dropping message after retries: ${pending.message.messageId}")
                ackWaiters.remove(pending.message.messageId)?.complete(false)
                persistOutbox()
            }
        }
    }

    private fun startReceiveProcessor() {
        scope.launch {
            connectionManager.receiveData()
                .catch { error -> logger.e(TAG, "Receive stream failed", error) }
                .collect { (transportDeviceId, bytes) ->
                    val message = NetworkMessage.fromJsonOrNull(String(bytes, Charsets.UTF_8))
                    val localId = localDeviceId()
                    if (message == null || !message.isStructurallyValid(transportDeviceId, localId)) {
                        logger.w(TAG, "Dropping invalid message from $transportDeviceId")
                        return@collect
                    }

                    when (message.messageType) {
                        MessageType.ACK -> handleAck(message, transportDeviceId)
                        MessageType.HEARTBEAT -> Unit
                        else -> {
                            // ACK duplicates too: the sender may have retried
                            // because the first ACK was lost, but never expose
                            // the duplicate to business modules.
                            sendAck(message.messageId, transportDeviceId)
                            if (rememberReceivedMessage(message.messageId)) receiveFlow.emit(message)
                        }
                    }
                }
        }
    }

    private fun handleAck(message: NetworkMessage, transportDeviceId: String) {
        val ackFor = runCatching { message.payload.get("ackFor")?.asString }.getOrNull()
        if (ackFor.isNullOrBlank()) return

        val sent = awaitingAck[ackFor] ?: return
        if (sent.deviceId != transportDeviceId) {
            logger.w(TAG, "Ignoring ACK from an unexpected device")
            return
        }
        if (awaitingAck.remove(ackFor, sent)) {
            rememberCompletedMessage(ackFor)
            ackWaiters.remove(ackFor)?.complete(true)
            persistOutbox()
        }
    }

    private fun startAckTimeoutChecker() {
        scope.launch {
            while (isActive) {
                delay(ACK_CHECK_INTERVAL_MS)
                val now = now()
                awaitingAck.entries.toList().forEach { (messageId, sent) ->
                    if (now - sent.timestamp <= ACK_TIMEOUT_MS) return@forEach
                    if (awaitingAck.remove(messageId, sent)) {
                        if (sent.retryCount < MAX_ACK_RETRIES) {
                            if (!enqueuePending(
                                    PendingMessage(sent.deviceId, sent.message, sent.retryCount + 1, now)
                                )
                            ) {
                                ackWaiters.remove(messageId)?.complete(false)
                            }
                        } else {
                            logger.e(TAG, "Message was not acknowledged: $messageId")
                            ackWaiters.remove(messageId)?.complete(false)
                        }
                        persistOutbox()
                    }
                }
            }
        }
    }

    private fun enqueuePending(message: PendingMessage): Boolean {
        synchronized(pendingQueueLock) {
            if (pendingMessages.any { it.message.messageId == message.message.messageId }) {
                return true
            }
            if (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                logger.e(TAG, "Pending message queue is full; dropping ${message.message.messageId}")
                return false
            }
            pendingMessages.offer(message)
        }
        persistOutbox()
        return true
    }

    /** Keep replay protection bounded so a peer cannot grow this set forever. */
    private fun rememberReceivedMessage(messageId: String): Boolean {
        if (!receivedMessageIds.add(messageId)) return false
        receivedMessageOrder.offer(messageId)
        while (receivedMessageOrder.size > MAX_RECEIVED_MESSAGE_IDS) {
            val oldest = receivedMessageOrder.poll() ?: break
            receivedMessageIds.remove(oldest)
        }
        return true
    }

    private fun rememberCompletedMessage(messageId: String) {
        completedMessageIds.add(messageId)
        while (completedMessageIds.size > MAX_COMPLETED_MESSAGE_IDS) {
            val oldest = completedMessageIds.firstOrNull() ?: break
            completedMessageIds.remove(oldest)
        }
    }

    private fun restorePersistedMessages() {
        val raw = outboxPreferences?.getString(OUTBOX_KEY, null) ?: return
        runCatching {
            gson.fromJson(raw, Array<PersistedMessage>::class.java)
                .orEmpty()
                .filter {
                    it.deviceId.isNotBlank() &&
                        it.message.targetDevice == it.deviceId &&
                        it.message.messageId.isNotBlank()
                }
                .forEach {
                    if (pendingMessages.size < MAX_PENDING_MESSAGES) {
                        pendingMessages.offer(
                            PendingMessage(
                                deviceId = it.deviceId,
                                message = it.message,
                                retryCount = it.retryCount.coerceAtLeast(0),
                                timestamp = it.timestamp
                            )
                        )
                    }
                }
        }.onFailure {
            logger.w(TAG, "Discarding unreadable persisted message queue")
            outboxPreferences?.edit()?.remove(OUTBOX_KEY)?.apply()
        }
    }

    private fun persistOutbox() {
        val preferences = outboxPreferences ?: return
        synchronized(outboxLock) {
            val persisted = buildList {
                pendingMessages.forEach {
                    add(PersistedMessage(it.deviceId, it.message, it.retryCount, it.timestamp))
                }
                awaitingAck.values.forEach {
                    add(PersistedMessage(it.deviceId, it.message, it.retryCount, it.timestamp))
                }
            }
                .distinctBy { it.message.messageId }
                .take(MAX_PERSISTED_OUTBOX)
            preferences.edit()
                .putString(OUTBOX_KEY, gson.toJson(persisted))
                // Persist synchronously around network transitions so process
                // death cannot lose the state that was just queued or ACKed.
                .commit()
        }
    }

    private fun localDeviceId(): String = runCatching { deviceManager.getLocalDevice().id }.getOrDefault("local")
    private fun now() = System.currentTimeMillis()

    private data class PendingMessage(
        val deviceId: String,
        val message: NetworkMessage,
        val retryCount: Int,
        val timestamp: Long
    )

    private data class SentMessage(
        val deviceId: String,
        val message: NetworkMessage,
        val timestamp: Long,
        val retryCount: Int
    )

    private data class PersistedMessage(
        val deviceId: String,
        val message: NetworkMessage,
        val retryCount: Int,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "MessageTransport"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val ACK_CHECK_INTERVAL_MS = 5_000L
        private const val QUEUE_POLL_INTERVAL_MS = 100L
        private const val QUEUE_RETRY_DELAY_MS = 1_000L
        private const val MAX_DIRECT_ATTEMPTS = 3
        private const val MAX_QUEUE_RETRIES = 5
        private const val MAX_ACK_RETRIES = 3
        private const val MAX_ACK_WAIT_MS = 30_000L
        private const val MAX_PERSISTED_OUTBOX = 256
        private const val MAX_PENDING_MESSAGES = 256
        private const val MAX_COMPLETED_MESSAGE_IDS = 1024
        private const val MAX_RECEIVED_MESSAGE_IDS = 4096
        private const val TRANSPORT_PREFERENCES = "smslink_transport"
        private const val OUTBOX_KEY = "outbox_v1"
        private val DIRECT_RETRY_DELAYS = longArrayOf(100L, 500L)
    }
}
