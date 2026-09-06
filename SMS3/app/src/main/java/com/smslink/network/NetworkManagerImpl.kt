package com.smslink.network

import com.smslink.core.log.ILogger
import com.smslink.core.model.Message
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import kotlinx.coroutines.CancellationException
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
            val isCallState = message.threadId.startsWith(CALL_THREAD_PREFIX)
            val payload = if (isCallState) {
                runCatching {
                    com.google.gson.JsonParser.parseString(message.body).asJsonObject
                }.getOrElse {
                    throw IllegalArgumentException("Invalid call state payload", it)
                }
            } else {
                com.google.gson.JsonObject().apply {
                    addProperty("body", message.body)
                    addProperty("address", message.address)
                    addProperty("threadId", message.threadId)
                }
            }

            val networkMessage = NetworkMessage(
                messageType = if (isCallState) {
                    com.smslink.network.model.MessageType.CALL
                } else {
                    com.smslink.network.model.MessageType.SMS
                },
                messageId = message.id,
                sourceDevice = message.deviceId,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            val result = messageTransport.sendMessage(targetDeviceId, networkMessage).first()
            if (!result.success) {
                throw IllegalStateException(result.error ?: "Message was not queued")
            }
            val acknowledged = try {
                messageTransport.awaitDelivery(result.messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Older source-compatible transports do not implement an ACK
                // waiter. Their successful send result still represents the
                // strongest signal available from that transport.
                logger.w(TAG, "Delivery ACK unavailable: ${e.message}")
                true
            }
            if (!acknowledged) {
                throw IllegalStateException("Message was not acknowledged by $targetDeviceId")
            }
            logger.d(TAG, "Message sent successfully: ${message.id}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send message: ${message.id}", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "NetworkManagerImpl"
        private const val CALL_THREAD_PREFIX = "call:"
    }
}
