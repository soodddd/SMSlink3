package com.smslink.notification

import android.app.Notification
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.device.IDeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * System notification listener implementation.
 * Filters notifications and forwards them to the sync manager.
 */
@AndroidEntryPoint
class NotificationListenerServiceImpl : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var logger: ILogger

    @Inject
    lateinit var deviceManager: IDeviceManager

    @Inject
    lateinit var notificationManager: NotificationManagerImpl

    @Inject
    lateinit var interactionHandler: NotificationInteractionHandler

    @Inject
    lateinit var preferences: SharedPreferences

    private val notificationFilter by lazy { NotificationFilter(preferences) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logger.i(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (instance === this) instance = null
        logger.i(TAG, "NotificationListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        logger.i(TAG, "NotificationListenerService connected")
        notificationManager.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        logger.w(TAG, "NotificationListenerService disconnected")
        notificationManager.onListenerDisconnected()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            notificationManager.shouldRebindListener()
        ) {
            requestRebind(android.content.ComponentName(this, javaClass))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        serviceScope.launch {
            try {
                val packageName = sbn.packageName

                if (packageName == applicationContext.packageName) {
                    return@launch
                }

                if (!notificationFilter.shouldSync(packageName)) {
                    logger.d(TAG, "Notification filtered: $packageName")
                    return@launch
                }

                val notification = sbn.notification
                val appNotification = extractNotification(sbn, notification)

                interactionHandler.registerNotification(
                    notificationId = appNotification.id,
                    sbn = sbn,
                    sourceDeviceId = appNotification.deviceId
                )
                notificationManager.onNotificationPosted(appNotification)
            } catch (e: Exception) {
                logger.e(TAG, "Failed to process notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            // Mirrored notifications are posted by this application. Do not
            // reflect their lifecycle back to the peer as if they were local.
            if (sbn.packageName == applicationContext.packageName) return
            val notificationId = generateNotificationId(sbn)
            interactionHandler.unregisterNotification(notificationId)
            notificationManager.onNotificationRemoved(notificationId)
            logger.d(TAG, "Notification removed: $notificationId")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to process notification removal", e)
        }
    }

    private fun extractNotification(
        sbn: StatusBarNotification,
        notification: Notification
    ): AppNotification {
        val extras = notification.extras
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString() ?: ""
        val appName = getAppName(sbn.packageName)

        return AppNotification(
            id = generateNotificationId(sbn),
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            deviceId = getCurrentDeviceId(),
            isSynced = false
        )
    }

    private fun generateNotificationId(sbn: StatusBarNotification): String {
        return "${sbn.packageName}_${sbn.id}_${sbn.postTime}"
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun getCurrentDeviceId(): String {
        return runCatching { deviceManager.getLocalDevice().id }
            .getOrElse {
                logger.w(TAG, "Falling back to generated notification device id: ${it.message ?: it::class.java.simpleName}")
                "local_device"
            }
    }

    companion object {
        private const val TAG = "NotificationListener"

        @Volatile
        private var instance: NotificationListenerServiceImpl? = null

        fun getInstance(): NotificationListenerServiceImpl? = instance

        fun requestRebind(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(
                    android.content.ComponentName(context, NotificationListenerServiceImpl::class.java)
                )
            }
        }

        fun requestUnbind() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                instance?.requestUnbind()
            }
        }
    }
}

/**
 * Notification filter that allows whitelist/blacklist modes.
 */
class NotificationFilter(private val preferences: SharedPreferences) {

    fun shouldSync(packageName: String): Boolean {
        val filterMode = preferences.getString(PREF_FILTER_MODE, FILTER_MODE_ALL)
            ?: FILTER_MODE_ALL

        return when (filterMode) {
            FILTER_MODE_ALL -> true
            FILTER_MODE_WHITELIST -> isInWhitelist(packageName)
            FILTER_MODE_BLACKLIST -> !isInBlacklist(packageName)
            else -> false
        }
    }

    private fun isInWhitelist(packageName: String): Boolean {
        val whitelist = preferences.getStringSet(PREF_WHITELIST, emptySet()) ?: emptySet()
        return whitelist.contains(packageName)
    }

    private fun isInBlacklist(packageName: String): Boolean {
        val blacklist = preferences.getStringSet(PREF_BLACKLIST, emptySet()) ?: emptySet()
        return blacklist.contains(packageName)
    }

    fun addToWhitelist(packageName: String) {
        val currentSet = preferences.getStringSet(PREF_WHITELIST, emptySet()) ?: emptySet()
        val whitelist = currentSet.toMutableSet()
        whitelist.add(packageName)
        preferences.edit().putStringSet(PREF_WHITELIST, whitelist).apply()
    }

    fun removeFromWhitelist(packageName: String) {
        val currentSet = preferences.getStringSet(PREF_WHITELIST, emptySet()) ?: emptySet()
        val whitelist = currentSet.toMutableSet()
        whitelist.remove(packageName)
        preferences.edit().putStringSet(PREF_WHITELIST, whitelist).apply()
    }

    fun addToBlacklist(packageName: String) {
        val currentSet = preferences.getStringSet(PREF_BLACKLIST, emptySet()) ?: emptySet()
        val blacklist = currentSet.toMutableSet()
        blacklist.add(packageName)
        preferences.edit().putStringSet(PREF_BLACKLIST, blacklist).apply()
    }

    fun removeFromBlacklist(packageName: String) {
        val currentSet = preferences.getStringSet(PREF_BLACKLIST, emptySet()) ?: emptySet()
        val blacklist = currentSet.toMutableSet()
        blacklist.remove(packageName)
        preferences.edit().putStringSet(PREF_BLACKLIST, blacklist).apply()
    }

    fun setFilterMode(mode: String) {
        preferences.edit().putString(PREF_FILTER_MODE, mode).apply()
    }

    companion object {
        const val PREF_FILTER_MODE = "notification_filter_mode"
        const val PREF_WHITELIST = "notification_whitelist"
        const val PREF_BLACKLIST = "notification_blacklist"

        const val FILTER_MODE_ALL = "all"
        const val FILTER_MODE_WHITELIST = "whitelist"
        const val FILTER_MODE_BLACKLIST = "blacklist"
    }
}
