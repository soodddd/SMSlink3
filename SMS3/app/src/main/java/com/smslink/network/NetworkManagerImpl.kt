package com.smslink.network

import com.smslink.core.log.ILogger
import com.smslink.core.model.Message
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络管理器实现
 * 提供消息发送功能的简化实现
 */
@Singleton
class NetworkManagerImpl @Inject constructor(
    private val messageTransport: IMessageTransport,
    private val logger: ILogger
) : INetworkManager {

    override suspend fun sendMessage(message: Message, targetDeviceId: String) {
        try {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("body", message.body)
                addProperty("address", message.address)
                addProperty("threadId", message.threadId)
            }

            val networkMessage = NetworkMessage(
                messageType = com.smslink.network.model.MessageType.SMS,
                messageId = message.id,
                sourceDevice = message.deviceId,
                targetDevice = targetDeviceId,
                timestamp = message.timestamp,
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, networkMessage).first()
            logger.d(TAG, "Message sent successfully: ${message.id}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send message: ${message.id}", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "NetworkManagerImpl"
    }
}
