package com.smslink.sms

import com.smslink.core.model.Message
import com.smslink.core.model.SmsMessage
import com.smslink.sms.model.Conversation
import kotlinx.coroutines.flow.Flow

/**
 * 短信管理器接口
 * 负责短信的读取、发送和同步
 */
interface ISmsManager {
    /**
     * 获取短信列表
     * @param limit 数量限制
     * @return 短信列表流
     */
    fun getMessages(limit: Int): Flow<List<Message>>

    /**
     * 发送短信
     * @param address 收件人地址
     * @param body 短信内容
     * @param simSlot SIM 卡槽位（双卡设备）
     * @return 是否发送成功
     */
    suspend fun sendMessage(address: String, body: String, simSlot: Int? = null): Boolean

    /**
     * 同步短信到其他设备
     * @param message 短信
     * @param targetDeviceId 目标设备ID
     */
    suspend fun syncMessage(message: SmsMessage, targetDeviceId: String)

    /**
     * 监听新短信
     * @return 新短信流
     */
    fun observeNewMessages(): Flow<Message>

    /**
     * 标记短信为已读
     * @param messageId 短信ID
     */
    suspend fun markAsRead(messageId: String)

    /**
     * 删除短信
     * @param messageId 短信ID
     */
    suspend fun deleteMessage(messageId: String)

    /**
     * 获取会话列表
     * @return 会话列表
     */
    suspend fun getConversations(): List<Conversation>
}
