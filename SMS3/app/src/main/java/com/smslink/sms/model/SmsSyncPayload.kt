package com.smslink.sms.model

import com.google.gson.Gson
import com.smslink.core.model.MessageType
import com.smslink.core.model.SmsDeliveryStatus

/**
 * 短信同步消息格式
 */
data class SmsSyncPayload(
    val messageId: String,
    val threadId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: MessageType,
    val read: Boolean,
    val deliveryStatus: SmsDeliveryStatus = SmsDeliveryStatus.QUEUED,
    val deliveryTimestamp: Long? = null,
    val simSlot: Int? = null,
    val action: SyncAction = SyncAction.NEW_MESSAGE
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): SmsSyncPayload =
            Gson().fromJson(json, SmsSyncPayload::class.java)
    }
}

/**
 * 同步动作类型
 */
enum class SyncAction {
    NEW_MESSAGE,        // 新消息
    MARK_READ,          // 标记已读
    DELETE_MESSAGE,     // 删除消息
    SEND_REQUEST,       // 发送请求（副设备请求主设备发送）
    SEND_RESULT         // 发送结果
}

/**
 * 会话模型
 */
data class Conversation(
    val threadId: String,
    val address: String,
    val contactName: String?,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val messageCount: Int
)

/**
 * 短信发送请求
 */
data class SmsSendRequest(
    val requestId: String,
    val address: String,
    val body: String,
    val simSlot: Int? = null
)

/**
 * 短信发送结果
 */
data class SmsSendResult(
    val requestId: String,
    val success: Boolean,
    val messageId: String?,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): SmsSendResult =
            Gson().fromJson(json, SmsSendResult::class.java)
    }
}
