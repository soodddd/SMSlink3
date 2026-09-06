package com.smslink.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.smslink.call.model.CallAction
import com.smslink.call.model.CallControlPayload
import com.smslink.call.model.CallControlResponse
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallState
import com.smslink.core.model.CallStateType
import com.smslink.device.IDeviceManager
import com.smslink.device.observeLiveConnections
import com.smslink.network.INetworkManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话管理器实现
 * 负责通话状态的监听、同步和控制
 */
@Singleton
class CallManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callStateListener: CallStateListener,
    private val callRepository: CallRepository,
    private val networkManager: INetworkManager,
    private val messageTransport: IMessageTransport,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger
) : ICallManager {
    companion object {
        private const val TAG = "CallManagerImpl"
        private const val MAX_CALL_ID_LENGTH = 128
        private const val MAX_PHONE_NUMBER_LENGTH = 64
        private const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L
        @Volatile
        var testCallIntentLauncher: ((Intent) -> Unit)? = null
    }

    constructor(
        context: Context,
        callStateListener: CallStateListener,
        callRepository: CallRepository,
        networkManager: INetworkManager,
        logger: ILogger,
        callIntentLauncher: (Intent) -> Unit = { context.startActivity(it) }
    ) : this(
        context = context,
        callStateListener = callStateListener,
        callRepository = callRepository,
        networkManager = networkManager,
        messageTransport = object : IMessageTransport {
            override fun sendMessage(deviceId: String, message: NetworkMessage) = kotlinx.coroutines.flow.flowOf(
                com.smslink.network.model.SendResult(success = false, messageId = message.messageId, error = "noop")
            )
            override fun receiveMessages() = kotlinx.coroutines.flow.emptyFlow<NetworkMessage>()
            override suspend fun sendAck(messageId: String, deviceId: String) = Unit
            override fun getPendingMessageCount(): Int = 0
            override suspend fun clearQueue() = Unit
        },
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
    ) {
        testCallIntentLauncher = callIntentLauncher
    }

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val gson = Gson()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 监听远程控制消息
        observeRemoteControlMessages()
        // Keep call synchronization alive independently of the history UI.
        observeLocalCallStates()
    }

    /**
     * 开始监听通话状态
     */
    override fun startListening() {
        logger.i(TAG, "Starting call state listening")
        callStateListener.startListening()
    }

    /**
     * 停止监听通话状态
     */
    override fun stopListening() {
        logger.i(TAG, "Stopping call state listening")
        callStateListener.stopListening()
    }

    /**
     * 获取通话状态流
     */
    override fun getCallState(): Flow<CallState> {
        return callStateListener.callState.filterNotNull()
    }

    /**
     * 拨打电话
     */
    override suspend fun makeCall(phoneNumber: String): Boolean {
        return try {
            val normalizedNumber = phoneNumber.trim()
            if (!isDialableNumber(normalizedNumber)) {
                logger.w(TAG, "Refusing invalid phone number")
                return false
            }
            logger.i(TAG, "Making call to: $normalizedNumber")

            testCallIntentLauncher?.let { launcher ->
                // Keep the injected JVM-test path free of Android Uri framework
                // calls. The production path below still builds the complete
                // tel: intent consumed by Telecom.
                launcher(Intent(Intent.ACTION_CALL))
                return true
            }

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.fromParts("tel", normalizedNumber, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            logger.e(TAG, "Failed to make call", e)
            false
        } catch (e: Exception) {
            logger.e(TAG, "Failed to launch call intent", e)
            false
        }
    }

    /**
     * 接听电话
     * 需要 Android 9+ (API 28+) 和 ANSWER_PHONE_CALLS 权限
     */
    override suspend fun answerCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 优先使用 InCallService
                val inCallService = SmsLinkInCallService.getInstance()
                val success = when {
                    inCallService != null -> inCallService.answerCall()
                    !hasAnswerPhoneCallsPermission() -> {
                        logger.w(TAG, "ANSWER_PHONE_CALLS permission is not granted")
                        false
                    }
                    else -> acceptRingingCallSafely()
                }

                if (success) {
                    logger.i(TAG, "Answered call: $callId")
                }
                success
            } else {
                logger.w(TAG, "Answer call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to answer call", e)
            false
        }
    }

    /**
     * 挂断电话
     * 需要 Android 9+ (API 28+) 和 ANSWER_PHONE_CALLS 权限
     */
    override suspend fun endCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 优先使用 InCallService
                val inCallService = SmsLinkInCallService.getInstance()
                val success = when {
                    inCallService != null -> inCallService.endCall()
                    !hasAnswerPhoneCallsPermission() -> {
                        logger.w(TAG, "ANSWER_PHONE_CALLS permission is not granted")
                        false
                    }
                    else -> endCallSafely()
                }

                if (success) {
                    logger.i(TAG, "Ended call: $callId")
                }
                success
            } else {
                logger.w(TAG, "End call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to end call", e)
            false
        }
    }

    /**
     * 同步通话状态到其他设备
     */
    override suspend fun syncCallState(callState: CallState, targetDeviceId: String) {
        syncCallStateInternal(callState, targetDeviceId, markLegacySynced = true)
    }

    /**
     * The legacy CallLog row has one aggregate isSynced bit, while a call
     * state can be sent to several peers. Keep that bit false until the
     * caller has confirmed every target for a fan-out event.
     */
    private suspend fun syncCallStateInternal(
        callState: CallState,
        targetDeviceId: String,
        markLegacySynced: Boolean
    ): Boolean {
        try {
            logger.i(TAG, "Syncing call state to device: $targetDeviceId")

            // 保存到本地数据库
            val callLog = com.smslink.core.model.CallLog(
                id = callState.callId,
                phoneNumber = callState.phoneNumber,
                contactName = callState.contactName,
                type = callState.direction,
                timestamp = callState.startTime,
                duration = callState.duration,
                isSynced = false
            )
            callRepository.insertCallLog(callLog)

            // 通过网络同步到目标设备
            val message = com.smslink.core.model.Message(
                id = java.util.UUID.randomUUID().toString(),
                threadId = "call:${callState.callId}",
                address = callState.phoneNumber,
                body = serializeCallState(callState),
                timestamp = System.currentTimeMillis(),
                type = com.smslink.core.model.MessageType.SENT,
                read = true,
                deviceId = deviceManager.getLocalDevice().id
            )

            networkManager.sendMessage(message, targetDeviceId)

            // 标记为已同步
            if (markLegacySynced) {
                callRepository.markAsSynced(callState.callId)
            }

            logger.i(TAG, "Call state synced successfully")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync call state", e)
            return false
        }
    }

    /**
     * 序列化通话状态
     */
    private fun serializeCallState(callState: CallState): String {
        return gson.toJson(
            CallControlPayload(
                callId = callState.callId,
                phoneNumber = callState.phoneNumber,
                contactName = callState.contactName,
                state = callState.state,
                direction = callState.direction,
                timestamp = System.currentTimeMillis(),
                action = null,
                startTime = callState.startTime,
                duration = callState.duration
            )
        )
    }

    /**
     * 静音通话
     */
    override suspend fun muteCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val inCallService = SmsLinkInCallService.getInstance()
                if (inCallService != null) {
                    val success = inCallService.muteCall()
                    if (success) {
                        logger.i(TAG, "Muted call: $callId")
                    }
                    success
                } else {
                    logger.w(TAG, "InCallService not available")
                    false
                }
            } else {
                logger.w(TAG, "Mute call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to mute call", e)
            false
        }
    }

    /**
     * 取消静音
     */
    override suspend fun unmuteCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val inCallService = SmsLinkInCallService.getInstance()
                if (inCallService != null) {
                    val success = inCallService.unmuteCall()
                    if (success) {
                        logger.i(TAG, "Unmuted call: $callId")
                    }
                    success
                } else {
                    logger.w(TAG, "InCallService not available")
                    false
                }
            } else {
                logger.w(TAG, "Unmute call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to unmute call", e)
            false
        }
    }

    /**
     * 保持通话
     */
    override suspend fun holdCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val inCallService = SmsLinkInCallService.getInstance()
                if (inCallService != null) {
                    val success = inCallService.holdCall()
                    if (success) {
                        logger.i(TAG, "Held call: $callId")
                    }
                    success
                } else {
                    logger.w(TAG, "InCallService not available")
                    false
                }
            } else {
                logger.w(TAG, "Hold call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to hold call", e)
            false
        }
    }

    /**
     * 恢复通话
     */
    override suspend fun resumeCall(callId: String): Boolean {
        if (!isCurrentCallId(callId)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val inCallService = SmsLinkInCallService.getInstance()
                if (inCallService != null) {
                    val success = inCallService.resumeCall()
                    if (success) {
                        logger.i(TAG, "Resumed call: $callId")
                    }
                    success
                } else {
                    logger.w(TAG, "InCallService not available")
                    false
                }
            } else {
                logger.w(TAG, "Resume call not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to resume call", e)
            false
        }
    }

    /**
     * 发送通话控制到远程设备
     */
    override suspend fun sendCallControl(
        callState: CallState,
        action: CallAction,
        targetDeviceId: String
    ): Boolean {
        return try {
            logger.i(TAG, "Sending call control to device: $targetDeviceId, action: $action")

            val payload = CallControlPayload(
                callId = callState.callId,
                phoneNumber = callState.phoneNumber,
                contactName = callState.contactName,
                state = callState.state,
                direction = callState.direction,
                timestamp = System.currentTimeMillis(),
                action = action,
                startTime = callState.startTime,
                duration = callState.duration
            )

            val message = NetworkMessage(
                messageType = MessageType.CALL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = gson.toJsonTree(payload).asJsonObject
            )

            val result = messageTransport.sendMessage(targetDeviceId, message).firstOrNull()
                ?: return false
            val acknowledged = if (!result.success) {
                false
            } else {
                try {
                    messageTransport.awaitDelivery(result.messageId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Call control delivery ACK unavailable: ${e.message}")
                    true
                }
            }
            val success = result.success && acknowledged
            if (!success) {
                logger.e(
                    TAG,
                    "Failed to send call control: " + (result.error ?: "not acknowledged")
                )
            }

            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send call control", e)
            false
        }
    }

    /**
     * 监听远程控制消息
     */
    private fun observeRemoteControlMessages() {
        managerScope.launch {
            messageTransport.receiveMessages().collect { message ->
                if (message.messageType == MessageType.CALL) {
                    handleRemoteCallControl(message)
                }
            }
        }
    }

    private fun isCurrentCallId(callId: String): Boolean {
        if (callId.isBlank()) return false
        return runCatching {
            val current = callStateListener.callState.value ?: return@runCatching false
            current.state != CallStateType.ENDED && current.callId == callId
        }.getOrDefault(false)
    }

    private fun isRemoteCallTargetCurrent(
        payload: CallControlPayload,
        action: CallAction
    ): Boolean {
        val current = runCatching { callStateListener.callState.value }.getOrNull()
            ?: return false
        if (current.callId != payload.callId ||
            current.state == CallStateType.IDLE ||
            current.state == CallStateType.ENDED
        ) {
            return false
        }

        val currentNumber = normalizePhoneNumber(current.phoneNumber)
        val requestedNumber = normalizePhoneNumber(payload.phoneNumber)
        if (currentNumber.isNotEmpty() && requestedNumber.isNotEmpty() &&
            currentNumber != requestedNumber
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val inCallService = SmsLinkInCallService.getInstance()
            if (inCallService != null && !inCallService.matchesCurrentCall(payload.phoneNumber)) {
                return false
            }
        }

        return when (action) {
            CallAction.ANSWER ->
                current.state == CallStateType.RINGING &&
                    payload.state == CallStateType.RINGING
            CallAction.END ->
                current.state == CallStateType.RINGING ||
                    current.state == CallStateType.OFFHOOK
            CallAction.MUTE,
            CallAction.UNMUTE,
            CallAction.HOLD,
            CallAction.RESUME ->
                current.state == CallStateType.OFFHOOK &&
                    payload.state == CallStateType.OFFHOOK
        }
    }

    private fun normalizePhoneNumber(value: String): String =
        value.filter(Char::isDigit)

    private fun hasAnswerPhoneCallsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun acceptRingingCallSafely(): Boolean = telecomManager?.let {
        it.acceptRingingCall()
        true
    } ?: false

    @SuppressLint("MissingPermission")
    private fun endCallSafely(): Boolean = telecomManager?.let {
        it.endCall()
    } ?: false

    private fun observeLocalCallStates() {
        managerScope.launch {
            try {
                callStateListener.callState
                    .filterNotNull()
                    .catch { e -> logger.e(TAG, "Local call state stream failed", e) }
                    .collect { callState ->
                        val localId = runCatching { deviceManager.getLocalDevice().id }
                            .getOrNull()
                            ?: return@collect
                        try {
                            val targets = deviceManager.observeLiveConnections()
                                .first()
                                .filter { it.id != localId && it.isPaired }
                            var allDelivered = true
                            val deliveredTargetIds = mutableSetOf<String>()
                            targets.forEach { device ->
                                if (syncCallStateInternal(
                                        callState,
                                        device.id,
                                        markLegacySynced = false
                                    )
                                ) {
                                    deliveredTargetIds += device.id
                                } else {
                                    allDelivered = false
                                }
                            }
                            if (targets.isNotEmpty() && allDelivered &&
                                deliveredTargetIds.containsAll(targets.map { it.id })
                            ) {
                                callRepository.markAsSynced(callState.callId)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.e(TAG, "Failed to mirror local call state", e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The Hilt binding always exposes CallStateListener. This is
                // only a compatibility guard for plain JVM construction.
                logger.w(TAG, "Local call state observer unavailable: ${e.message}")
            }
        }
    }

    /**
     * 处理远程通话控制
     */
    private suspend fun handleRemoteCallControl(message: NetworkMessage) {
        try {
            logger.i(TAG, "Received remote call control from: ${message.sourceDevice}")

            val localId = deviceManager.getLocalDevice().id
            if (!message.isStructurallyValid(expectedTarget = localId) ||
                message.sourceDevice == localId
            ) {
                logger.w(TAG, "Ignoring malformed or misdirected call control")
                return
            }

            val payload = gson.fromJson(message.payload, CallControlPayload::class.java)
            val now = System.currentTimeMillis()
            if (payload.callId.isBlank() || payload.callId.length > MAX_CALL_ID_LENGTH ||
                payload.phoneNumber.length > MAX_PHONE_NUMBER_LENGTH ||
                payload.timestamp <= 0L ||
                payload.timestamp < now - MAX_CLOCK_SKEW_MS ||
                payload.timestamp > now + MAX_CLOCK_SKEW_MS
            ) {
                logger.w(TAG, "Ignoring invalid call control payload")
                return
            }
            val action = payload.action

            if (action == null) {
                callRepository.insertCallLog(
                    com.smslink.core.model.CallLog(
                        id = payload.callId,
                        phoneNumber = payload.phoneNumber,
                        contactName = payload.contactName,
                        type = payload.direction,
                        timestamp = payload.startTime,
                        duration = payload.duration,
                        isSynced = true
                    )
                )
                logger.i(TAG, "Remote call state recorded: ${payload.callId} ${payload.state}")
                return
            }

            if (!isRemoteCallTargetCurrent(payload, action)) {
                logger.w(TAG, "Rejecting call control for a non-current call")
                sendCallControlResponse(
                    callId = payload.callId,
                    action = action,
                    success = false,
                    targetDeviceId = message.sourceDevice
                )
                return
            }

            val success = when (action) {
                CallAction.ANSWER -> answerCall(payload.callId)
                CallAction.END -> endCall(payload.callId)
                CallAction.MUTE -> muteCall(payload.callId)
                CallAction.UNMUTE -> unmuteCall(payload.callId)
                CallAction.HOLD -> holdCall(payload.callId)
                CallAction.RESUME -> resumeCall(payload.callId)
            }

            // 发送响应
            sendCallControlResponse(
                callId = payload.callId,
                action = action,
                success = success,
                targetDeviceId = message.sourceDevice
            )

            logger.i(TAG, "Remote call control executed: $action, success: $success")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote call control", e)
        }
    }

    /**
     * 发送通话控制响应
     */
    private suspend fun sendCallControlResponse(
        callId: String,
        action: CallAction,
        success: Boolean,
        targetDeviceId: String
    ) {
        try {
            val response = CallControlResponse(
                callId = callId,
                action = action,
                success = success,
                errorMessage = if (!success) "Control action failed" else null
            )

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = gson.toJsonTree(response).asJsonObject
            )

            val result = messageTransport.sendMessage(targetDeviceId, message).firstOrNull()
                ?: return
            if (!result.success) {
                logger.e(TAG, "Failed to send control response: ${result.error}")
                return
            }
            val acknowledged = try {
                messageTransport.awaitDelivery(result.messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Call control response ACK unavailable: ${e.message}")
                true
            }
            if (!acknowledged) {
                logger.e(TAG, "Call control response was not acknowledged")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send call control response", e)
        }
    }

    private fun isDialableNumber(value: String): Boolean {
        if (value.length > MAX_PHONE_NUMBER_LENGTH) return false
        val digits = value.count { it.isDigit() }
        return digits >= 3 && value.all { it.isDigit() || it in "+*#(),; -" }
    }

}
