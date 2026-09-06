package com.smslink.network.transport

import com.smslink.network.model.NetworkMessage
import com.smslink.network.model.SendResult
import kotlinx.coroutines.flow.Flow

/**
 * 消息传输接口
 * 负责消息的序列化、发送、接收和确认
 */
interface IMessageTransport {
    /**
     * 发送消息
     * @param deviceId 目标设备ID
     * @param message 消息对象
     * @return 发送结果流
     */
    fun sendMessage(deviceId: String, message: NetworkMessage): Flow<SendResult>

    /**
     * 接收消息流
     * @return 消息流
     */
    fun receiveMessages(): Flow<NetworkMessage>

    /**
     * 发送确认消息
     * @param messageId 消息ID
     * @param deviceId 设备ID
     */
    suspend fun sendAck(messageId: String, deviceId: String)

    /**
     * Wait until the remote transport acknowledges a previously written
     * message. A SendResult only means the frame was accepted locally.
     *
     * The default keeps source-compatible test/fake transports functional;
     * MessageTransportImpl overrides it with the real ACK wait.
     */
    suspend fun awaitDelivery(messageId: String, timeoutMs: Long = 15_000L): Boolean = true

    /**
     * 获取待发送消息队列大小
     */
    fun getPendingMessageCount(): Int

    /**
     * 清空消息队列
     */
    suspend fun clearQueue()
}
