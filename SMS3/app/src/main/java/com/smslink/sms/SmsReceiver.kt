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
        if ((intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
            intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            logger.w(TAG, "RECEIVE_SMS permission not granted")
            return
        }

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                handleSmsReceived(context, intent)
            }
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                // SMS_DELIVER is an inbound delivery broadcast for the
                // default SMS app, not an outbound delivery receipt.
                handleSmsReceived(context, intent)
            }
            SmsManagerImpl.ACTION_SMS_SENT -> {
                if (isForThisApp(context, intent)) {
                    updateDeliveryStatus(intent, if (resultCode == android.app.Activity.RESULT_OK)
                        com.smslink.core.model.SmsDeliveryStatus.SENT
                    else com.smslink.core.model.SmsDeliveryStatus.FAILED)
                }
            }
            SmsManagerImpl.ACTION_SMS_DELIVERED -> {
                if (isForThisApp(context, intent)) {
                    updateDeliveryStatus(
                        intent,
                        if (resultCode == android.app.Activity.RESULT_OK) {
                            com.smslink.core.model.SmsDeliveryStatus.DELIVERED
                        } else {
                            com.smslink.core.model.SmsDeliveryStatus.FAILED
                        }
                    )
                }
            }
        }
    }

    private fun isForThisApp(context: Context, intent: Intent): Boolean {
        // PendingIntent callbacks are explicit in SmsManagerImpl. Some vendor
        // builds nevertheless omit ComponentName when delivering them; the
        // receiver's BROADCAST_SMS protection and the private message-id
        // extra are the authoritative boundary, so do not discard that valid
        // callback solely because component is null.
        return intent.component?.packageName == null ||
            intent.component?.packageName == context.packageName
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

        // 合并多段短信
        val sender = messages[0].originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        logger.i(TAG, "Received SMS from: $sender, length: ${body.length}")

        // 创建消息对象
        val message = Message(
            // SMS_RECEIVED and SMS_DELIVER can both be delivered on devices
            // where the app is the default handler. A deterministic id makes
            // the Room primary key deduplicate that pair of broadcasts.
            id = UUID.nameUUIDFromBytes(
                "$sender|$timestamp|$body".toByteArray(Charsets.UTF_8)
            ).toString(),
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

    private fun updateDeliveryStatus(
        intent: Intent,
        status: com.smslink.core.model.SmsDeliveryStatus
    ) {
        val messageId = intent.getStringExtra(SmsManagerImpl.EXTRA_MESSAGE_ID) ?: return
        val partIndex = intent.getIntExtra(SmsManagerImpl.EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(SmsManagerImpl.EXTRA_PART_COUNT, 1)
        val pendingResult = goAsync()
        scope.launch {
            try {
                smsManager.updateDeliveryStatus(messageId, status, partIndex, partCount)
            } finally {
                pendingResult.finish()
            }
        }
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
