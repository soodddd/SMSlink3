package com.smslink.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
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

        // 检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            logger.w(TAG, "READ_SMS permission not granted")
            return
        }

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                handleSmsReceived(context, intent)
            }
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                handleSmsDelivered(intent)
            }
        }
    }

    /**
     * 处理接收到的短信
     */
    private fun handleSmsReceived(context: Context, intent: Intent) {
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
            threadId = getThreadId(context, sender),
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
     * 使用系统 ContentProvider 查询真实的 threadId
     */
    private fun getThreadId(context: Context, address: String): String {
        return try {
            val uri = android.net.Uri.parse("content://sms/")
            val projection = arrayOf("thread_id")
            val selection = "address = ?"
            val selectionArgs = arrayOf(address)

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val threadIdIndex = cursor.getColumnIndex("thread_id")
                    if (threadIdIndex >= 0) {
                        return cursor.getString(threadIdIndex)
                    }
                }
            }

            // 如果查询失败，使用地址作为 threadId
            address
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get thread ID", e)
            // 降级方案：使用地址本身
            address
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
