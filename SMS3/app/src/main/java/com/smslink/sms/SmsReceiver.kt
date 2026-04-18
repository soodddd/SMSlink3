package com.smslink.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.smslink.core.log.ILogger
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 短信广播接收器
 * 监听系统短信接收事件
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var smsManager: SmsManagerImpl

    @Inject
    lateinit var logger: ILogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        logger.d(TAG, "SMS broadcast received: ${intent.action}")

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                handleSmsReceived(intent)
            }
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                handleSmsDelivered(intent)
            }
        }
    }

    /**
     * 处理接收到的短信
     */
    private fun handleSmsReceived(intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            logger.w(TAG, "No messages in SMS_RECEIVED intent")
            return
        }

        // 获取订阅ID（SIM卡槽）
        val subId = intent.extras?.getInt("subscription", -1) ?: -1

        // 合并多段短信
        val sender = messages[0].originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        logger.i(TAG, "Received SMS from: $sender, length: ${body.length}")

        // 创建消息对象
        val message = Message(
            id = UUID.randomUUID().toString(),
            threadId = getThreadId(sender),
            address = sender,
            body = body,
            timestamp = timestamp,
            type = MessageType.INBOX,
            read = false,
            deviceId = "local"
        )

        // 异步处理消息
        val pendingResult = goAsync()
        scope.launch {
            try {
                smsManager.notifyNewMessage(message)
                logger.d(TAG, "SMS processed successfully")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to process SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 处理短信发送状态
     */
    private fun handleSmsDelivered(intent: Intent) {
        logger.d(TAG, "SMS delivered")
        // TODO: 更新消息发送状态
    }

    /**
     * 获取会话ID
     */
    private fun getThreadId(address: String): String {
        // 简化实现：使用地址的哈希值
        return address.hashCode().toString()
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
