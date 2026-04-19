package com.smslink.core.model

/**
 * 通知信息模型
 */
data class NotificationInfo(
    val id: String,
    val appName: String,
    val appPackage: String,
    val title: String,
    val text: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val iconPath: String? = null
)
