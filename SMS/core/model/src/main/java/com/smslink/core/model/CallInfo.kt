package com.smslink.core.model

/**
 * 通话信息模型
 */
data class CallInfo(
    val id: String,
    val contactName: String?,
    val phoneNumber: String,
    val type: CallType,
    val duration: Long, // 通话时长（秒）
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val isAnswered: Boolean = false
)

/**
 * 通话类型
 */
enum class CallType {
    INCOMING,  // 来电
    OUTGOING,  // 去电
    MISSED     // 未接来电
}

/**
 * 通话状态
 */
data class CallState(
    val callId: String,
    val contactName: String?,
    val phoneNumber: String,
    val isIncoming: Boolean,
    val isActive: Boolean,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val duration: Long = 0, // 通话时长（秒）
    val deviceId: String,
    val deviceName: String
)
