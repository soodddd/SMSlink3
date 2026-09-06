package com.smslink.call

import com.smslink.core.model.CallState
import kotlinx.coroutines.flow.Flow

/**
 * 通话管理器接口
 * 负责通话状态的监听和同步
 */
interface ICallManager {
    /**
     * 开始监听通话状态
     */
    fun startListening()

    /**
     * 停止监听通话状态
     */
    fun stopListening()

    /**
     * 获取通话状态流
     * @return 通话状态流
     */
    fun getCallState(): Flow<CallState>

    /**
     * 拨打电话
     * @param phoneNumber 电话号码
     * @return 是否成功
     */
    suspend fun makeCall(phoneNumber: String): Boolean

    /**
     * 接听电话
     * @param callId 通话ID
     * @return 是否成功
     */
    suspend fun answerCall(callId: String): Boolean

    /**
     * 挂断电话
     * @param callId 通话ID
     * @return 是否成功
     */
    suspend fun endCall(callId: String): Boolean

    /** Optional controls stay on the interface so UI code never down-casts
     * the Hilt binding to a concrete implementation. */
    suspend fun muteCall(callId: String): Boolean = false

    suspend fun unmuteCall(callId: String): Boolean = false

    suspend fun holdCall(callId: String): Boolean = false

    suspend fun resumeCall(callId: String): Boolean = false

    suspend fun sendCallControl(
        callState: CallState,
        action: com.smslink.call.model.CallAction,
        targetDeviceId: String
    ): Boolean = false

    /**
     * 同步通话状态到其他设备
     * @param callState 通话状态
     * @param targetDeviceId 目标设备ID
     */
    suspend fun syncCallState(callState: CallState, targetDeviceId: String)
}
