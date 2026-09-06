package com.smslink.sync

import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.core.model.Message
import com.smslink.notification.NotificationManagerImpl
import com.smslink.notification.NotificationRepository
import com.smslink.sms.SmsManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 补同步管理器
 * 在设备连接建立后，补同步近1分钟的通知和短信
 *
 * 参考：
 * - KDE Connect的历史同步机制
 * - 确保新连接的设备能看到最近的重要消息
 */
@Singleton
class CatchupSyncManager @Inject constructor(
    private val notificationManager: NotificationManagerImpl,
    private val notificationRepository: NotificationRepository,
    private val smsManager: SmsManagerImpl,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "CatchupSync"
        private const val CATCHUP_WINDOW = 60 * 1000L // 1分钟
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 执行完整的补同步
     * 在设备连接建立后调用
     */
    fun performCatchupSync(targetDeviceId: String) {
        logger.i(TAG, "Starting catchup sync for device: $targetDeviceId")

        scope.launch {
            try {
                // 并行执行通知和短信补同步
                launch { syncRecentNotifications(targetDeviceId) }
                launch { syncRecentMessages(targetDeviceId) }
            } catch (e: Exception) {
                logger.e(TAG, "Catchup sync failed", e)
            }
        }
    }

    /**
     * 补同步近1分钟的通知
     */
    private suspend fun syncRecentNotifications(targetDeviceId: String) {
        try {
            logger.d(TAG, "Syncing recent notifications to device: $targetDeviceId")

            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - CATCHUP_WINDOW

            // 获取近1分钟的通知
            val recentNotifications = notificationRepository.getAllNotifications(100)
                .first()
                .filter { notification ->
                    notification.timestamp >= cutoffTime
                }

            logger.i(TAG, "Found ${recentNotifications.size} recent notifications to sync")

            // 逐个同步通知
            recentNotifications.forEach { notification ->
                try {
                    notificationManager.syncNotification(notification, targetDeviceId)
                    logger.d(TAG, "Synced notification: ${notification.id}")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to sync notification: ${notification.id}", e)
                }
            }

            logger.i(TAG, "Notification catchup sync completed for device: $targetDeviceId")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync recent notifications", e)
        }
    }

    /**
     * 补同步近1分钟的短信
     */
    private suspend fun syncRecentMessages(targetDeviceId: String) {
        try {
            logger.d(TAG, "Syncing recent messages to device: $targetDeviceId")

            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - CATCHUP_WINDOW

            // 获取近1分钟的短信
            val recentMessages = smsManager.getMessages(100)
                .first()
                .filter { message ->
                    message.timestamp >= cutoffTime
                }

            logger.i(TAG, "Found ${recentMessages.size} recent messages to sync")

            // 逐个同步短信
            recentMessages.forEach { message ->
                try {
                    // 使用内部方法同步短信
                    syncMessageToDevice(message, targetDeviceId)
                    logger.d(TAG, "Synced message: ${message.id}")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to sync message: ${message.id}", e)
                }
            }

            logger.i(TAG, "Message catchup sync completed for device: $targetDeviceId")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync recent messages", e)
        }
    }

    /**
     * 同步单条消息到设备
     * 这是一个辅助方法，因为SmsManagerImpl的syncSmsToDevice是私有的
     */
    private suspend fun syncMessageToDevice(message: Message, targetDeviceId: String) {
        // 将Message转换为SmsMessage并同步
        val smsMessage = com.smslink.core.model.SmsMessage(
            id = message.id,
            address = message.address,
            body = message.body,
            timestamp = message.timestamp,
            isRead = message.read
        )

        smsManager.syncMessage(smsMessage, targetDeviceId)
    }

    /**
     * 仅同步通知
     */
    fun syncNotificationsOnly(targetDeviceId: String) {
        scope.launch {
            syncRecentNotifications(targetDeviceId)
        }
    }

    /**
     * 仅同步短信
     */
    fun syncMessagesOnly(targetDeviceId: String) {
        scope.launch {
            syncRecentMessages(targetDeviceId)
        }
    }
}
