package com.smslink.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 应用通知实体
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["timestamp"]),
        Index(value = ["isSynced"])
    ]
)
data class AppNotification(
    @PrimaryKey val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val deviceId: String,
    val isSynced: Boolean
)
