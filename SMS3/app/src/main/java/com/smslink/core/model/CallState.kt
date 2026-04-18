package com.smslink.core.model

/**
 * Call state model.
 */
data class CallState(
    val callId: String,
    val phoneNumber: String,
    val contactName: String?,
    val state: CallStateType,
    val direction: CallDirection,
    val startTime: Long,
    val duration: Long
)

/**
 * Call state type.
 */
enum class CallStateType {
    IDLE,
    RINGING,
    OFFHOOK,
    ENDED
}

/**
 * Call direction.
 */
enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED
}
