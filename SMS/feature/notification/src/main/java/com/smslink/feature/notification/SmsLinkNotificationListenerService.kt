package com.smslink.feature.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.smslink.core.model.NotificationInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 系统通知监听服务
 * 监听系统通知并同步到其他设备
 */
@AndroidEntryPoint
class SmsLinkNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var notificationSyncManager: NotificationSyncManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NotificationListener"

        // 忽略的系统应用包名
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.smslink" // 忽略自己的通知
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "NotificationListenerService destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // 过滤系统通知
            if (shouldIgnoreNotification(sbn)) {
                return
            }

            val notification = sbn.notification ?: return
            val packageName = sbn.packageName
            val appName = getAppName(packageName)

            // 提取通知内容
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // 如果标题和内容都为空，忽略
            if (title.isEmpty() && text.isEmpty()) {
                return
            }

            // 创建通知信息
            val notificationInfo = NotificationInfo(
                id = UUID.randomUUID().toString(),
                appName = appName,
                appPackage = packageName,
                title = title,
                text = text,
                deviceId = "local", // 本地设备
                deviceName = "本机",
                timestamp = sbn.postTime,
                isRead = false,
                iconPath = null
            )

            // 保存到本地数据库
            serviceScope.launch {
                try {
                    notificationRepository.saveNotification(notificationInfo)
                    Log.d(TAG, "Notification saved: $appName - $title")

                    // 同步到其他设备
                    notificationSyncManager.syncNotification(notificationInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save notification", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 可选：处理通知移除事件
        Log.d(TAG, "Notification removed: ${sbn.packageName}")
    }

    /**
     * 判断是否应该忽略该通知
     */
    private fun shouldIgnoreNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName

        // 忽略系统应用
        if (IGNORED_PACKAGES.contains(packageName)) {
            return true
        }

        val notification = sbn.notification ?: return true

        // 忽略持续通知（如音乐播放器）
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            return true
        }

        // 忽略低优先级通知
        if (notification.priority < Notification.PRIORITY_DEFAULT) {
            return true
        }

        return false
    }

    /**
     * 获取应用名称
     */
    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
