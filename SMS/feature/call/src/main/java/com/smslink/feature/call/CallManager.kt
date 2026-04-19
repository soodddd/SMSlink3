package com.smslink.feature.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.smslink.audio.AudioCapture
import com.smslink.audio.AudioCodec
import com.smslink.audio.AudioPlayer
import com.smslink.core.model.CallInfo
import com.smslink.core.model.CallState
import com.smslink.core.model.CallType
import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话管理器
 * 负责管理通话状态、音频传输和通话控制
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CallManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private val audioCapture = AudioCapture()
    private val audioPlayer = AudioPlayer()
    private val audioCodec = AudioCodec()

    private val _currentCallState = MutableStateFlow<CallState?>(null)
    val currentCallState: StateFlow<CallState?> = _currentCallState.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallInfo>>(emptyList())
    val callHistory: StateFlow<List<CallInfo>> = _callHistory.asStateFlow()

    private var callStartTime: Long = 0
    private var isAudioStreaming = false

    // 消息发送回调（由 DeviceManager 设置）
    var onSendMessage: ((Message) -> Unit)? = null

    init {
        registerPhoneStateListener()
    }

    /**
     * 注册电话状态监听器
     */
    private fun registerPhoneStateListener() {
        if (!hasReadPhoneStatePermission()) {
            Log.w(TAG, "Skipping phone state listener registration because READ_PHONE_STATE is not granted yet.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 使用 TelephonyCallback
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChange(state)
                }
            }
            try {
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
            } catch (securityException: SecurityException) {
                Log.w(TAG, "Telephony callback registration was denied by the device.", securityException)
            }
        } else {
            // Android 12 以下使用 PhoneStateListener
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChange(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            try {
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (securityException: SecurityException) {
                Log.w(TAG, "PhoneStateListener registration was denied by the device.", securityException)
            }
        }
    }

    /**
     * 处理通话状态变化
     */
    private fun hasReadPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleCallStateChange(state: Int, phoneNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // 来电响铃
                handleIncomingCall(phoneNumber ?: "Unknown")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // 通话中
                handleCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // 通话结束
                handleCallEnded()
            }
        }
    }

    /**
     * 处理来电
     */
    private fun handleIncomingCall(phoneNumber: String) {
        val callId = UUID.randomUUID().toString()
        val callState = CallState(
            callId = callId,
            contactName = getContactName(phoneNumber),
            phoneNumber = phoneNumber,
            isIncoming = true,
            isActive = false,
            deviceId = "local",
            deviceName = "Local Device"
        )
        _currentCallState.value = callState

        // 通知远程设备有来电
        sendCallNotification(callState)
    }

    /**
     * 处理通话激活
     */
    private fun handleCallActive() {
        val currentCall = _currentCallState.value ?: return
        callStartTime = System.currentTimeMillis()

        _currentCallState.value = currentCall.copy(isActive = true)

        // 开始音频流传输
        startAudioStreaming()
    }

    /**
     * 处理通话结束
     */
    private fun handleCallEnded() {
        val currentCall = _currentCallState.value ?: return
        val duration = (System.currentTimeMillis() - callStartTime) / 1000

        // 停止音频流
        stopAudioStreaming()

        // 保存通话记录
        val callInfo = CallInfo(
            id = currentCall.callId,
            contactName = currentCall.contactName,
            phoneNumber = currentCall.phoneNumber,
            type = if (currentCall.isIncoming) CallType.INCOMING else CallType.OUTGOING,
            duration = duration,
            deviceId = currentCall.deviceId,
            deviceName = currentCall.deviceName,
            timestamp = System.currentTimeMillis(),
            isAnswered = currentCall.isActive
        )

        _callHistory.value = listOf(callInfo) + _callHistory.value
        _currentCallState.value = null

        // 通知远程设备通话结束
        sendCallEndNotification(callInfo)
    }

    /**
     * 接听电话
     */
    fun answerCall(callId: String): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.acceptRingingCall()
            } else {
                // Android 9 以下需要使用反射或其他方法
                // 这里简化处理
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 拒接电话
     */
    fun rejectCall(callId: String): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            }
            _currentCallState.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 挂断电话
     */
    fun endCall(callId: String): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 切换静音
     */
    fun toggleMute(callId: String): Result<Unit> {
        val currentCall = _currentCallState.value ?: return Result.failure(Exception("No active call"))

        return try {
            val newMuteState = !currentCall.isMuted
            // TODO: 实现静音控制
            _currentCallState.value = currentCall.copy(isMuted = newMuteState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 切换扬声器
     */
    fun toggleSpeaker(callId: String): Result<Unit> {
        val currentCall = _currentCallState.value ?: return Result.failure(Exception("No active call"))

        return try {
            val newSpeakerState = !currentCall.isSpeakerOn
            // TODO: 实现扬声器控制
            _currentCallState.value = currentCall.copy(isSpeakerOn = newSpeakerState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 开始音频流传输
     */
    private fun startAudioStreaming() {
        if (isAudioStreaming) return

        isAudioStreaming = true

        // 初始化音频播放器
        audioPlayer.initialize()
        audioPlayer.startPlaying()

        // 开始捕获并发送音频
        scope.launch {
            audioCapture.startCapture().collect { audioData ->
                val encodedData = audioCodec.encode(audioData)
                sendAudioData(encodedData)
            }
        }
    }

    /**
     * 停止音频流传输
     */
    private fun stopAudioStreaming() {
        if (!isAudioStreaming) return

        isAudioStreaming = false
        audioCapture.stopCapture()
        audioPlayer.stopPlaying()
    }

    /**
     * 发送音频数据
     */
    private fun sendAudioData(audioData: ByteArray) {
        val message = Message(
            type = MessageType.CALL_AUDIO_DATA,
            messageId = System.currentTimeMillis(),
            payload = audioData
        )
        onSendMessage?.invoke(message)
    }

    /**
     * 接收音频数据
     */
    fun receiveAudioData(audioData: ByteArray) {
        scope.launch {
            val decodedData = audioCodec.decode(audioData)
            audioPlayer.playAudio(decodedData)
        }
    }

    /**
     * 发送通话通知
     */
    private fun sendCallNotification(callState: CallState) {
        val contactName = callState.contactName ?: "Unknown"
        val payloadString = "${callState.callId}|${callState.phoneNumber}|$contactName"
        val message = Message(
            type = MessageType.CALL_INCOMING,
            messageId = System.currentTimeMillis(),
            payload = payloadString.toByteArray()
        )
        onSendMessage?.invoke(message)
    }

    /**
     * 发送通话结束通知
     */
    private fun sendCallEndNotification(callInfo: CallInfo) {
        val message = Message(
            type = MessageType.CALL_END,
            messageId = System.currentTimeMillis(),
            payload = "${callInfo.id}|${callInfo.duration}".toByteArray()
        )
        onSendMessage?.invoke(message)
    }

    /**
     * 处理远程来电通知
     */
    fun handleRemoteIncomingCall(callId: String, phoneNumber: String, contactName: String?) {
        val callState = CallState(
            callId = callId,
            contactName = contactName,
            phoneNumber = phoneNumber,
            isIncoming = true,
            isActive = false,
            deviceId = "remote",
            deviceName = "Remote Device"
        )
        _currentCallState.value = callState
    }

    /**
     * 处理远程通话结束
     */
    fun handleRemoteCallEnd(callId: String, duration: Long) {
        val currentCall = _currentCallState.value
        if (currentCall?.callId == callId) {
            stopAudioStreaming()

            val callInfo = CallInfo(
                id = callId,
                contactName = currentCall.contactName,
                phoneNumber = currentCall.phoneNumber,
                type = if (currentCall.isIncoming) CallType.INCOMING else CallType.OUTGOING,
                duration = duration,
                deviceId = currentCall.deviceId,
                deviceName = currentCall.deviceName,
                timestamp = System.currentTimeMillis(),
                isAnswered = currentCall.isActive
            )

            _callHistory.value = listOf(callInfo) + _callHistory.value
            _currentCallState.value = null
        }
    }

    /**
     * 获取联系人名称
     */
    private fun getContactName(phoneNumber: String): String? {
        // TODO: 从联系人数据库查询
        return null
    }

    /**
     * 获取通话历史
     */
    fun getCallHistory(): List<CallInfo> {
        return _callHistory.value
    }
}
