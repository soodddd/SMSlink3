package com.smslink.network.model

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 网络消息封装
 * 所有跨设备消息的统一格式
 */
data class NetworkMessage(
    val version: String = "1.0",
    val messageType: MessageType,
    val messageId: String,
    val sourceDevice: String,
    val targetDevice: String,
    val timestamp: Long,
    val payload: JsonObject,
    val status: MessageStatus = MessageStatus.PENDING,
    val errorCode: Int = 0,
    val errorMessage: String? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): NetworkMessage = Gson().fromJson(json, NetworkMessage::class.java)
    }
}

/**
 * 消息类型
 */
enum class MessageType {
    NOTIFICATION,   // 通知消息
    SMS,           // 短信消息
    FILE,          // 文件传输
    CALL,          // 通话控制
    CONTROL,       // 控制指令
    HEARTBEAT,     // 心跳包
    ACK            // 确认消息
}

/**
 * 消息状态
 */
enum class MessageStatus {
    PENDING,    // 待发送
    SENDING,    // 发送中
    SENT,       // 已发送
    DELIVERED,  // 已送达
    FAILED      // 发送失败
}

/**
 * 发送结果
 */
data class SendResult(
    val success: Boolean,
    val messageId: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
