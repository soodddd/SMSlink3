package com.smslink.sms

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.google.gson.JsonParser
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import com.smslink.core.model.SmsMessage
import com.smslink.device.IDeviceManager
import com.smslink.network.model.MessageType as NetworkMessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import com.smslink.sms.model.Conversation
import com.smslink.sms.model.SmsSendResult
import com.smslink.sms.model.SmsSyncPayload
import com.smslink.sms.model.SyncAction
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val messageTransport: IMessageTransport,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger
) : ISmsManager {
    constructor(
        context: Context,
        messageDao: MessageDao,
        logger: ILogger
    ) : this(
        context = context,
        messageDao = messageDao,
        messageTransport = object : IMessageTransport {
            override fun sendMessage(deviceId: String, message: NetworkMessage) = flowOf(
                com.smslink.network.model.SendResult(
                    success = false,
                    messageId = message.messageId,
                    error = "noop"
                )
            )

            override fun receiveMessages() = emptyFlow<NetworkMessage>()
            override suspend fun sendAck(messageId: String, deviceId: String) = Unit
            override fun getPendingMessageCount(): Int = 0
            override suspend fun clearQueue() = Unit
        },
        deviceManager = object : IDeviceManager {
            override fun startDiscovery() = Unit
            override fun stopDiscovery() = Unit
            override fun pairDevice(deviceId: String, qrCode: String) = emptyFlow<com.smslink.core.model.PairResult>()
            override fun getConnectedDevices() = flowOf(emptyList<Device>())
            override suspend fun setDeviceRole(deviceId: String, role: com.smslink.core.model.DeviceRole) = Unit
            override suspend fun removeDevice(deviceId: String) = Unit
            override fun getLocalDevice() = Device(
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

    private val _newMessages = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 64)
    private val smsManager = runCatching { SmsManager.getDefault() }.getOrNull()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncedMessageIds = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        startListeningRemoteMessages()
    }

    override fun getMessages(limit: Int): Flow<List<Message>> {
        logger.d(TAG, "Getting messages with limit: $limit")
        return messageDao.getAll(limit)
    }

    override suspend fun sendMessage(address: String, body: String, simSlot: Int?): Boolean {
        return try {
            logger.d(TAG, "Sending SMS to: $address, simSlot: $simSlot")

            val manager = if (simSlot != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                if (subscriptionInfoList != null && simSlot < subscriptionInfoList.size) {
                    SmsManager.getSmsManagerForSubscriptionId(subscriptionInfoList[simSlot].subscriptionId)
                } else {
                    smsManager
                }
            } else {
                smsManager
            }

            val parts = manager?.divideMessage(body) ?: arrayListOf(body)
            if (parts.size == 1) {
                manager?.sendTextMessage(address, null, body, null, null)
            } else {
                manager?.sendMultipartTextMessage(address, null, parts, null, null)
            }

            val message = Message(
                id = UUID.randomUUID().toString(),
                threadId = getOrCreateThreadId(address),
                address = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                type = MessageType.SENT,
                read = true,
                deviceId = getDeviceId()
            )
            messageDao.insert(message)
            scope.launch { syncSmsToDevices(message, simSlot) }

            logger.i(TAG, "SMS sent successfully to: $address")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send SMS", e)
            false
        }
    }

    override suspend fun syncMessage(message: SmsMessage, targetDeviceId: String) {
        logger.d(TAG, "Syncing message to device: $targetDeviceId")
        val msg = Message(
            id = message.id,
            threadId = "0",
            address = message.address,
            body = message.body,
            timestamp = message.timestamp,
            type = MessageType.INBOX,
            read = message.isRead,
            deviceId = getDeviceId()
        )
        syncSmsToDevice(msg, targetDeviceId)
    }

    private suspend fun syncSmsToDevices(message: Message, simSlot: Int? = null) {
        try {
            if (syncedMessageIds.contains(message.id)) {
                logger.d(TAG, "Message already synced: ${message.id}")
                return
            }

            deviceManager.getConnectedDevices().collect { devices ->
                devices.forEach { device ->
                    syncSmsToDevice(message, device.id, simSlot)
                }
            }

            syncedMessageIds.add(message.id)
            logger.d(TAG, "Message synced to all devices: ${message.id}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync message to devices", e)
        }
    }

    private suspend fun syncSmsToDevice(message: Message, targetDeviceId: String, simSlot: Int? = null) {
        try {
            val payload = SmsSyncPayload(
                messageId = message.id,
                threadId = message.threadId,
                address = message.address,
                body = message.body,
                timestamp = message.timestamp,
                type = message.type,
                read = message.read,
                simSlot = simSlot,
                action = SyncAction.NEW_MESSAGE
            )

            val networkMessage = NetworkMessage(
                messageType = NetworkMessageType.SMS,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = getDeviceId(),
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = JsonParser.parseString(payload.toJson()).asJsonObject
            )

            messageTransport.sendMessage(targetDeviceId, networkMessage).collect { result ->
                if (result.success) {
                    logger.i(TAG, "SMS synced to device: $targetDeviceId")
                } else {
                    logger.e(TAG, "Failed to sync SMS to device: $targetDeviceId, error: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync SMS to device: $targetDeviceId", e)
        }
    }

    override fun observeNewMessages(): Flow<Message> = _newMessages.asSharedFlow()

    override suspend fun markAsRead(messageId: String) {
        logger.d(TAG, "Marking message as read: $messageId")
        messageDao.markAsRead(messageId)

        try {
            val message = messageDao.getById(messageId)
            if (message != null) {
                updateSystemSmsReadStatus(message, true)
                syncReadStatusToDevices(messageId)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update system SMS read status", e)
        }
    }

    private suspend fun syncReadStatusToDevices(messageId: String) {
        try {
            val message = messageDao.getById(messageId) ?: return

            val payload = SmsSyncPayload(
                messageId = message.id,
                threadId = message.threadId,
                address = message.address,
                body = message.body,
                timestamp = message.timestamp,
                type = message.type,
                read = true,
                action = SyncAction.MARK_READ
            )

            deviceManager.getConnectedDevices().collect { devices ->
                devices.forEach { device ->
                    val networkMessage = NetworkMessage(
                        messageType = NetworkMessageType.SMS,
                        messageId = UUID.randomUUID().toString(),
                        sourceDevice = getDeviceId(),
                        targetDevice = device.id,
                        timestamp = System.currentTimeMillis(),
                        payload = JsonParser.parseString(payload.toJson()).asJsonObject
                    )

                    messageTransport.sendMessage(device.id, networkMessage).collect { result ->
                        if (result.success) {
                            logger.d(TAG, "Read status synced to device: ${device.id}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync read status", e)
        }
    }

    override suspend fun deleteMessage(messageId: String) {
        logger.d(TAG, "Deleting message: $messageId")
        messageDao.deleteById(messageId)
    }

    suspend fun syncFromSystem(limit: Int = 100) {
        logger.d(TAG, "Syncing messages from system database")

        try {
            val messages = readSystemSms(limit)
            if (messages.isNotEmpty()) {
                messageDao.insertAll(messages)
                logger.i(TAG, "Synced ${messages.size} messages from system")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync messages from system", e)
        }
    }

    suspend fun notifyNewMessage(message: Message) {
        logger.d(TAG, "New message received: ${message.id}")
        messageDao.insert(message)
        _newMessages.emit(message)
        scope.launch { syncSmsToDevices(message) }
    }

    private fun startListeningRemoteMessages() {
        scope.launch {
            messageTransport.receiveMessages()
                .filter { it.messageType == NetworkMessageType.SMS }
                .catch { e ->
                    logger.e(TAG, "Error receiving remote messages", e)
                }
                .collect { networkMessage ->
                    handleRemoteMessage(networkMessage)
                }
        }
    }

    private suspend fun handleRemoteMessage(networkMessage: NetworkMessage) {
        try {
            val payload = SmsSyncPayload.fromJson(networkMessage.payload.toString())

            when (payload.action) {
                SyncAction.NEW_MESSAGE -> handleRemoteSms(payload, networkMessage.sourceDevice)
                SyncAction.MARK_READ -> handleRemoteReadStatus(payload)
                SyncAction.DELETE_MESSAGE -> handleRemoteDelete(payload)
                SyncAction.SEND_REQUEST -> handleRemoteSendRequest(payload, networkMessage.sourceDevice)
                SyncAction.SEND_RESULT -> handleRemoteSendResult(payload)
            }

            messageTransport.sendAck(networkMessage.messageId, networkMessage.sourceDevice)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote message", e)
        }
    }

    private suspend fun handleRemoteSms(payload: SmsSyncPayload, sourceDevice: String) {
        try {
            val existing = messageDao.getById(payload.messageId)
            if (existing != null) {
                logger.d(TAG, "Message already exists: ${payload.messageId}")
                return
            }

            val message = Message(
                id = payload.messageId,
                threadId = payload.threadId,
                address = payload.address,
                body = payload.body,
                timestamp = payload.timestamp,
                type = payload.type,
                read = payload.read,
                deviceId = sourceDevice
            )
            messageDao.insert(message)
            syncedMessageIds.add(message.id)
            _newMessages.emit(message)
            logger.i(TAG, "Remote SMS saved: ${payload.messageId} from device: $sourceDevice")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote SMS", e)
        }
    }

    private suspend fun handleRemoteReadStatus(payload: SmsSyncPayload) {
        try {
            messageDao.markAsRead(payload.messageId)
            val message = messageDao.getById(payload.messageId)
            if (message != null) {
                updateSystemSmsReadStatus(message, true)
            }
            logger.d(TAG, "Remote read status updated: ${payload.messageId}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote read status", e)
        }
    }

    private suspend fun handleRemoteDelete(payload: SmsSyncPayload) {
        try {
            messageDao.deleteById(payload.messageId)
            logger.d(TAG, "Remote message deleted: ${payload.messageId}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote delete", e)
        }
    }

    private suspend fun handleRemoteSendRequest(payload: SmsSyncPayload, sourceDevice: String) {
        try {
            val localDevice = deviceManager.getLocalDevice()
            if (localDevice.role != com.smslink.core.model.DeviceRole.MAIN &&
                localDevice.role != com.smslink.core.model.DeviceRole.CELLULAR_SOURCE) {
                logger.w(TAG, "This device cannot send SMS, role: ${localDevice.role}")
                sendSendResult(sourceDevice, payload.messageId, false, null, "Device cannot send SMS")
                return
            }

            val success = sendMessage(payload.address, payload.body, payload.simSlot)
            val messageId = if (success) UUID.randomUUID().toString() else null
            sendSendResult(sourceDevice, payload.messageId, success, messageId, if (success) null else "Send failed")
            logger.i(TAG, "Remote send request handled: ${payload.messageId}, success: $success")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote send request", e)
            sendSendResult(sourceDevice, payload.messageId, false, null, e.message)
        }
    }

    private suspend fun sendSendResult(
        targetDevice: String,
        requestId: String,
        success: Boolean,
        messageId: String?,
        error: String?
    ) {
        try {
            val result = SmsSendResult(
                requestId = requestId,
                success = success,
                messageId = messageId,
                error = error
            )

            val payload = SmsSyncPayload(
                messageId = requestId,
                threadId = "",
                address = "",
                body = result.toJson(),
                timestamp = System.currentTimeMillis(),
                type = MessageType.SENT,
                read = true,
                action = SyncAction.SEND_RESULT
            )

            val networkMessage = NetworkMessage(
                messageType = NetworkMessageType.SMS,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = getDeviceId(),
                targetDevice = targetDevice,
                timestamp = System.currentTimeMillis(),
                payload = JsonParser.parseString(payload.toJson()).asJsonObject
            )

            messageTransport.sendMessage(targetDevice, networkMessage).collect { }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send send result", e)
        }
    }

    private suspend fun handleRemoteSendResult(payload: SmsSyncPayload) {
        try {
            val result = SmsSendResult.fromJson(payload.body)
            logger.i(TAG, "Remote send result: requestId=${result.requestId}, success=${result.success}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote send result", e)
        }
    }

    override suspend fun getConversations(): List<Conversation> {
        return try {
            val conversations = mutableListOf<Conversation>()

            val uri = Telephony.Sms.Conversations.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.Conversations.THREAD_ID,
                Telephony.Sms.Conversations.MESSAGE_COUNT
            )

            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )

            cursor?.use {
                val threadIdIndex = it.getColumnIndexOrThrow(Telephony.Sms.Conversations.THREAD_ID)
                val messageCountIndex = it.getColumnIndexOrThrow(Telephony.Sms.Conversations.MESSAGE_COUNT)

                while (it.moveToNext()) {
                    val threadId = it.getString(threadIdIndex)
                    val messageCount = it.getInt(messageCountIndex)

                    val conversationInfo = getConversationInfo(threadId)
                    if (conversationInfo != null) {
                        conversations.add(
                            Conversation(
                                threadId = threadId,
                                address = conversationInfo.address,
                                contactName = conversationInfo.contactName,
                                lastMessage = conversationInfo.lastMessage,
                                lastTimestamp = conversationInfo.lastTimestamp,
                                unreadCount = conversationInfo.unreadCount,
                                messageCount = messageCount
                            )
                        )
                    }
                }
            }

            conversations.sortedByDescending { it.lastTimestamp }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get conversations", e)
            emptyList()
        }
    }

    private fun getConversationInfo(threadId: String): ConversationInfo? {
        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )

            val cursor = context.contentResolver.query(
                uri,
                projection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId),
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val readIndex = it.getColumnIndexOrThrow(Telephony.Sms.READ)

                    val address = it.getString(addressIndex) ?: ""
                    val lastMessage = it.getString(bodyIndex) ?: ""
                    val lastTimestamp = it.getLong(dateIndex)

                    var unreadCount = 0
                    do {
                        val read = it.getInt(readIndex) == 1
                        if (!read) unreadCount++
                    } while (it.moveToNext())

                    return ConversationInfo(
                        address = address,
                        contactName = getContactName(address),
                        lastMessage = lastMessage,
                        lastTimestamp = lastTimestamp,
                        unreadCount = unreadCount
                    )
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get conversation info for thread: $threadId", e)
        }
        return null
    }

    private fun getContactName(address: String): String? = null

    private data class ConversationInfo(
        val address: String,
        val contactName: String?,
        val lastMessage: String,
        val lastTimestamp: Long,
        val unreadCount: Int
    )

    private fun readSystemSms(limit: Int): List<Message> {
        val messages = mutableListOf<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )

        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readIndex = it.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (it.moveToNext()) {
                val id = it.getString(idIndex)
                val threadId = it.getString(threadIdIndex)
                val address = it.getString(addressIndex) ?: ""
                val body = it.getString(bodyIndex) ?: ""
                val date = it.getLong(dateIndex)
                val type = it.getInt(typeIndex)
                val read = it.getInt(readIndex) == 1

                messages.add(
                    Message(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = body,
                        timestamp = date,
                        type = mapSystemTypeToMessageType(type),
                        read = read,
                        deviceId = getDeviceId()
                    )
                )
            }
        }

        return messages
    }

    private fun updateSystemSmsReadStatus(message: Message, read: Boolean) {
        val uri = Telephony.Sms.CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, if (read) 1 else 0)
        }

        context.contentResolver.update(
            uri,
            values,
            "${Telephony.Sms._ID} = ?",
            arrayOf(message.id)
        )
    }

    private fun getOrCreateThreadId(address: String): String {
        return address.hashCode().toString()
    }

    private fun getDeviceId(): String {
        return runCatching { deviceManager.getLocalDevice().id }.getOrElse { "local" }
    }

    private fun mapSystemTypeToMessageType(type: Int): MessageType {
        return when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
            Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageType.DRAFT
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> MessageType.OUTBOX
            Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageType.FAILED
            else -> MessageType.INBOX
        }
    }

    companion object {
        private const val TAG = "SmsManagerImpl"
    }
}
