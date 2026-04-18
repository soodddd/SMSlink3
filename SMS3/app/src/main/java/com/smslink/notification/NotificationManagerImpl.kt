package com.smslink.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.device.IDeviceManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知管理器实现
 * 负责应用通知的监听、同步和管理
 */
@Singleton
class NotificationManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NotificationRepository,
    private val messageTransport: IMessageTransport,
    private val deviceManager: IDeviceManager,
    private val preferences: SharedPreferences,
    private val logger: ILogger
) : INotificationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _notificationFlow = MutableSharedFlow<AppNotification>(replay = 0)
    private var isListening = false
    private val gson = Gson()

    constructor(
        context: Context,
        repository: NotificationRepository,
        logger: ILogger
    ) : this(
        context = context,
        repository = repository,
        messageTransport = object : IMessageTransport {
            override fun sendMessage(deviceId: String, message: NetworkMessage) = kotlinx.coroutines.flow.flowOf(
                com.smslink.network.model.SendResult(success = true, messageId = message.messageId, error = null)
            )
            override fun receiveMessages() = kotlinx.coroutines.flow.emptyFlow<NetworkMessage>()
            override suspend fun sendAck(messageId: String, deviceId: String) = Unit
            override fun getPendingMessageCount(): Int = 0
            override suspend fun clearQueue() = Unit
        },
        deviceManager = object : IDeviceManager {
            override fun startDiscovery() = Unit
            override fun stopDiscovery() = Unit
            override fun pairDevice(deviceId: String, qrCode: String) = kotlinx.coroutines.flow.emptyFlow<com.smslink.core.model.PairResult>()
            override fun getConnectedDevices() = kotlinx.coroutines.flow.flowOf(emptyList<com.smslink.core.model.Device>())
            override suspend fun setDeviceRole(deviceId: String, role: com.smslink.core.model.DeviceRole) = Unit
            override suspend fun removeDevice(deviceId: String) = Unit
            override fun getLocalDevice() = com.smslink.core.model.Device(
                id = "local",
                name = "local",
                type = com.smslink.core.model.DeviceType.PHONE,
                role = com.smslink.core.model.DeviceRole.MAIN,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )
        },
        preferences = context.getSharedPreferences("smslink_test", Context.MODE_PRIVATE),
        logger = logger
    )

    init {
        setInstance(this)
        startReceivingRemoteNotifications()
        createNotificationChannel()
    }

    /**
     * 开始监听通知
     */
    override fun startListening() {
        if (isListening) {
            logger.w(TAG, "Notification listener already started")
            return
        }

        try {
            val intent = Intent(context, NotificationListenerServiceImpl::class.java)
            context.startService(intent)
            isListening = true
            logger.i(TAG, "Notification listener started")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start notification listener", e)
        }
    }

    /**
     * 停止监听通知
     */
    override fun stopListening() {
        if (!isListening) {
            logger.w(TAG, "Notification listener not started")
            return
        }

        try {
            val intent = Intent(context, NotificationListenerServiceImpl::class.java)
            context.stopService(intent)
            isListening = false
            logger.i(TAG, "Notification listener stopped")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to stop notification listener", e)
        }
    }

    /**
     * 获取通知流
     */
    override fun getNotifications(): Flow<AppNotification> {
        return _notificationFlow.asSharedFlow()
    }

    /**
     * 处理新通知
     * 由 NotificationListenerService 调用
     */
    internal fun onNotificationPosted(notification: AppNotification) {
        scope.launch {
            try {
                // 保存到数据库
                repository.insertNotification(notification)

                // 发送到流
                _notificationFlow.emit(notification)

                logger.d(TAG, "Notification posted: ${notification.appName} - ${notification.title}")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to handle notification", e)
            }
        }
    }

    /**
     * 同步通知到其他设备
     */
    override suspend fun syncNotification(notification: AppNotification, targetDeviceId: String) {
        try {
            logger.d(TAG, "Syncing notification ${notification.id} to device $targetDeviceId")

            // 检查双端应用抑制策略
            if (shouldSuppressNotification(notification, targetDeviceId)) {
                logger.i(TAG, "Notification suppressed due to dual-app policy: ${notification.packageName}")
                return
            }

            // 序列化通知为 NetworkMessage
            val payload = createNotificationPayload(notification)
            val message = NetworkMessage(
                messageType = MessageType.NOTIFICATION,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            // 通过 MessageTransport 发送
            messageTransport.sendMessage(targetDeviceId, message)
                .catch { e ->
                    logger.e(TAG, "Failed to send notification to device $targetDeviceId", e)
                }
                .collect { result ->
                    if (result.success) {
                        // 标记为已同步
                        repository.markAsSynced(notification.id)
                        logger.i(TAG, "Notification synced successfully: ${notification.id}")
                    } else {
                        logger.e(TAG, "Failed to sync notification: ${result.error}")
                    }
                }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync notification", e)
            throw e
        }
    }

    /**
     * 同步通知到所有已连接设备
     */
    suspend fun syncNotificationToDevices(notification: AppNotification) {
        try {
            val devices = deviceManager.getConnectedDevices()
                .catch { e ->
                    logger.e(TAG, "Failed to get connected devices", e)
                }
                .first()

            devices.forEach { device ->
                syncNotification(notification, device.id)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync notification to devices", e)
        }
    }

    /**
     * 获取历史通知
     */
    override suspend fun getHistoryNotifications(limit: Int): List<AppNotification> {
        return try {
            repository.getAllNotifications(limit).first()
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get history notifications", e)
            emptyList()
        }
    }

    /**
     * 清除通知
     */
    override suspend fun clearNotification(notificationId: String) {
        try {
            repository.deleteNotificationById(notificationId)
            logger.d(TAG, "Notification cleared: $notificationId")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to clear notification", e)
            throw e
        }
    }

    /**
     * 接收远程通知
     */
    private fun startReceivingRemoteNotifications() {
        scope.launch {
            messageTransport.receiveMessages()
                .catch { e ->
                    logger.e(TAG, "Error receiving remote notifications", e)
                }
                .collect { message ->
                    if (message.messageType == MessageType.NOTIFICATION) {
                        handleRemoteNotification(message)
                    }
                }
        }
    }

    /**
     * 处理远程通知
     */
    private suspend fun handleRemoteNotification(message: NetworkMessage) {
        try {
            logger.d(TAG, "Received remote notification: ${message.messageId}")

            val payload = message.payload
            val notification = parseNotificationPayload(payload, message.sourceDevice)

            // 保存到数据库
            repository.insertNotification(notification)

            // 在本地生成系统通知
            showSystemNotification(notification)

            // 发送到流
            _notificationFlow.emit(notification)

            logger.i(TAG, "Remote notification handled: ${notification.id}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote notification", e)
        }
    }

    /**
     * 创建通知负载
     */
    private fun createNotificationPayload(notification: AppNotification): JsonObject {
        return JsonObject().apply {
            addProperty("notificationId", notification.id)
            addProperty("packageName", notification.packageName)
            addProperty("appName", notification.appName)
            addProperty("title", notification.title)
            addProperty("text", notification.text)
            addProperty("timestamp", notification.timestamp)
            addProperty("canReply", false) // TODO: 实现回复功能
        }
    }

    /**
     * 解析通知负载
     */
    private fun parseNotificationPayload(payload: JsonObject, sourceDeviceId: String): AppNotification {
        return AppNotification(
            id = payload.get("notificationId")?.asString ?: UUID.randomUUID().toString(),
            packageName = payload.get("packageName")?.asString ?: "",
            appName = payload.get("appName")?.asString ?: "",
            title = payload.get("title")?.asString ?: "",
            text = payload.get("text")?.asString ?: "",
            timestamp = payload.get("timestamp")?.asLong ?: System.currentTimeMillis(),
            deviceId = sourceDeviceId,
            isSynced = true
        )
    }

    /**
     * 显示系统通知
     */
    private fun showSystemNotification(notification: AppNotification) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${notification.appName} (Remote)")
                .setContentText(notification.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            notificationManager.notify(notification.id.hashCode(), builder.build())
            logger.d(TAG, "System notification shown: ${notification.id}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to show system notification", e)
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from other devices"
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 检查是否应该抑制通知（双端应用抑制策略）
     */
    private fun shouldSuppressNotification(notification: AppNotification, targetDeviceId: String): Boolean {
        // 检查是否启用双端应用抑制
        val suppressEnabled = preferences.getBoolean(PREF_DUAL_APP_SUPPRESS, true)
        if (!suppressEnabled) {
            return false
        }

        // 检查应用是否在白名单中（允许镜像）
        val whitelist = preferences.getStringSet(PREF_MIRROR_WHITELIST, emptySet()) ?: emptySet()
        if (whitelist.contains(notification.packageName)) {
            return false
        }

        // TODO: 检查目标设备是否安装了相同应用
        // 这需要设备间交换已安装应用列表
        // 暂时返回 false，后续可以扩展
        return false
    }

    /**
     * 回复通知
     */
    suspend fun replyToNotification(notificationId: String, replyText: String) {
        try {
            logger.d(TAG, "Replying to notification: $notificationId")
            // TODO: 实现 RemoteInput 回复功能
            logger.w(TAG, "Reply functionality not yet implemented")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to reply to notification", e)
        }
    }

    /**
     * 清除远程通知
     */
    suspend fun dismissNotification(notificationId: String, targetDeviceId: String) {
        try {
            logger.d(TAG, "Dismissing notification: $notificationId on device: $targetDeviceId")

            val payload = JsonObject().apply {
                addProperty("action", "dismiss")
                addProperty("notificationId", notificationId)
            }

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Dismiss command sent successfully")
                } else {
                    logger.e(TAG, "Failed to send dismiss command: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to dismiss notification", e)
        }
    }

    /**
     * 执行通知动作
     */
    suspend fun executeAction(notificationId: String, actionId: String, targetDeviceId: String) {
        try {
            logger.d(TAG, "Executing action: $actionId for notification: $notificationId")

            val payload = JsonObject().apply {
                addProperty("action", "execute")
                addProperty("notificationId", notificationId)
                addProperty("actionId", actionId)
            }

            val message = NetworkMessage(
                messageType = MessageType.CONTROL,
                messageId = UUID.randomUUID().toString(),
                sourceDevice = deviceManager.getLocalDevice().id,
                targetDevice = targetDeviceId,
                timestamp = System.currentTimeMillis(),
                payload = payload
            )

            messageTransport.sendMessage(targetDeviceId, message).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Action command sent successfully")
                } else {
                    logger.e(TAG, "Failed to send action command: ${result.error}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to execute action", e)
        }
    }

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "remote_notifications"
        private const val PREF_DUAL_APP_SUPPRESS = "dual_app_suppress"
        private const val PREF_MIRROR_WHITELIST = "mirror_whitelist"

        // 单例实例，用于 Service 访问
        @Volatile
        private var instance: NotificationManagerImpl? = null

        internal fun getInstance(): NotificationManagerImpl? = instance

        internal fun setInstance(manager: NotificationManagerImpl) {
            instance = manager
        }
    }
}
