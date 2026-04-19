package com.smslink.feature.notification

import android.util.Log
import com.smslink.core.model.NotificationInfo
import com.smslink.feature.device.DeviceConnectionState
import com.smslink.feature.device.DeviceManager
import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationSyncManager @Inject constructor(
    private val deviceManager: DeviceManager,
    private val notificationRepository: NotificationRepository,
    private val notificationDisplayManager: NotificationDisplayManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NotificationSync"
    }

    init {
        observeConnectionState()
        observeIncomingMessages()
    }

    private fun observeConnectionState() {
        deviceManager.connectionState
            .onEach { state ->
                if (state is DeviceConnectionState.Connected) {
                    Log.d(TAG, "Device connected, ready to sync notifications")
                }
            }
            .launchIn(scope)
    }

    private fun observeIncomingMessages() {
        deviceManager.incomingMessages
            .onEach { message ->
                if (message.type == MessageType.NOTIFICATION_SYNC && message.payload.isNotEmpty()) {
                    handleIncomingNotification(message)
                }
            }
            .launchIn(scope)
    }

    suspend fun syncNotification(notification: NotificationInfo) {
        try {
            val connectionState = deviceManager.connectionState.value
            if (connectionState !is DeviceConnectionState.Connected) {
                Log.w(TAG, "No active connection, notification not synced")
                return
            }

            val message = Message(
                type = MessageType.NOTIFICATION_SYNC,
                messageId = System.currentTimeMillis(),
                payload = buildNotificationPayload(notification).toByteArray(Charsets.UTF_8)
            )

            deviceManager.sendMessage(message)
            Log.d(TAG, "Notification synced: ${notification.appName} - ${notification.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync notification", e)
        }
    }

    private fun buildControlPayload(action: String): ByteArray {
        return JSONObject()
            .put("action", action)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private suspend fun handleIncomingNotification(message: Message) {
        try {
            val payloadString = String(message.payload, Charsets.UTF_8)
            val json = JSONObject(payloadString)

            when (json.optString("action")) {
                "history_request" -> return
                else -> {
                    val notification = parseNotificationPayload(payloadString)
                    notificationRepository.saveNotification(notification)
                    notificationDisplayManager.displayNotification(notification)
                    Log.d(TAG, "Received notification: ${notification.appName} - ${notification.title}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming notification", e)
        }
    }

    private fun buildNotificationPayload(notification: NotificationInfo): String {
        val json = JSONObject().apply {
            put("id", notification.id)
            put("appName", notification.appName)
            put("appPackage", notification.appPackage)
            put("title", notification.title)
            put("text", notification.text)
            put("deviceId", notification.deviceId)
            put("deviceName", notification.deviceName)
            put("timestamp", notification.timestamp)
            put("isRead", notification.isRead)
            notification.iconPath?.let { put("iconPath", it) }
        }
        return json.toString()
    }

    private fun parseNotificationPayload(payload: String): NotificationInfo {
        val json = JSONObject(payload)
        return NotificationInfo(
            id = json.getString("id"),
            appName = json.getString("appName"),
            appPackage = json.getString("appPackage"),
            title = json.getString("title"),
            text = json.getString("text"),
            deviceId = json.getString("deviceId"),
            deviceName = json.getString("deviceName"),
            timestamp = json.getLong("timestamp"),
            isRead = json.optBoolean("isRead", false),
            iconPath = json.optString("iconPath").takeIf { it.isNotEmpty() }
        )
    }

    suspend fun requestHistorySync() {
        try {
            val connectionState = deviceManager.connectionState.value
            if (connectionState !is DeviceConnectionState.Connected) {
                Log.w(TAG, "No active connection")
                return
            }

            val message = Message(
                type = MessageType.NOTIFICATION_SYNC,
                messageId = System.currentTimeMillis(),
                payload = buildControlPayload("history_request")
            )
            deviceManager.sendMessage(message)
            Log.d(TAG, "Requested notification history sync")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request history sync", e)
        }
    }
}
