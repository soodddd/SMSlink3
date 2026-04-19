package com.smslink.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.smslink.core.model.NotificationInfo

/**
 * 通知数据库实体
 */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey
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

/**
 * 转换为 NotificationInfo
 */
fun NotificationEntity.toNotificationInfo(): NotificationInfo {
    return NotificationInfo(
        id = id,
        appName = appName,
        appPackage = appPackage,
        title = title,
        text = text,
        deviceId = deviceId,
        deviceName = deviceName,
        timestamp = timestamp,
        isRead = isRead,
        iconPath = iconPath
    )
}

/**
 * 从 NotificationInfo 转换
 */
fun NotificationInfo.toEntity(): NotificationEntity {
    return NotificationEntity(
        id = id,
        appName = appName,
        appPackage = appPackage,
        title = title,
        text = text,
        deviceId = deviceId,
        deviceName = deviceName,
        timestamp = timestamp,
        isRead = isRead,
        iconPath = iconPath
    )
}
