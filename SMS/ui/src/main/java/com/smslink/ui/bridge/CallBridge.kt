package com.smslink.ui.bridge

import com.smslink.core.model.CallInfo
import com.smslink.feature.call.CallManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallBridge @Inject constructor(
    private val callManager: CallManager
) {
    private val _availability = MutableStateFlow<FeatureAvailability>(
        FeatureAvailability.Ready
    )
    val availability: StateFlow<FeatureAvailability> = _availability

    /**
     * 获取当前通话状态
     */
    fun getCurrentCallState(): Flow<CallState?> {
        return callManager.currentCallState.map { state ->
            state?.let {
                CallState(
                    callId = it.callId,
                    contactName = it.contactName ?: "Unknown",
                    phoneNumber = it.phoneNumber,
                    isIncoming = it.isIncoming,
                    isActive = it.isActive,
                    isMuted = it.isMuted,
                    isSpeakerOn = it.isSpeakerOn,
                    duration = it.duration
                )
            }
        }
    }

    /**
     * 获取通话历史
     */
    fun getCallHistory(): Flow<List<CallInfo>> {
        return callManager.callHistory
    }

    /**
     * 接听电话
     */
    suspend fun answerCall(callId: String): Result<Unit> {
        return callManager.answerCall(callId)
    }

    /**
     * 拒接电话
     */
    suspend fun rejectCall(callId: String): Result<Unit> {
        return callManager.rejectCall(callId)
    }

    /**
     * 挂断电话
     */
    suspend fun endCall(callId: String): Result<Unit> {
        return callManager.endCall(callId)
    }

    /**
     * 切换静音
     */
    suspend fun toggleMute(callId: String): Result<Unit> {
        return callManager.toggleMute(callId)
    }

    /**
     * 切换扬声器
     */
    suspend fun toggleSpeaker(callId: String): Result<Unit> {
        return callManager.toggleSpeaker(callId)
    }
}

sealed interface FeatureAvailability {
    object Ready : FeatureAvailability
    data class Unavailable(val reason: String) : FeatureAvailability
}

data class CallState(
    val callId: String,
    val contactName: String,
    val phoneNumber: String,
    val isIncoming: Boolean,
    val isActive: Boolean,
    val isMuted: Boolean,
    val isSpeakerOn: Boolean,
    val duration: Long
)
