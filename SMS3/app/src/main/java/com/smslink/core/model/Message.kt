package com.smslink.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 短信消息实体
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: MessageType,
    val read: Boolean,
    val deviceId: String
)

/**
 * 消息类型
 */
enum class MessageType {
    INBOX,      // 收件箱
    SENT,       // 已发送
    DRAFT,      // 草稿
    OUTBOX,     // 发件箱
    FAILED      // 发送失败
}
