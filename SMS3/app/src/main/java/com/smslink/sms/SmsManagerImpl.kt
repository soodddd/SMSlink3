package com.smslink.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import com.smslink.core.model.SmsMessage
import com.smslink.core.model.SmsDeliveryStatus
import com.smslink.device.IDeviceManager
import com.smslink.device.observeLiveConnections
import com.smslink.network.model.MessageType as NetworkMessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import com.smslink.sms.model.Conversation
import com.smslink.sms.model.SmsSendResult
import com.smslink.sms.model.SmsSyncPayload
import com.smslink.sms.model.SyncAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates the local telephony provider and idempotent remote SMS sync. */
@Singleton
class SmsManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val messageTransport: IMessageTransport,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger
) : ISmsManager {
    /** Compatibility constructor for the original JVM tests. */
    constructor(context: Context, messageDao: MessageDao, logger: ILogger) : this(
        context = context,
        messageDao = messageDao,
        messageTransport = object : IMessageTransport {
            override fun sendMessage(deviceId: String, message: NetworkMessage) = flowOf(
                com.smslink.network.model.SendResult(false, message.messageId, "noop")
            )
            override fun receiveMessages() = emptyFlow<NetworkMessage>()
            override suspend fun sendAck(messageId: String, deviceId: String) = Unit
            override fun getPendingMessageCount() = 0
            override suspend fun clearQueue() = Unit
        },
        deviceManager = object : IDeviceManager {
            override fun startDiscovery() = Unit
            override fun stopDiscovery() = Unit
            override fun pairDevice(deviceId: String, qrCode: String) = emptyFlow<com.smslink.core.model.PairResult>()
            override fun getConnectedDevices() = flowOf(emptyList<Device>())
            override suspend fun setDeviceRole(deviceId: String, role: DeviceRole) = Unit
            override suspend fun removeDevice(deviceId: String) = Unit
            override fun getLocalDevice() = Device(
                id = "local",
                name = "local",
                type = DeviceType.PHONE,
                role = DeviceRole.MAIN,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )
        },
        logger = logger
    )

    private val _newMessages = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingRemoteSendResults = ConcurrentHashMap<String, CompletableDeferred<SmsSendResult>>()
    private val remoteSendMutex = Mutex()
    private val smsPartLock = Any()
    private val smsPartStates = ConcurrentHashMap<String, SmsPartState>()
    private val smsPartPreferences by lazy {
        context.getSharedPreferences(SMS_PART_PREFERENCES, Context.MODE_PRIVATE)
    }

    private val defaultSmsManager: SmsManager? = runCatching { SmsManager.getDefault() }.getOrNull()

    init {
        startListeningRemoteMessages()
    }

    override fun getMessages(limit: Int): Flow<List<Message>> = messageDao.getAll(limit.coerceIn(1, MAX_QUERY_LIMIT))

    override suspend fun sendMessage(address: String, body: String, simSlot: Int?): Boolean {
        val normalizedAddress = address.trim()
        if (!isValidSmsAddress(normalizedAddress) || !isValidSmsBody(body)) return false

        val localDevice = runCatching { deviceManager.getLocalDevice() }.getOrNull()
            ?: return false
        if (localDevice.role != DeviceRole.MAIN && localDevice.role != DeviceRole.CELLULAR_SOURCE) {
            val liveDevices = (try {
                deviceManager.observeLiveConnections().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }).filter { it.id != localDevice.id && it.isPaired }
            val target = liveDevices.firstOrNull {
                    it.role == DeviceRole.MAIN || it.role == DeviceRole.CELLULAR_SOURCE
                }
                ?.id
                ?: liveDevices.firstOrNull()
                ?.id
                ?: return false
            return sendRemoteRequest(target, normalizedAddress, body, simSlot)
        }

        return sendSmsLocally(normalizedAddress, body, simSlot) != null
    }

    /** Sends through an explicitly selected paired device (normally the SIM owner). */
    suspend fun sendMessageFromDevice(
        address: String,
        body: String,
        sourceDeviceId: String,
        simSlot: Int? = null
    ): Boolean {
        val localId = localDeviceId()
        if (sourceDeviceId.isBlank() || sourceDeviceId == localId) {
            return sendMessage(address, body, simSlot)
        }
        val normalizedAddress = address.trim()
        if (!isValidSmsAddress(normalizedAddress) || !isValidSmsBody(body)) return false
        val target = (try {
            deviceManager.observeLiveConnections().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }).firstOrNull { it.id == sourceDeviceId && it.isPaired }
            ?: return false
        return sendRemoteRequest(target.id, normalizedAddress, body, simSlot)
    }

    private suspend fun sendSmsLocally(
        address: String,
        body: String,
        simSlot: Int?,
        messageIdOverride: String? = null
    ): String? {
        val manager = resolveSmsManager(simSlot)
        if (manager != null && !hasSendPermission()) {
            logger.w(TAG, "SEND_SMS permission is not granted")
            return null
        }

        val messageId = messageIdOverride
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString()
        val threadId = getOrCreateThreadId(address)
        val queuedMessage = Message(
            id = messageId,
            threadId = threadId,
            address = address,
            body = body,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SENT,
            read = true,
            deviceId = localDeviceId(),
            deliveryStatus = SmsDeliveryStatus.QUEUED
        )
        return try {
            messageDao.insert(queuedMessage)
            var partCount = 1
            if (manager != null) {
                val parts = manager.divideMessage(body)
                require(parts.isNotEmpty()) { "SmsManager returned no message parts" }
                partCount = parts.size
                initializeSmsPartState(messageId, parts.size)
                if (parts.size == 1) {
                    manager.sendTextMessage(
                        address,
                        null,
                        body,
                        createStatusPendingIntent(ACTION_SMS_SENT, messageId, 0, 1),
                        createStatusPendingIntent(ACTION_SMS_DELIVERED, messageId, 0, 1)
                    )
                } else {
                    val sentIntents = ArrayList<PendingIntent>(parts.size)
                    val deliveredIntents = ArrayList<PendingIntent>(parts.size)
                    parts.indices.forEach { index ->
                        createStatusPendingIntent(
                            ACTION_SMS_SENT,
                            messageId,
                            index,
                            parts.size
                        )?.let(sentIntents::add)
                        createStatusPendingIntent(
                            ACTION_SMS_DELIVERED,
                            messageId,
                            index,
                            parts.size
                        )?.let(deliveredIntents::add)
                    }
                    require(
                        sentIntents.size == parts.size &&
                            deliveredIntents.size == parts.size
                    ) { "Unable to create SMS status intents" }
                    manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
                }
            } else {
                // A JVM/unit-test or a non-telephony profile has no platform
                // SmsManager. The local repository can still be used as a
                // dry-run ledger; real Android telephony paths always expose
                // a manager and are permission checked above.
                logger.w(TAG, "No platform SmsManager; recording local submission only")
            }

            // For multipart SMS, telephony reports each segment separately.
            // Do not publish SENT until every segment has acknowledged; an
            // early aggregate status makes peers show a successful message
            // even when one segment later fails.
            val initialStatus = if (partCount > 1) {
                SmsDeliveryStatus.QUEUED
            } else {
                SmsDeliveryStatus.SENT
            }
            val message = queuedMessage.copy(
                deliveryStatus = initialStatus,
                deliveryTimestamp = initialStatus.takeIf { it == SmsDeliveryStatus.SENT }
                    ?.let { System.currentTimeMillis() }
            )
            messageDao.updateDeliveryStatus(
                messageId,
                initialStatus,
                message.deliveryTimestamp
            )
            scope.launch { syncSmsToDevices(message, simSlot) }
            logger.i(TAG, "SMS submitted to telephony: $address")
            messageId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to submit SMS", e)
            synchronized(smsPartLock) {
                smsPartStates.remove(messageId)
                smsPartPreferences.edit().remove(partKey(messageId)).apply()
            }
            try {
                messageDao.updateDeliveryStatus(
                    messageId,
                    SmsDeliveryStatus.FAILED,
                    System.currentTimeMillis()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Preserve the original submission failure.
            }
            null
        }
    }

    private suspend fun sendRemoteRequest(
        targetDeviceId: String,
        address: String,
        body: String,
        simSlot: Int?
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val payload = SmsSyncPayload(
            messageId = requestId,
            threadId = getOrCreateThreadId(address),
            address = address,
            body = body,
            timestamp = System.currentTimeMillis(),
            type = MessageType.SENT,
            read = true,
            simSlot = simSlot,
            action = SyncAction.SEND_REQUEST
        )
        val requestMessage = Message(
            id = requestId,
            threadId = payload.threadId,
            address = address,
            body = body,
            timestamp = payload.timestamp,
            type = MessageType.SENT,
            read = true,
            deviceId = localDeviceId(),
            deliveryStatus = SmsDeliveryStatus.QUEUED
        )
        try {
            messageDao.insert(requestMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to persist remote SMS request", e)
            return false
        }
        val resultWaiter = CompletableDeferred<SmsSendResult>()
        pendingRemoteSendResults[requestId] = resultWaiter
        try {
            val result = sendPayload(targetDeviceId, payload)
            if (!result.success) {
                try {
                    messageDao.updateDeliveryStatus(
                        requestId,
                        SmsDeliveryStatus.FAILED,
                        System.currentTimeMillis()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Preserve the transport failure result.
                }
                return false
            }
            // The transport ACK only proves that the request reached the peer.
            // Do not report success until the peer explicitly reports the
            // telephony submission result; otherwise a dead/old peer would
            // make the UI claim that an SMS was sent when it was not.
            val remoteResult = withTimeoutOrNull(REMOTE_SEND_RESULT_TIMEOUT_MS) {
                resultWaiter.await()
            }
            val effectiveSuccess = remoteResult?.success == true
            val effectiveTimestamp = remoteResult?.timestamp ?: System.currentTimeMillis()
            try {
                messageDao.updateDeliveryStatus(
                    requestId,
                    if (effectiveSuccess) SmsDeliveryStatus.SENT else SmsDeliveryStatus.FAILED,
                    effectiveTimestamp
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The caller still receives the remote result; a later sync
                // can reconcile a transient local database failure.
            }
            return effectiveSuccess
        } finally {
            pendingRemoteSendResults.remove(requestId, resultWaiter)
        }
    }

    override suspend fun syncMessage(message: SmsMessage, targetDeviceId: String) {
        syncSmsToDevice(
            Message(
                id = message.id,
                threadId = getOrCreateThreadId(message.address),
                address = message.address,
                body = message.body,
                timestamp = message.timestamp,
                type = MessageType.INBOX,
                read = message.isRead,
                deviceId = localDeviceId()
            ),
            targetDeviceId
        )
    }

    private suspend fun syncSmsToDevices(message: Message, simSlot: Int? = null) {
        val devices = try {
            deviceManager.observeLiveConnections().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        devices.filter { it.id != localDeviceId() && it.isPaired }.forEach { device ->
            val syncKey = "${message.id}:${device.id}"
            if (!syncedMessageIds.add(syncKey)) return@forEach
            if (!syncSmsToDevice(message, device.id, simSlot)) syncedMessageIds.remove(syncKey)
        }
    }

    private suspend fun syncSmsToDevice(message: Message, targetDeviceId: String, simSlot: Int? = null): Boolean {
        val payload = SmsSyncPayload(
            messageId = message.id,
            threadId = message.threadId,
            address = message.address,
            body = message.body,
            timestamp = message.timestamp,
            type = message.type,
            read = message.read,
            deliveryStatus = message.deliveryStatus,
            deliveryTimestamp = message.deliveryTimestamp,
            simSlot = simSlot,
            action = SyncAction.NEW_MESSAGE
        )
        val result = sendPayload(targetDeviceId, payload)
        if (result.success) logger.d(TAG, "SMS synced to device: $targetDeviceId")
        else logger.w(TAG, "SMS sync queued/failed for $targetDeviceId: ${result.error}")
        return result.success
    }

    private suspend fun sendPayload(targetDeviceId: String, payload: SmsSyncPayload): com.smslink.network.model.SendResult {
        val message = NetworkMessage(
            messageType = NetworkMessageType.SMS,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = localDeviceId(),
            targetDevice = targetDeviceId,
            timestamp = System.currentTimeMillis(),
            payload = JsonParser.parseString(payload.toJson()).asJsonObject
        )
        val result = try {
            messageTransport.sendMessage(targetDeviceId, message).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return com.smslink.network.model.SendResult(false, message.messageId, e.message)
        }
        if (!result.success) return result
        val acknowledged = try {
            messageTransport.awaitDelivery(result.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep compatibility with transports that do not expose a
            // delivery-ACK implementation, but never hide cancellation.
            logger.w(TAG, "SMS delivery ACK unavailable: ${e.message}")
            true
        }
        return if (acknowledged) {
            result
        } else {
            com.smslink.network.model.SendResult(
                false,
                result.messageId,
                "Message was not acknowledged"
            )
        }
    }

    override fun observeNewMessages(): Flow<Message> = _newMessages.asSharedFlow()

    override suspend fun markAsRead(messageId: String) {
        messageDao.markAsRead(messageId)
        try {
            messageDao.getById(messageId)?.let {
                updateSystemSmsReadStatus(it, true)
                syncReadStatusToDevices(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to propagate read state", e)
        }
    }

    suspend fun updateDeliveryStatus(
        messageId: String,
        status: SmsDeliveryStatus,
        partIndex: Int = 0,
        partCount: Int = 1
    ) {
        val publishStatus = synchronized(smsPartLock) {
            val state = loadSmsPartState(messageId, partCount)
            if (state == null) {
                true
            } else {
                val safeIndex = partIndex.coerceAtLeast(0)
                if (safeIndex >= state.partCount) {
                    false
                } else {
                    when (status) {
                        SmsDeliveryStatus.SENT -> state.sentParts.add(safeIndex)
                        SmsDeliveryStatus.DELIVERED -> state.deliveredParts.add(safeIndex)
                        SmsDeliveryStatus.FAILED -> state.failed = true
                        SmsDeliveryStatus.QUEUED -> Unit
                    }
                    val complete = when (status) {
                        SmsDeliveryStatus.SENT -> state.sentParts.size >= state.partCount
                        SmsDeliveryStatus.DELIVERED ->
                            state.deliveredParts.size >= state.partCount && !state.failed
                        SmsDeliveryStatus.FAILED -> true
                        SmsDeliveryStatus.QUEUED -> false
                    }
                    val removeState = status == SmsDeliveryStatus.FAILED ||
                        (status == SmsDeliveryStatus.DELIVERED && complete) ||
                        (status == SmsDeliveryStatus.SENT && state.partCount == 1 && complete)
                    if (removeState) {
                        smsPartStates.remove(messageId)
                        smsPartPreferences.edit().remove(partKey(messageId)).apply()
                    } else {
                        persistSmsPartState(messageId, state)
                    }
                    complete
                }
            }
        }
        // Do not mark a multipart SMS delivered until every part has reported.
        if (!publishStatus) return

        val timestamp = if (status == SmsDeliveryStatus.DELIVERED || status == SmsDeliveryStatus.FAILED) {
            System.currentTimeMillis()
        } else {
            null
        }
        try {
            messageDao.getById(messageId)?.let { current ->
                val effectiveStatus = mergeDeliveryStatus(current.deliveryStatus, status)
                val effectiveTimestamp = when {
                    effectiveStatus == current.deliveryStatus && current.deliveryTimestamp != null ->
                        current.deliveryTimestamp
                    timestamp != null -> timestamp
                    else -> current.deliveryTimestamp
                }
                if (effectiveStatus != current.deliveryStatus ||
                    effectiveTimestamp != current.deliveryTimestamp
                ) {
                    messageDao.updateDeliveryStatus(messageId, effectiveStatus, effectiveTimestamp)
                }
                val updated = current.copy(
                    deliveryStatus = effectiveStatus,
                    deliveryTimestamp = effectiveTimestamp
                )
                val devices = deviceManager.observeLiveConnections().firstOrNull().orEmpty()
                devices.filter { it.id != localDeviceId() && it.isPaired }.forEach { device ->
                    syncSmsToDevice(updated, device.id)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update SMS delivery status", e)
        }
    }

    private suspend fun syncReadStatusToDevices(message: Message) {
        val payload = SmsSyncPayload(
            messageId = message.id,
            threadId = message.threadId,
            address = message.address,
            body = message.body,
            timestamp = message.timestamp,
            type = message.type,
            read = true,
            deliveryStatus = message.deliveryStatus,
            deliveryTimestamp = message.deliveryTimestamp,
            action = SyncAction.MARK_READ
        )
        val devices = try {
            deviceManager.observeLiveConnections().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        devices.filter { it.id != localDeviceId() && it.isPaired }.forEach { sendPayload(it.id, payload) }
    }

    override suspend fun deleteMessage(messageId: String) {
        val existing = messageDao.getById(messageId)
        messageDao.deleteById(messageId)
        if (existing != null) {
            val payload = SmsSyncPayload(
                messageId = existing.id,
                threadId = existing.threadId,
                address = existing.address,
                body = existing.body,
                timestamp = existing.timestamp,
                type = existing.type,
                read = existing.read,
                deliveryStatus = existing.deliveryStatus,
                deliveryTimestamp = existing.deliveryTimestamp,
                action = SyncAction.DELETE_MESSAGE
            )
            val devices = try {
                deviceManager.observeLiveConnections().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            devices.filter { it.id != localDeviceId() && it.isPaired }.forEach { sendPayload(it.id, payload) }
        }
    }

    suspend fun syncFromSystem(limit: Int = 100) {
        try {
            if (!hasReadPermission() && defaultSmsManager != null) {
                logger.w(TAG, "READ_SMS permission is not granted")
                return
            }
            val messages = readSystemSms(limit.coerceIn(1, MAX_QUERY_LIMIT))
            if (messages.isEmpty()) return

            // Provider row IDs do not match the UUID created by the receiver
            // or by a queued local send. Reconcile before inserting so one SMS
            // cannot appear twice or lose its delivery state.
            val unmatched = messageDao.getAllSnapshot().toMutableList()
            messages.forEach { systemMessage ->
                val sameId = unmatched.firstOrNull { it.id == systemMessage.id }
                val localPending = unmatched.asSequence().filter { candidate ->
                    candidate.deviceId == localDeviceId() &&
                        candidate.id != systemMessage.id &&
                        addressesMatch(candidate.address, systemMessage.address) &&
                        candidate.body == systemMessage.body &&
                        (candidate.type == systemMessage.type ||
                            (candidate.type == MessageType.SENT &&
                                systemMessage.type in setOf(
                                    MessageType.SENT,
                                    MessageType.OUTBOX,
                                    MessageType.FAILED
                                ))) &&
                        timestampDistance(candidate.timestamp, systemMessage.timestamp) <= SYSTEM_MATCH_WINDOW_MS
                }.minByOrNull { candidate ->
                    timestampDistance(candidate.timestamp, systemMessage.timestamp)
                }
                val previous = localPending ?: sameId
                val systemStatus = if (systemMessage.type == MessageType.SENT ||
                    systemMessage.type == MessageType.OUTBOX
                ) {
                    SmsDeliveryStatus.SENT
                } else if (systemMessage.type == MessageType.FAILED) {
                    SmsDeliveryStatus.FAILED
                } else {
                    systemMessage.deliveryStatus
                }
                val merged = previous?.copy(
                    threadId = systemMessage.threadId,
                    address = systemMessage.address,
                    body = systemMessage.body,
                    timestamp = systemMessage.timestamp,
                    type = systemMessage.type,
                    read = previous.read || systemMessage.read,
                    deliveryStatus = mergeDeliveryStatus(previous.deliveryStatus, systemStatus),
                    deliveryTimestamp = previous.deliveryTimestamp
                ) ?: systemMessage.copy(deliveryStatus = systemStatus)

                if (previous == null) {
                    messageDao.insert(merged)
                } else if (merged != previous) {
                    messageDao.update(merged)
                }
                previous?.let(unmatched::remove)
                syncSmsToDevices(merged)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync system SMS", e)
        }
    }

    suspend fun notifyNewMessage(message: Message) {
        val normalized = if (message.deviceId == "local") message.copy(deviceId = localDeviceId()) else message
        if (messageDao.getById(normalized.id)?.id != normalized.id) {
            messageDao.insert(normalized)
            _newMessages.emit(normalized)
            scope.launch { syncSmsToDevices(normalized) }
        }
    }

    private fun startListeningRemoteMessages() {
        scope.launch {
            messageTransport.receiveMessages()
                .filter { it.messageType == NetworkMessageType.SMS }
                .catch { logger.e(TAG, "Remote SMS stream failed", it) }
                .collect { handleRemoteMessage(it) }
        }
    }

    private suspend fun handleRemoteMessage(networkMessage: NetworkMessage) {
        try {
            val localId = localDeviceId()
            if (!networkMessage.isStructurallyValid(expectedTarget = localId) ||
                networkMessage.sourceDevice == localId
            ) {
                logger.w(TAG, "Ignoring malformed or self-addressed remote SMS")
                return
            }
            val payload = SmsSyncPayload.fromJson(networkMessage.payload.toString())
            if (!isValidSmsPayload(payload, networkMessage.timestamp)) {
                logger.w(TAG, "Ignoring invalid remote SMS payload")
                return
            }
            when (payload.action) {
                SyncAction.NEW_MESSAGE -> handleRemoteSms(payload, networkMessage.sourceDevice)
                SyncAction.MARK_READ -> handleRemoteReadStatus(payload)
                SyncAction.DELETE_MESSAGE -> messageDao.deleteById(payload.messageId)
                SyncAction.SEND_REQUEST -> handleRemoteSendRequest(payload, networkMessage.sourceDevice)
                SyncAction.SEND_RESULT -> handleRemoteSendResult(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote SMS", e)
        }
    }

    private suspend fun handleRemoteSms(payload: SmsSyncPayload, sourceDevice: String) {
        val existing = messageDao.getById(payload.messageId)
        if (existing != null) {
            val mergedStatus = mergeDeliveryStatus(existing.deliveryStatus, payload.deliveryStatus)
            val merged = existing.copy(
                threadId = payload.threadId.ifBlank { existing.threadId },
                address = payload.address.ifBlank { existing.address },
                body = payload.body.ifBlank { existing.body },
                timestamp = maxOf(existing.timestamp, payload.timestamp),
                type = payload.type,
                read = existing.read || payload.read,
                deliveryStatus = mergedStatus,
                deliveryTimestamp = payload.deliveryTimestamp ?: existing.deliveryTimestamp
            )
            if (merged != existing) {
                messageDao.update(merged)
                _newMessages.emit(merged)
            }
            return
        }
        val message = Message(
            id = payload.messageId,
            threadId = payload.threadId.ifBlank { getOrCreateThreadId(payload.address) },
            address = payload.address,
            body = payload.body,
            timestamp = payload.timestamp,
            type = payload.type,
            read = payload.read,
            deviceId = sourceDevice,
            deliveryStatus = payload.deliveryStatus,
            deliveryTimestamp = payload.deliveryTimestamp
        )
        messageDao.insert(message)
        _newMessages.emit(message)
    }

    private suspend fun handleRemoteReadStatus(payload: SmsSyncPayload) {
        messageDao.getById(payload.messageId)?.let { existing ->
            val merged = existing.copy(
                read = true,
                deliveryStatus = mergeDeliveryStatus(existing.deliveryStatus, payload.deliveryStatus),
                deliveryTimestamp = payload.deliveryTimestamp ?: existing.deliveryTimestamp
            )
            messageDao.update(merged)
            updateSystemSmsReadStatus(merged, true)
        }
    }

    private suspend fun handleRemoteSendRequest(payload: SmsSyncPayload, sourceDevice: String) =
        remoteSendMutex.withLock {
            val role = runCatching { deviceManager.getLocalDevice().role }.getOrNull()
            if (role != DeviceRole.MAIN && role != DeviceRole.CELLULAR_SOURCE) {
                sendSendResult(sourceDevice, payload.messageId, false, null, "Device cannot send SMS")
                return@withLock
            }

            // The request ID is also the local message ID. A retransmitted
            // request therefore returns the previous result instead of
            // sending a second physical SMS.
            val existing = messageDao.getById(payload.messageId)
            if (existing?.deliveryStatus == SmsDeliveryStatus.SENT ||
                existing?.deliveryStatus == SmsDeliveryStatus.DELIVERED
            ) {
                sendSendResult(sourceDevice, payload.messageId, true, existing.id, null)
                return@withLock
            }
            if (existing?.deliveryStatus == SmsDeliveryStatus.FAILED) {
                sendSendResult(
                    sourceDevice,
                    payload.messageId,
                    false,
                    existing.id,
                    "SMS submission previously failed"
                )
                return@withLock
            }
            if (existing?.deliveryStatus == SmsDeliveryStatus.QUEUED) {
                // A retransmitted request must not submit a second physical
                // SMS when the previous process died between telephony
                // submission and the durable status update. Fail closed and
                // let the caller retry explicitly after reconciliation.
                sendSendResult(
                    sourceDevice,
                    payload.messageId,
                    false,
                    existing.id,
                    "SMS submission state is unknown"
                )
                return@withLock
            }

            val messageId = sendSmsLocally(
                address = payload.address,
                body = payload.body,
                simSlot = payload.simSlot,
                messageIdOverride = payload.messageId
            )
            sendSendResult(
                sourceDevice,
                payload.messageId,
                messageId != null,
                messageId,
                if (messageId == null) "SMS submission failed" else null
            )
        }

    private suspend fun sendSendResult(targetDevice: String, requestId: String, success: Boolean, messageId: String?, error: String?) {
        val result = SmsSendResult(requestId, success, messageId, error)
        sendPayload(
            targetDevice,
            SmsSyncPayload(
                messageId = requestId,
                threadId = "",
                address = "",
                body = result.toJson(),
                timestamp = System.currentTimeMillis(),
                type = MessageType.SENT,
                read = true,
                action = SyncAction.SEND_RESULT
            )
        )
    }

    private suspend fun handleRemoteSendResult(payload: SmsSyncPayload) {
        val result = try {
            SmsSendResult.fromJson(payload.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Invalid remote SMS result", e)
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (result.requestId != payload.messageId ||
                !isUuid(result.requestId) ||
                result.timestamp < now - MAX_CLOCK_SKEW_MS ||
                result.timestamp > now + MAX_CLOCK_SKEW_MS
            ) {
                logger.w(TAG, "Ignoring mismatched or stale remote SMS result")
                return
            }
            val current = try {
                messageDao.getById(result.requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Failed to load remote SMS request", e)
                return
            }
            if (current == null || current.deviceId != localDeviceId() ||
                current.deliveryStatus != SmsDeliveryStatus.QUEUED
            ) {
                logger.w(TAG, "Ignoring remote SMS result for an unknown request: ${result.requestId}")
                return
            }
            pendingRemoteSendResults[result.requestId]?.complete(result)
            messageDao.updateDeliveryStatus(
                result.requestId,
                if (result.success) SmsDeliveryStatus.SENT else SmsDeliveryStatus.FAILED,
                result.timestamp
            )
            logger.i(TAG, "Remote SMS result ${result.requestId}: ${result.success}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote SMS result", e)
        }
    }

    override suspend fun getConversations(): List<Conversation> {
        return try {
            messageDao.getAllSnapshot()
                .groupBy { it.threadId.ifBlank { getOrCreateThreadId(it.address) } }
                .map { (threadId, messages) ->
                    val sorted = messages.sortedByDescending { it.timestamp }
                    val last = sorted.first()
                    Conversation(
                        threadId = threadId,
                        address = last.address,
                        contactName = null,
                        lastMessage = last.body,
                        lastTimestamp = last.timestamp,
                        unreadCount = messages.count { !it.read },
                        messageCount = messages.size
                    )
                }
                .sortedByDescending { it.lastTimestamp }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to load conversations", e)
            emptyList()
        }
    }

    private fun readSystemSms(limit: Int): List<Message> {
        val result = mutableListOf<Message>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            val providerId = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val thread = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                val systemAddress = cursor.getString(address).orEmpty()
                val systemBody = cursor.getString(body).orEmpty()
                val systemTimestamp = cursor.getLong(date)
                val systemId = cursor.getLong(providerId).toString()
                result += Message(
                    id = stableSystemMessageId(systemId, systemAddress, systemTimestamp, systemBody),
                    threadId = cursor.getString(thread),
                    address = systemAddress,
                    body = systemBody,
                    timestamp = systemTimestamp,
                    type = mapSystemTypeToMessageType(cursor.getInt(type)),
                    read = cursor.getInt(read) == 1,
                    deviceId = localDeviceId()
                )
            }
        }
        return result
    }

    private fun updateSystemSmsReadStatus(message: Message, read: Boolean) {
        if (!hasReadPermission()) return
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, if (read) 1 else 0)
        }
        if (message.id.toLongOrNull() != null) {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(message.id)
            )
        } else {
            // Receiver/import IDs are content-derived UUIDs. Resolve them
            // back to the provider row using a narrow immutable tuple.
            if (message.timestamp < 0L) return
            val from = (message.timestamp - SYSTEM_MATCH_WINDOW_MS).coerceAtLeast(0L)
            val until = if (message.timestamp > Long.MAX_VALUE - SYSTEM_MATCH_WINDOW_MS) {
                Long.MAX_VALUE
            } else {
                message.timestamp + SYSTEM_MATCH_WINDOW_MS
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND " +
                    "${Telephony.Sms.DATE} BETWEEN ? AND ?",
                arrayOf(message.address, message.body, from.toString(), until.toString())
            )
        }
    }

    private fun resolveSmsManager(simSlot: Int?): SmsManager? {
        if (simSlot == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
            return defaultSmsManager
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            logger.w(TAG, "READ_PHONE_STATE permission is not granted; using default SIM")
            return defaultSmsManager
        }
        return runCatching {
            val subscriptions = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager
            val info = subscriptions?.activeSubscriptionInfoList?.getOrNull(simSlot)
            info?.let { SmsManager.getSmsManagerForSubscriptionId(it.subscriptionId) } ?: defaultSmsManager
        }.getOrDefault(defaultSmsManager)
    }

    /** Avoid Long overflow turning a corrupt provider timestamp into a false match. */
    private fun timestampDistance(first: Long, second: Long): Long {
        if ((first < 0L) != (second < 0L)) return Long.MAX_VALUE
        val difference = first - second
        return if (difference == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(difference)
    }

    private fun createStatusPendingIntent(
        action: String,
        messageId: String,
        partIndex: Int,
        partCount: Int
    ): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            context,
            messageId.hashCode() xor action.hashCode() xor (partIndex * 31) xor (partCount * 131),
            Intent(context, SmsReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_PART_INDEX, partIndex)
                putExtra(EXTRA_PART_COUNT, partCount)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun initializeSmsPartState(messageId: String, partCount: Int) {
        val state = SmsPartState(partCount.coerceAtLeast(1))
        synchronized(smsPartLock) {
            smsPartStates[messageId] = state
            persistSmsPartState(messageId, state)
        }
    }

    private fun loadSmsPartState(messageId: String, partCount: Int): SmsPartState? {
        val expectedCount = partCount.coerceAtLeast(1)
        smsPartStates[messageId]?.let { state ->
            if (state.partCount == expectedCount) return state
        }
        val raw = smsPartPreferences.getString(partKey(messageId), null) ?: return null
        val fields = raw.split('|')
        val count = fields.getOrNull(0)?.toIntOrNull() ?: return null
        if (count != expectedCount || count <= 0) return null
        val state = SmsPartState(
            partCount = count,
            sentParts = parsePartIndexes(fields.getOrNull(2), count),
            deliveredParts = parsePartIndexes(fields.getOrNull(3), count),
            failed = fields.getOrNull(1).toBoolean()
        )
        smsPartStates[messageId] = state
        return state
    }

    private fun persistSmsPartState(messageId: String, state: SmsPartState) {
        val encoded = state.partCount.toString() +
            "|" + state.failed +
            "|" + state.sentParts.sorted().joinToString(",") +
            "|" + state.deliveredParts.sorted().joinToString(",")
        smsPartPreferences.edit().putString(partKey(messageId), encoded).apply()
    }

    private fun parsePartIndexes(encoded: String?, partCount: Int): MutableSet<Int> =
        encoded.orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 0 until partCount }
            .toMutableSet()

    private fun partKey(messageId: String): String = "parts_$messageId"

    private data class SmsPartState(
        val partCount: Int,
        val sentParts: MutableSet<Int> = mutableSetOf(),
        val deliveredParts: MutableSet<Int> = mutableSetOf(),
        var failed: Boolean = false
    )

    private fun hasSendPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    private fun hasReadPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    private fun getOrCreateThreadId(address: String) = address.hashCode().toString()
    private fun localDeviceId() = runCatching { deviceManager.getLocalDevice().id }.getOrDefault("local")
    private fun stableSystemMessageId(
        providerId: String,
        address: String,
        timestamp: Long,
        body: String
    ): String = UUID.nameUUIDFromBytes(
        "$providerId|$address|$timestamp|$body".toByteArray(Charsets.UTF_8)
    ).toString()

    private fun isValidSmsAddress(address: String): Boolean {
        if (address.isBlank() || address.length > MAX_ADDRESS_LENGTH) return false
        return address.any(Char::isDigit) &&
            address.all { it.isDigit() || it in "+*#(),; -" }
    }

    private fun isValidSmsBody(body: String): Boolean =
        body.isNotBlank() && body.length <= MAX_BODY_LENGTH

    private fun addressesMatch(first: String, second: String): Boolean {
        val firstDigits = first.filter(Char::isDigit)
        val secondDigits = second.filter(Char::isDigit)
        return if (firstDigits.length >= 3 && secondDigits.length >= 3) {
            firstDigits == secondDigits
        } else {
            first.trim() == second.trim()
        }
    }

    private fun isValidSmsPayload(payload: SmsSyncPayload, envelopeTimestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        if (payload.messageId.isBlank() || payload.messageId.length > 128 ||
            payload.address.length > MAX_ADDRESS_LENGTH || payload.body.length > MAX_BODY_LENGTH ||
            payload.timestamp <= 0L ||
            payload.timestamp > now + MAX_FUTURE_TIMESTAMP_MS ||
            envelopeTimestamp < now - MAX_CLOCK_SKEW_MS ||
            envelopeTimestamp > now + MAX_CLOCK_SKEW_MS
        ) return false

        return when (payload.action) {
            SyncAction.NEW_MESSAGE ->
                isValidSmsAddress(payload.address) && isValidSmsBody(payload.body)
            SyncAction.MARK_READ, SyncAction.DELETE_MESSAGE -> true
            SyncAction.SEND_REQUEST ->
                isUuid(payload.messageId) && isValidSmsAddress(payload.address) &&
                    isValidSmsBody(payload.body) && (payload.simSlot == null || payload.simSlot >= 0)
            SyncAction.SEND_RESULT -> isUuid(payload.messageId) && payload.body.isNotBlank()
        }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun mergeDeliveryStatus(
        current: SmsDeliveryStatus,
        incoming: SmsDeliveryStatus
    ): SmsDeliveryStatus = when {
        current == SmsDeliveryStatus.DELIVERED -> SmsDeliveryStatus.DELIVERED
        current == SmsDeliveryStatus.FAILED -> current
        incoming == SmsDeliveryStatus.FAILED -> SmsDeliveryStatus.FAILED
        incoming == SmsDeliveryStatus.DELIVERED -> SmsDeliveryStatus.DELIVERED
        incoming == SmsDeliveryStatus.SENT -> SmsDeliveryStatus.SENT
        else -> current
    }

    private fun mapSystemTypeToMessageType(type: Int): MessageType = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
        Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageType.DRAFT
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageType.OUTBOX
        Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageType.FAILED
        else -> MessageType.INBOX
    }

    companion object {
        const val ACTION_SMS_SENT = "com.smslink.sms.ACTION_SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.smslink.sms.ACTION_SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "com.smslink.sms.EXTRA_MESSAGE_ID"
        const val EXTRA_PART_INDEX = "com.smslink.sms.EXTRA_PART_INDEX"
        const val EXTRA_PART_COUNT = "com.smslink.sms.EXTRA_PART_COUNT"
        private const val TAG = "SmsManagerImpl"
        private const val MAX_QUERY_LIMIT = 5000
        private const val MAX_ADDRESS_LENGTH = 256
        private const val MAX_BODY_LENGTH = 100_000
        private const val MAX_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L
        private const val MAX_FUTURE_TIMESTAMP_MS = 10 * 60 * 1000L
        private const val SYSTEM_MATCH_WINDOW_MS = 5 * 60 * 1000L
        private const val REMOTE_SEND_RESULT_TIMEOUT_MS = 15_000L
        private const val SMS_PART_PREFERENCES = "smslink_sms_parts"
    }
}
