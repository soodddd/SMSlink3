package com.smslink.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.MainActivity
import com.smslink.device.IDeviceManager
import com.smslink.device.observeLiveConnections
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    @Inject
    lateinit var interactionHandler: NotificationInteractionHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _notificationFlow = MutableSharedFlow<AppNotification>(replay = 0)
    @Volatile
    private var isListening = false
    @Volatile
    private var rebindEnabled = true
    private val gson = Gson()
    private val remoteAppInventories = ConcurrentHashMap<String, RemoteAppInventory>()
    private val pendingInventoryRequests = ConcurrentHashMap.newKeySet<String>()
    private val syncReceipts = NotificationSyncReceiptStore(context)

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
            if (!hasNotificationListenerPermission()) {
                rebindEnabled = false
                val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                // Application contexts need NEW_TASK on Android. Keep the
                // flag construction isolated so local JVM tests using the
                // Android framework stubs can still exercise the launch call.
                runCatching { settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(settingsIntent)
                logger.i(TAG, "Notification listener access is required; opened system settings")
                return
            }
            rebindEnabled = true
            isListening = true
            NotificationListenerServiceImpl.requestRebind(context)
            logger.i(TAG, "Notification listener access confirmed")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start notification listener", e)
        }
    }

    /**
     * 停止监听通知
     */
    override fun stopListening() {
        rebindEnabled = false
        if (!isListening) {
            logger.w(TAG, "Notification listener not started")
            return
        }

        try {
            NotificationListenerServiceImpl.requestUnbind()
            isListening = false
            logger.i(TAG, "Notification listener unbound")
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

    fun hasNotificationListenerPermission(): Boolean {
        return runCatching {
            val packageName = context.packageName
            if (packageName.isBlank()) return@runCatching false
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return@runCatching false
            enabled.split(':').any { component ->
                component.startsWith("$packageName/") || component == packageName
            }
        }
            .getOrDefault(false)
    }

    internal fun onListenerConnected() {
        isListening = true
        logger.i(TAG, "Notification listener connected")
    }

    internal fun onListenerDisconnected() {
        isListening = false
        logger.w(TAG, "Notification listener disconnected")
    }

    internal fun shouldRebindListener(): Boolean =
        rebindEnabled && hasNotificationListenerPermission()

    /**
     * 处理新通知
     * 由 NotificationListenerService 调用
     */
    internal fun onNotificationPosted(notification: AppNotification) {
        scope.launch {
            try {
                // Room's primary-key conflict policy is the final guard. The
                // identity check here avoids emitting the same status-bar
                // event twice after a process restart, while also refusing
                // to treat an unrelated repository fallback object as a hit.
                val existing = try {
                    repository.getNotificationById(notification.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Could not read existing notification ${notification.id}: ${e.message}")
                    null
                }
                if (existing?.id == notification.id) {
                    // A status-bar notification can be updated in place. Do
                    // not drop a real content update merely because its key
                    // is unchanged; only an identical event is a duplicate.
                    if (existing == notification) {
                        // The same status-bar event may be delivered again after
                        // a transient transport failure. Keep the local record
                        // idempotent, but retry any device that has not ACKed it.
                        syncNotificationToDevices(notification)
                        return@launch
                    }
                    repository.updateNotification(notification)
                } else {
                    repository.insertNotification(notification)
                }

                // 发送到流
                _notificationFlow.emit(notification)

                // NotificationListenerService is the system-bound source of
                // truth. Forward immediately so syncing does not depend on a
                // second manually started ordinary service collector.
                syncNotificationToDevices(notification)

                logger.d(TAG, "Notification posted: ${notification.appName} - ${notification.title}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Failed to handle notification", e)
            }
        }
    }

    /**
     * 同步通知到其他设备
     */
    override suspend fun syncNotification(notification: AppNotification, targetDeviceId: String) {
        syncNotificationInternal(
            notification = notification,
            targetDeviceId = targetDeviceId,
            markLegacySynced = true
        )
    }

    /**
     * Returns whether the notification was delivered (or was already
     * delivered). The public API remains Unit for source compatibility, while
     * the fan-out path needs the result to avoid marking a multi-device sync
     * complete after only one peer succeeds.
     */
    private suspend fun syncNotificationInternal(
        notification: AppNotification,
        targetDeviceId: String,
        markLegacySynced: Boolean
    ): Boolean {
        try {
            logger.d(TAG, "Syncing notification ${notification.id} to device $targetDeviceId")

            // 检查双端应用抑制策略
            if (shouldSuppressNotification(notification, targetDeviceId)) {
                logger.i(TAG, "Notification suppressed due to dual-app policy: ${notification.packageName}")
                return false
            }
            val contentFingerprint = syncReceipts.fingerprint(notification)
            if (syncReceipts.isSynced(notification.id, targetDeviceId, contentFingerprint)) {
                return true
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

            // A successful frame write is not delivery. Wait for the
            // transport ACK before recording this notification as synced.
            if (!sendAndAwaitDelivery(targetDeviceId, message, "Notification")) {
                throw IllegalStateException("Notification was not acknowledged")
            }
            syncReceipts.markSynced(notification.id, targetDeviceId, contentFingerprint)
            if (markLegacySynced) {
                // AppNotification.isSynced is a legacy aggregate flag. The
                // per-device receipt above remains authoritative for fan-out.
                repository.markAsSynced(notification.id)
            }
            logger.i(TAG, "Notification synced successfully: ${notification.id}")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync notification", e)
            return false
        }
    }

    /**
     * 同步通知到所有已连接设备
     */
    suspend fun syncNotificationToDevices(notification: AppNotification) {
        try {
            val devices = deviceManager.observeLiveConnections()
                .catch { e ->
                    logger.e(TAG, "Failed to get connected devices", e)
                }
                .first()

            val localId = deviceManager.getLocalDevice().id
            val targets = devices.filter { device ->
                device.id != localId && device.isPaired
            }
            if (targets.isEmpty()) return

            var allDelivered = true
            val deliveredTargetIds = mutableSetOf<String>()
            val contentFingerprint = syncReceipts.fingerprint(notification)
            targets.forEach { device ->
                if (syncReceipts.isSynced(notification.id, device.id, contentFingerprint)) {
                    deliveredTargetIds += device.id
                    return@forEach
                }
                if (!syncNotificationInternal(notification, device.id, markLegacySynced = false)) {
                    allDelivered = false
                } else {
                    deliveredTargetIds += device.id
                }
            }
            if (allDelivered && deliveredTargetIds.containsAll(targets.map { it.id })) {
                repository.markAsSynced(notification.id)
            }
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
            val dismissedLocally = if (::interactionHandler.isInitialized) {
                interactionHandler.dismissLocally(notificationId)
            } else {
                false
            }
            repository.deleteNotificationById(notificationId)
            if (!dismissedLocally) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.cancel(notificationId.hashCode())
            }
            syncReceipts.clearNotification(notificationId)
            logger.d(TAG, "Notification cleared: $notificationId")
        } catch (e: CancellationException) {
            throw e
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
                    when (message.messageType) {
                        MessageType.NOTIFICATION -> handleRemoteNotification(message)
                        MessageType.CONTROL -> handleRemoteControl(message)
                        else -> Unit
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

            val localId = deviceManager.getLocalDevice().id
            if (!message.isStructurallyValid(
                    expectedSource = message.sourceDevice,
                    expectedTarget = localId
                )
            ) return

            val payload = message.payload
            if (!isValidNotificationPayload(payload)) return
            val remoteId = payload.get("notificationId")?.asString.orEmpty()
            val existing = try {
                repository.getNotificationById(remoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Could not read existing notification $remoteId: ${e.message}")
                null
            }
            val notification = parseNotificationPayload(payload, message.sourceDevice)
            if (existing?.id == remoteId) {
                if (existing == notification) return
                repository.updateNotification(notification)
            } else {
                repository.insertNotification(notification)
            }

            // 在本地生成系统通知
            showSystemNotification(notification)

            // 发送到流
            _notificationFlow.emit(notification)

            logger.i(TAG, "Remote notification handled: ${notification.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to handle remote notification", e)
        }
    }

    /** Propagate a user dismissal while retaining the local history record. */
    internal fun onNotificationRemoved(notificationId: String) {
        if (notificationId.isBlank()) return
        scope.launch {
            val localId = runCatching { deviceManager.getLocalDevice().id }.getOrNull() ?: return@launch
            try {
                deviceManager.observeLiveConnections().first()
                    .filter { it.id != localId && it.isPaired }
                    .forEach { device ->
                        val message = NetworkMessage(
                            messageType = MessageType.CONTROL,
                            messageId = UUID.randomUUID().toString(),
                            sourceDevice = localId,
                            targetDevice = device.id,
                            timestamp = System.currentTimeMillis(),
                            payload = JsonObject().apply {
                                addProperty("action", "remove")
                                addProperty("notificationId", notificationId)
                            }
                        )
                        sendAndAwaitDelivery(device.id, message, "Notification removal")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Failed to propagate notification removal", e)
            }
        }
    }

    private suspend fun handleRemoteControl(message: NetworkMessage) {
        val localId = runCatching { deviceManager.getLocalDevice().id }.getOrNull() ?: return
        if (!message.isStructurallyValid(
                expectedSource = message.sourceDevice,
                expectedTarget = localId
            ) || message.sourceDevice == localId
        ) return

        when (message.payload.get("action")?.asString?.lowercase()) {
            "app_inventory_request" -> sendAppInventory(message.sourceDevice)
            "app_inventory" -> cacheRemoteAppInventory(message.sourceDevice, message.payload)
            "dismiss", "remove" -> {
                val notificationId = message.payload.get("notificationId")?.asString.orEmpty()
                if (notificationId.isNotBlank()) {
                    if (::interactionHandler.isInitialized &&
                        interactionHandler.hasActiveNotification(notificationId)
                    ) {
                        interactionHandler.handleRemoteDismiss(notificationId, message.sourceDevice)
                    } else {
                        clearNotification(notificationId)
                    }
                }
            }
            "reply" -> if (::interactionHandler.isInitialized) interactionHandler.handleRemoteReply(
                notificationId = message.payload.get("notificationId")?.asString.orEmpty(),
                replyText = message.payload.get("replyText")?.asString.orEmpty(),
                requesterDeviceId = message.sourceDevice
            )
            "execute" -> if (::interactionHandler.isInitialized) interactionHandler.handleRemoteAction(
                notificationId = message.payload.get("notificationId")?.asString.orEmpty(),
                actionIndex = message.payload.get("actionIndex")?.asInt
                    ?: message.payload.get("actionId")?.asString?.toIntOrNull()
                    ?: -1,
                requesterDeviceId = message.sourceDevice
            )
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
            addProperty(
                "canReply",
                ::interactionHandler.isInitialized && interactionHandler.supportsReply(notification.id)
            )
        }
    }

    /** A successful Flow result means the frame was written, not delivered. */
    private suspend fun sendAndAwaitDelivery(
        targetDeviceId: String,
        message: NetworkMessage,
        description: String
    ): Boolean {
        val result = messageTransport.sendMessage(targetDeviceId, message).firstOrNull()
            ?: run {
                logger.e(TAG, "$description send returned no result")
                return false
            }
        if (!result.success) {
            logger.e(TAG, "$description was not queued: ${result.error}")
            return false
        }
        return try {
            messageTransport.awaitDelivery(result.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep compatibility with old fake/third-party transports that
            // predate awaitDelivery; the concrete transport has a real ACK.
            logger.w(TAG, "$description delivery ACK unavailable: ${e.message}")
            true
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
            val contentIntent = PendingIntent.getActivity(
                context,
                notification.id.hashCode(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${notification.appName} (Remote)")
                .setContentText(notification.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
                .setContentIntent(contentIntent)
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

        val inventory = remoteAppInventories[targetDeviceId]
        if (inventory != null &&
            System.currentTimeMillis() - inventory.timestamp <= APP_INVENTORY_TTL_MS
        ) {
            return notification.packageName in inventory.packages
        }

        // Inventory exchange is best-effort and safe by default: until the
        // peer answers, keep the notification visible rather than dropping a
        // potentially important alert.
        if (pendingInventoryRequests.add(targetDeviceId)) {
            scope.launch {
                try {
                    val localId = deviceManager.getLocalDevice().id
                    val request = NetworkMessage(
                        messageType = MessageType.CONTROL,
                        messageId = UUID.randomUUID().toString(),
                        sourceDevice = localId,
                        targetDevice = targetDeviceId,
                        timestamp = System.currentTimeMillis(),
                        payload = JsonObject().apply {
                            addProperty("action", "app_inventory_request")
                        }
                    )
                    sendAndAwaitDelivery(targetDeviceId, request, "App inventory request")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Failed to request app inventory from $targetDeviceId: ${e.message}")
                } finally {
                    pendingInventoryRequests.remove(targetDeviceId)
                }
            }
        }
        return false
    }

    private suspend fun sendAppInventory(targetDeviceId: String) {
        val localId = deviceManager.getLocalDevice().id
        val packages = runCatching {
            context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .take(MAX_APP_INVENTORY_SIZE)
                .toList()
        }.getOrDefault(emptyList())
        val payload = JsonObject().apply {
            addProperty("action", "app_inventory")
            add("packages", JsonArray().also { array -> packages.forEach(array::add) })
        }
        val message = NetworkMessage(
            messageType = MessageType.CONTROL,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = localId,
            targetDevice = targetDeviceId,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )
        sendAndAwaitDelivery(targetDeviceId, message, "App inventory")
    }

    private fun cacheRemoteAppInventory(sourceDeviceId: String, payload: JsonObject) {
        val packages = payload.getAsJsonArray("packages")
            ?.mapNotNull { element ->
                runCatching { element.asString.takeIf(String::isNotBlank) }.getOrNull()
            }
            ?.take(MAX_APP_INVENTORY_SIZE)
            ?.toSet()
            ?: return
        remoteAppInventories[sourceDeviceId] = RemoteAppInventory(
            timestamp = System.currentTimeMillis(),
            packages = packages
        )
    }

    private fun isValidNotificationPayload(payload: JsonObject): Boolean {
        val notificationId = runCatching { payload.get("notificationId")?.asString.orEmpty() }
            .getOrDefault("")
        val packageName = runCatching { payload.get("packageName")?.asString.orEmpty() }
            .getOrDefault("")
        val appName = runCatching { payload.get("appName")?.asString.orEmpty() }
            .getOrDefault("")
        val title = runCatching { payload.get("title")?.asString.orEmpty() }
            .getOrDefault("")
        val text = runCatching { payload.get("text")?.asString.orEmpty() }
            .getOrDefault("")
        val timestamp = runCatching { payload.get("timestamp")?.asLong ?: 0L }
            .getOrDefault(0L)
        return notificationId.isNotBlank() && notificationId.length <= MAX_NOTIFICATION_ID_LENGTH &&
            packageName.isNotBlank() && packageName.length <= MAX_PACKAGE_NAME_LENGTH &&
            appName.length <= MAX_APP_NAME_LENGTH && title.length <= MAX_TITLE_LENGTH &&
            text.length <= MAX_TEXT_LENGTH && timestamp > 0L &&
            timestamp <= System.currentTimeMillis() + MAX_FUTURE_TIMESTAMP_MS
    }

    /**
     * 回复通知
     */
    suspend fun replyToNotification(notificationId: String, replyText: String) {
        try {
            if (::interactionHandler.isInitialized) {
                interactionHandler.replyLocally(notificationId, replyText)
            } else {
                logger.w(TAG, "Notification interaction handler is unavailable")
            }
        } catch (e: CancellationException) {
            throw e
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

            if (sendAndAwaitDelivery(targetDeviceId, message, "Dismiss command")) {
                logger.i(TAG, "Dismiss command sent successfully")
            }
        } catch (e: CancellationException) {
            throw e
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

            if (sendAndAwaitDelivery(targetDeviceId, message, "Action command")) {
                logger.i(TAG, "Action command sent successfully")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to execute action", e)
        }
    }

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "remote_notifications"
        private const val PREF_DUAL_APP_SUPPRESS = "dual_app_suppress"
        private const val PREF_MIRROR_WHITELIST = "mirror_whitelist"
        private const val APP_INVENTORY_TTL_MS = 10 * 60 * 1000L
        private const val MAX_APP_INVENTORY_SIZE = 4000
        private const val MAX_NOTIFICATION_ID_LENGTH = 512
        private const val MAX_PACKAGE_NAME_LENGTH = 256
        private const val MAX_APP_NAME_LENGTH = 512
        private const val MAX_TITLE_LENGTH = 2048
        private const val MAX_TEXT_LENGTH = 100_000
        private const val MAX_FUTURE_TIMESTAMP_MS = 10 * 60 * 1000L

        // 单例实例，用于 Service 访问
        @Volatile
        private var instance: NotificationManagerImpl? = null

        internal fun getInstance(): NotificationManagerImpl? = instance

        internal fun setInstance(manager: NotificationManagerImpl) {
            instance = manager
        }
    }

    private data class RemoteAppInventory(
        val timestamp: Long,
        val packages: Set<String>
    )
}
