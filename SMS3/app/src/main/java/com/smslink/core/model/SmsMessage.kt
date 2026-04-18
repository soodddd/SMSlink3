package com.smslink.core.model

/**
 * 短信消息（用于传输）
 */
data class SmsMessage(
    val id: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isRead: Boolean
)
