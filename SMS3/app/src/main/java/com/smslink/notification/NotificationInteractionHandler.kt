package com.smslink.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.device.IDeviceManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.transport.IMessageTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns notification PendingIntent/RemoteInput contexts and the remote control
 * protocol. The context is deliberately kept in memory: PendingIntents cannot
 * be safely serialized or reconstructed on another device.
 */
@Singleton
class NotificationInteractionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageTransport: IMessageTransport,
    private val deviceManager: IDeviceManager,
    private val logger: ILogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeNotifications = ConcurrentHashMap<String, NotificationContext>()

    fun registerNotification(
        notificationId: String,
        sbn: StatusBarNotification,
        sourceDeviceId: String
    ) {
        runCatching {
            val notification = sbn.notification
            activeNotifications[notificationId] = NotificationContext(
                notificationId = notificationId,
                packageName = sbn.packageName,
                tag = sbn.tag,
                id = sbn.id,
                key = sbn.key,
                actions = extractActions(notification),
                remoteInputs = extractRemoteInputs(notification),
                sourceDeviceId = sourceDeviceId,
                timestamp = System.currentTimeMillis()
            )
        }.onFailure { logger.e(TAG, "Failed to register notification", it) }
    }

    fun unregisterNotification(notificationId: String) {
        activeNotifications.remove(notificationId)
    }

    fun hasActiveNotification(notificationId: String): Boolean =
        activeNotifications.containsKey(notificationId)

    fun getAvailableActions(notificationId: String): List<NotificationAction> =
        activeNotifications[notificationId]?.actions ?: emptyList()

    fun supportsReply(notificationId: String): Boolean =
        activeNotifications[notificationId]?.actions?.any { it.hasRemoteInput } == true

    /** Execute a local reply; returns false when the notification is gone. */
    suspend fun replyLocally(notificationId: String, replyText: String): Boolean {
        if (replyText.length > MAX_REPLY_LENGTH) return false
        val notification = activeNotifications[notificationId] ?: return false
        val action = notification.actions.firstOrNull { it.hasRemoteInput } ?: return false
        val key = action.remoteInputs.firstOrNull() ?: REMOTE_INPUT_KEY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) return false
        val remoteInput = RemoteInput.Builder(key).setLabel("Reply").build()
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(remoteInput),
            fillIn,
            android.os.Bundle().apply { putCharSequence(key, replyText) }
        )
        return runCatching {
            action.actionIntent?.send(context, 0, fillIn) ?: return false
            true
        }.onFailure { logger.e(TAG, "Failed to send notification reply", it) }.getOrDefault(false)
    }

    /** Local UI entry point: execute locally or forward to the source device. */
    fun replyToNotification(notificationId: String, replyText: String, targetDeviceId: String) {
        scope.launch {
            if (!replyLocally(notificationId, replyText)) {
                sendReplyRequest(notificationId, replyText, targetDeviceId)
            }
        }
    }

    /** Local UI entry point: execute locally or forward to the source device. */
    fun executeAction(notificationId: String, actionIndex: Int, targetDeviceId: String) {
        scope.launch {
            if (!executeLocally(notificationId, actionIndex)) {
                sendActionRequest(notificationId, actionIndex, targetDeviceId)
            }
        }
    }

    /** Local UI entry point for dismissing a local or mirrored notification. */
    fun dismissNotification(notificationId: String, targetDeviceId: String) {
        scope.launch {
            val local = dismissLocally(notificationId)
            if (!local && targetDeviceId.isNotBlank()) {
                sendDismissRequest(notificationId, targetDeviceId)
            }
        }
    }

    suspend fun handleRemoteReply(
        notificationId: String,
        replyText: String,
        requesterDeviceId: String
    ) {
        val success = notificationId.isNotBlank() && replyLocally(notificationId, replyText)
        sendResult("reply", notificationId, requesterDeviceId, success)
    }

    suspend fun handleRemoteAction(
        notificationId: String,
        actionIndex: Int,
        requesterDeviceId: String
    ) {
        val success = notificationId.isNotBlank() && actionIndex >= 0 &&
            executeLocally(notificationId, actionIndex)
        sendResult("execute", notificationId, requesterDeviceId, success)
    }

    suspend fun handleRemoteDismiss(notificationId: String, requesterDeviceId: String) {
        val success = notificationId.isNotBlank() && dismissLocally(notificationId)
        sendResult("dismiss", notificationId, requesterDeviceId, success)
    }

    fun cleanupExpiredContexts() {
        val cutoff = System.currentTimeMillis() - CONTEXT_TTL_MS
        activeNotifications.entries.removeIf { it.value.timestamp < cutoff }
    }

    private fun extractActions(notification: Notification): List<NotificationAction> =
        notification.actions.orEmpty().mapIndexedNotNull { index, action ->
            action?.let {
                NotificationAction(
                    index = index,
                    title = it.title?.toString().orEmpty().ifBlank { "Action $index" },
                    actionIntent = it.actionIntent,
                    hasRemoteInput = it.remoteInputs?.isNotEmpty() == true,
                    remoteInputs = it.remoteInputs?.map(RemoteInput::getResultKey).orEmpty()
                )
            }
        }

    private fun extractRemoteInputs(notification: Notification): List<String> =
        notification.actions.orEmpty().flatMap { action ->
            action?.remoteInputs?.map(RemoteInput::getResultKey).orEmpty()
        }

    private suspend fun executeLocally(notificationId: String, actionIndex: Int): Boolean {
        val action = activeNotifications[notificationId]?.actions
            ?.firstOrNull { it.index == actionIndex } ?: return false
        return runCatching {
            action.actionIntent?.send() ?: return false
            true
        }.onFailure { logger.e(TAG, "Failed to execute notification action", it) }.getOrDefault(false)
    }

    internal fun dismissLocally(notificationId: String): Boolean {
        val notification = activeNotifications[notificationId] ?: return false
        val listener = NotificationListenerServiceImpl.getInstance()
        if (listener == null) {
            // NotificationManager.cancel() can only cancel notifications owned
            // by this package. External notifications must be cancelled via
            // NotificationListenerService.cancelNotification(key).
            return false
        }
        val dismissed = runCatching {
            listener.cancelNotification(notification.key)
            true
        }.onFailure {
            logger.e(TAG, "Failed to cancel notification through listener", it)
        }.getOrDefault(false)
        if (dismissed) activeNotifications.remove(notificationId, notification)
        return dismissed
    }

    private suspend fun sendReplyRequest(notificationId: String, text: String, targetDeviceId: String) {
        sendControl(targetDeviceId) {
            addProperty("action", "reply")
            addProperty("notificationId", notificationId)
            addProperty("replyText", text.take(MAX_REPLY_LENGTH))
        }
    }

    private suspend fun sendActionRequest(notificationId: String, index: Int, targetDeviceId: String) {
        sendControl(targetDeviceId) {
            addProperty("action", "execute")
            addProperty("notificationId", notificationId)
            addProperty("actionIndex", index)
        }
    }

    private suspend fun sendDismissRequest(notificationId: String, targetDeviceId: String) {
        sendControl(targetDeviceId) {
            addProperty("action", "dismiss")
            addProperty("notificationId", notificationId)
        }
    }

    private suspend fun sendResult(
        action: String,
        notificationId: String,
        targetDeviceId: String,
        success: Boolean
    ) {
        if (targetDeviceId.isBlank()) return
        sendControl(targetDeviceId) {
            addProperty("action", "result")
            addProperty("requestAction", action)
            addProperty("notificationId", notificationId)
            addProperty("success", success)
        }
    }

    private suspend fun sendControl(targetDeviceId: String, payloadBuilder: JsonObject.() -> Unit) {
        if (targetDeviceId.isBlank()) return
        val payload = JsonObject().apply(payloadBuilder)
        val message = NetworkMessage(
            messageType = MessageType.CONTROL,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = deviceManager.getLocalDevice().id,
            targetDevice = targetDeviceId,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )
        try {
            val result = messageTransport.sendMessage(targetDeviceId, message).firstOrNull()
                ?: run {
                    logger.e(TAG, "Notification control returned no send result")
                    return
                }
            if (!result.success) {
                logger.e(TAG, "Failed to send notification control: ${result.error}")
                return
            }
            val acknowledged = try {
                messageTransport.awaitDelivery(result.messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Notification control delivery ACK unavailable: ${e.message}")
                true
            }
            if (!acknowledged) {
                logger.e(TAG, "Notification control was not acknowledged")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to send notification control", e)
        }
    }

    data class NotificationContext(
        val notificationId: String,
        val packageName: String,
        val tag: String?,
        val id: Int,
        val key: String,
        val actions: List<NotificationAction>,
        val remoteInputs: List<String>,
        val sourceDeviceId: String,
        val timestamp: Long
    )

    data class NotificationAction(
        val index: Int,
        val title: String,
        val actionIntent: PendingIntent?,
        val hasRemoteInput: Boolean,
        val remoteInputs: List<String>
    )

    companion object {
        private const val TAG = "NotificationInteraction"
        private const val REMOTE_INPUT_KEY = "remote_input_reply"
        private const val MAX_REPLY_LENGTH = 4096
        private const val CONTEXT_TTL_MS = 60 * 60 * 1000L
    }
}

// Kept as top-level aliases for source compatibility with the original API.
typealias NotificationContext = NotificationInteractionHandler.NotificationContext
typealias NotificationAction = NotificationInteractionHandler.NotificationAction
