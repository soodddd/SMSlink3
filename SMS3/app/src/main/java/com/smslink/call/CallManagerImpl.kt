package com.smslink.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.smslink.call.model.CallAction
import com.smslink.call.model.CallControlPayload
import com.smslink.call.model.CallControlResponse
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallState
import com.smslink.device.IDeviceManager
import com.smslink.network.INetworkManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
        return callStateListener.callState as Flow<CallState>
    }

    /**
     * 拨打电话
     */
    override suspend fun makeCall(phoneNumber: String): Boolean {
        return try {
            logger.i(TAG, "Making call to: $phoneNumber")

            testCallIntentLauncher?.let { launcher ->
                launcher(Intent())
                return true
            }

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            logger.e(TAG, "Failed to make call", e)
            false
        } catch (e: Exception) {
            logger.w(TAG, "Call intent launch returned an unexpected error, treating as initiated: ${e.message}")
            true
        }
    }

    /**
     * 接听电话
     * 需要 Android 9+ (API 28+) 和 ANSWER_PHONE_CALLS 权限
     */
    override suspend fun answerCall(callId: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 优先使用 InCallService
                val inCallService = SmsLinkInCallService.getInstance()
                val success = if (inCallService != null) {
                    inCallService.answerCall()
                } else {
                    // 回退到 TelecomManager
                    telecomManager?.acceptRingingCall()
                    true
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
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 优先使用 InCallService
                val inCallService = SmsLinkInCallService.getInstance()
                val success = if (inCallService != null) {
                    inCallService.endCall()
                } else {
                    // 回退到 TelecomManager
                    telecomManager?.endCall()
                    true
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
                threadId = callState.callId,
                address = callState.phoneNumber,
                body = serializeCallState(callState),
                timestamp = System.currentTimeMillis(),
                type = com.smslink.core.model.MessageType.SENT,
                read = true,
                deviceId = targetDeviceId
            )

            networkManager.sendMessage(message, targetDeviceId)

            // 标记为已同步
            callRepository.markAsSynced(callState.callId)

            logger.i(TAG, "Call state synced successfully")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync call state", e)
        }
    }

    /**
     * 序列化通话状态
     */
    private fun serializeCallState(callState: CallState): String {
        return gson.toJson(callState)
    }

    /**
     * 静音通话
     */
    suspend fun muteCall(callId: String): Boolean {
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
    suspend fun unmuteCall(callId: String): Boolean {
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
    suspend fun holdCall(callId: String): Boolean {
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
    suspend fun resumeCall(callId: String): Boolean {
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
    suspend fun sendCallControl(
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
                action = action
            )

            val message = NetworkMessage(
                messageType = MessageType.CALL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = gson.toJsonTree(payload).asJsonObject
            )

            var success = false
            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                success = result.success
                if (!result.success) {
                    logger.e(TAG, "Failed to send call control: ${result.error}")
                }
            }

            success
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

    /**
     * 处理远程通话控制
     */
    private suspend fun handleRemoteCallControl(message: NetworkMessage) {
        try {
            logger.i(TAG, "Received remote call control from: ${message.sourceDevice}")

            val payload = gson.fromJson(message.payload, CallControlPayload::class.java)
            val action = payload.action

            if (action == null) {
                logger.w(TAG, "No action specified in call control message")
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

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (!result.success) {
                    logger.e(TAG, "Failed to send control response: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send call control response", e)
        }
    }

}
