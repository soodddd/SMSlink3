package com.smslink.call.model

import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallStateType

/**
 * 通话控制消息载荷
 * 用于跨设备通话控制同步
 */
data class CallControlPayload(
    val callId: String,
    val phoneNumber: String,
    val contactName: String?,
    val state: CallStateType,
    val direction: CallDirection,
    val timestamp: Long,
    val action: CallAction?
)

/**
 * 通话控制动作
 */
enum class CallAction {
    ANSWER,     // 接听
    END,        // 挂断
    MUTE,       // 静音
    UNMUTE,     // 取消静音
    HOLD,       // 保持
    RESUME      // 恢复
}

/**
 * 通话控制响应
 */
data class CallControlResponse(
    val callId: String,
    val action: CallAction,
    val success: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
