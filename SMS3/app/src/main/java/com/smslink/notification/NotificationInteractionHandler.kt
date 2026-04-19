package com.smslink.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as AndroidXRemoteInput
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知交互处理器
 * 处理通知的回复、动作执行等交互功能
 *
 * 参考：
 * - Android RemoteInput API
 * - Notification.Action处理
 * - KDE Connect的通知交互实现
 */
@Singleton
class NotificationInteractionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageTransport: IMessageTransport,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "NotificationInteraction"
        private const val REMOTE_INPUT_KEY = "remote_input_reply"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存活动的通知，用于交互
    private val activeNotifications = mutableMapOf<String, NotificationContext>()

    /**
     * 注册通知上下文
     * 在通知被镜像时调用，保存必要的上下文信息
     */
    fun registerNotification(
        notificationId: String,
        sbn: StatusBarNotification,
        sourceDeviceId: String
    ) {
        try {
            val notification = sbn.notification
            val actions = extractActions(notification)
            val remoteInputs = extractRemoteInputs(notification)

            val context = NotificationContext(
                notificationId = notificationId,
                packageName = sbn.packageName,
                tag = sbn.tag,
                id = sbn.id,
                actions = actions,
                remoteInputs = remoteInputs,
                sourceDeviceId = sourceDeviceId,
                timestamp = System.currentTimeMillis()
            )

            activeNotifications[notificationId] = context
            logger.d(TAG, "Registered notification: $notificationId with ${actions.size} actions")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to register notification", e)
        }
    }

    /**
     * 提取通知动作
     */
    private fun extractActions(notification: Notification): List<NotificationAction> {
        val actions = mutableListOf<NotificationAction>()

        try {
            notification.actions?.forEachIndexed { index, action ->
                if (action != null) {
                    val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true

                    actions.add(
                        NotificationAction(
                            index = index,
                            title = action.title?.toString() ?: "Action $index",
                            actionIntent = action.actionIntent,
                            hasRemoteInput = hasRemoteInput,
                            remoteInputs = action.remoteInputs?.map { it.resultKey } ?: emptyList()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to extract actions", e)
        }

        return actions
    }

    /**
     * 提取RemoteInput
     */
    private fun extractRemoteInputs(notification: Notification): List<String> {
        val remoteInputKeys = mutableListOf<String>()

        try {
            notification.actions?.forEach { action ->
                action?.remoteInputs?.forEach { remoteInput ->
                    remoteInputKeys.add(remoteInput.resultKey)
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to extract remote inputs", e)
        }

        return remoteInputKeys
    }

    /**
     * 回复通知
     * 使用RemoteInput发送回复文本
     */
    fun replyToNotification(notificationId: String, replyText: String, targetDeviceId: String) {
        scope.launch {
            try {
                logger.i(TAG, "Replying to notification: $notificationId")

                val notificationContext = activeNotifications[notificationId]
                if (notificationContext == null) {
                    logger.w(TAG, "Notification context not found: $notificationId")
                    sendReplyRequest(notificationId, replyText, targetDeviceId)
                    return@launch
                }

                // 查找支持RemoteInput的动作
                val replyAction = notificationContext.actions.firstOrNull { it.hasRemoteInput }
                if (replyAction == null) {
                    logger.w(TAG, "No reply action found for notification: $notificationId")
                    return@launch
                }

                // 构建RemoteInput结果
                val remoteInputKey = replyAction.remoteInputs.firstOrNull() ?: REMOTE_INPUT_KEY
                val intent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val remoteInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    RemoteInput.Builder(remoteInputKey)
                        .setLabel("Reply")
                        .build()
                } else {
                    null
                }

                if (remoteInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    RemoteInput.addResultsToIntent(
                        arrayOf(remoteInput),
                        intent,
                        android.os.Bundle().apply {
                            putCharSequence(remoteInputKey, replyText)
                        }
                    )

                    // 发送PendingIntent
                    try {
                        replyAction.actionIntent?.send(context, 0, intent)
                        logger.i(TAG, "Reply sent successfully")
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to send reply intent", e)
                    }
                }

            } catch (e: Exception) {
                logger.e(TAG, "Failed to reply to notification", e)
            }
        }
    }

    /**
     * 发送回复请求到源设备
     * 当本地无法直接回复时使用
     */
    private suspend fun sendReplyRequest(notificationId: String, replyText: String, targetDeviceId: String) {
        try {
            val payload = JsonObject().apply {
                addProperty("action", "reply")
                addProperty("notificationId", notificationId)
                addProperty("replyText", replyText)
            }

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = "local", // 应该从DeviceManager获取
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Reply request sent successfully")
                } else {
                    logger.e(TAG, "Failed to send reply request: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send reply request", e)
        }
    }

    /**
     * 执行通知动作
     */
    fun executeAction(notificationId: String, actionIndex: Int, targetDeviceId: String) {
        scope.launch {
            try {
                logger.i(TAG, "Executing action $actionIndex for notification: $notificationId")

                val notificationContext = activeNotifications[notificationId]
                if (notificationContext == null) {
                    logger.w(TAG, "Notification context not found: $notificationId")
                    sendActionRequest(notificationId, actionIndex, targetDeviceId)
                    return@launch
                }

                // 查找对应的动作
                val action = notificationContext.actions.getOrNull(actionIndex)
                if (action == null) {
                    logger.w(TAG, "Action not found: $actionIndex")
                    return@launch
                }

                // 执行动作
                try {
                    action.actionIntent?.send()
                    logger.i(TAG, "Action executed successfully")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to execute action", e)
                }

            } catch (e: Exception) {
                logger.e(TAG, "Failed to execute action", e)
            }
        }
    }

    /**
     * 发送动作执行请求到源设备
     */
    private suspend fun sendActionRequest(notificationId: String, actionIndex: Int, targetDeviceId: String) {
        try {
            val payload = JsonObject().apply {
                addProperty("action", "execute")
                addProperty("notificationId", notificationId)
                addProperty("actionIndex", actionIndex)
            }

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = "local",
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Action request sent successfully")
                } else {
                    logger.e(TAG, "Failed to send action request: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send action request", e)
        }
    }

    /**
     * 清除通知
     */
    fun dismissNotification(notificationId: String, targetDeviceId: String) {
        scope.launch {
            try {
                logger.i(TAG, "Dismissing notification: $notificationId")

                val notificationContext = activeNotifications[notificationId]
                if (notificationContext != null) {
                    // 本地清除
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as? android.app.NotificationManager

                    if (notificationContext.tag != null) {
                        notificationManager?.cancel(notificationContext.tag, notificationContext.id)
                    } else {
                        notificationManager?.cancel(notificationContext.id)
                    }

                    activeNotifications.remove(notificationId)
                }

                // 发送清除请求到源设备
                sendDismissRequest(notificationId, targetDeviceId)

            } catch (e: Exception) {
                logger.e(TAG, "Failed to dismiss notification", e)
            }
        }
    }

    /**
     * 发送清除请求到源设备
     */
    private suspend fun sendDismissRequest(notificationId: String, targetDeviceId: String) {
        try {
            val payload = JsonObject().apply {
                addProperty("action", "dismiss")
                addProperty("notificationId", notificationId)
            }

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = "local",
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Dismiss request sent successfully")
                } else {
                    logger.e(TAG, "Failed to send dismiss request: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send dismiss request", e)
        }
    }

    /**
     * 清理过期的通知上下文
     */
    fun cleanupExpiredContexts() {
        val currentTime = System.currentTimeMillis()
        val expiredContexts = activeNotifications.filter { (_, context) ->
            currentTime - context.timestamp > 3600000 // 1小时
        }

        expiredContexts.forEach { (id, _) ->
            activeNotifications.remove(id)
        }

        if (expiredContexts.isNotEmpty()) {
            logger.d(TAG, "Cleaned up ${expiredContexts.size} expired notification contexts")
        }
    }

    /**
     * 获取通知的可用动作
     */
    fun getAvailableActions(notificationId: String): List<NotificationAction> {
        return activeNotifications[notificationId]?.actions ?: emptyList()
    }

    /**
     * 检查通知是否支持回复
     */
    fun supportsReply(notificationId: String): Boolean {
        val context = activeNotifications[notificationId] ?: return false
        return context.actions.any { it.hasRemoteInput }
    }
}

/**
 * 通知上下文
 */
data class NotificationContext(
    val notificationId: String,
    val packageName: String,
    val tag: String?,
    val id: Int,
    val actions: List<NotificationAction>,
    val remoteInputs: List<String>,
    val sourceDeviceId: String,
    val timestamp: Long
)

/**
 * 通知动作
 */
data class NotificationAction(
    val index: Int,
    val title: String,
    val actionIntent: PendingIntent?,
    val hasRemoteInput: Boolean,
    val remoteInputs: List<String>
)
