package com.smslink.feature.notification

import com.smslink.core.database.NotificationDao
import com.smslink.core.database.toEntity
import com.smslink.core.database.toNotificationInfo
import com.smslink.core.model.NotificationInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知仓库
 * 负责通知数据的持久化和查询
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val notificationDao: NotificationDao
) {

    /**
     * 获取所有通知历史
     */
    fun getNotificationHistory(): Flow<List<NotificationInfo>> {
        return notificationDao.getAllNotifications()
            .map { entities -> entities.map { it.toNotificationInfo() } }
    }

    /**
     * 根据 ID 获取通知
     */
    suspend fun getNotificationById(notificationId: String): NotificationInfo? {
        return notificationDao.getNotificationById(notificationId)?.toNotificationInfo()
    }

    /**
     * 搜索通知
     */
    fun searchNotifications(query: String): Flow<List<NotificationInfo>> {
        return notificationDao.searchNotifications(query)
            .map { entities -> entities.map { it.toNotificationInfo() } }
    }

    /**
     * 按设备筛选通知
     */
    fun filterByDevice(deviceId: String?): Flow<List<NotificationInfo>> {
        return if (deviceId == null) {
            getNotificationHistory()
        } else {
            notificationDao.getNotificationsByDevice(deviceId)
                .map { entities -> entities.map { it.toNotificationInfo() } }
        }
    }

    /**
     * 按应用筛选通知
     */
    fun filterByApp(appPackage: String): Flow<List<NotificationInfo>> {
        return notificationDao.getNotificationsByApp(appPackage)
            .map { entities -> entities.map { it.toNotificationInfo() } }
    }

    /**
     * 获取未读通知数量
     */
    fun getUnreadCount(): Flow<Int> {
        return notificationDao.getUnreadCount()
    }

    /**
     * 保存通知
     */
    suspend fun saveNotification(notification: NotificationInfo) {
        notificationDao.insertNotification(notification.toEntity())
    }

    /**
     * 批量保存通知
     */
    suspend fun saveNotifications(notifications: List<NotificationInfo>) {
        notificationDao.insertNotifications(notifications.map { it.toEntity() })
    }

    /**
     * 标记为已读
     */
    suspend fun markAsRead(notificationId: String) {
        notificationDao.markAsRead(notificationId)
    }

    /**
     * 标记所有为已读
     */
    suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }

    /**
     * 删除通知
     */
    suspend fun deleteNotification(notificationId: String) {
        notificationDao.deleteNotification(notificationId)
    }

    /**
     * 删除设备的所有通知
     */
    suspend fun deleteNotificationsByDevice(deviceId: String) {
        notificationDao.deleteNotificationsByDevice(deviceId)
    }

    /**
     * 清空所有通知历史
     */
    suspend fun clearHistory() {
        notificationDao.clearAll()
    }

    /**
     * 获取所有设备 ID（用于筛选）
     */
    fun getAllDeviceIds(): Flow<List<String>> {
        return notificationDao.getAllDeviceIds()
    }

    /**
     * 获取所有应用包名（用于筛选）
     */
    fun getAllAppPackages(): Flow<List<String>> {
        return notificationDao.getAllAppPackages()
    }
}
