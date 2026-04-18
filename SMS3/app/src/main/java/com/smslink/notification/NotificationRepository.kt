package com.smslink.notification

import com.smslink.core.database.dao.NotificationDao
import com.smslink.core.model.AppNotification
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知数据仓库
 * 负责通知数据的持久化和查询
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val notificationDao: NotificationDao
) {

    /**
     * 获取所有通知
     */
    fun getAllNotifications(limit: Int = 100): Flow<List<AppNotification>> {
        return notificationDao.getAll(limit)
    }

    /**
     * 根据设备ID获取通知
     */
    fun getNotificationsByDevice(deviceId: String): Flow<List<AppNotification>> {
        return notificationDao.getByDeviceId(deviceId)
    }

    /**
     * 获取未同步的通知
     */
    fun getUnsyncedNotifications(): Flow<List<AppNotification>> {
        return notificationDao.getUnsynced()
    }

    /**
     * 根据ID获取通知
     */
    suspend fun getNotificationById(notificationId: String): AppNotification? {
        return notificationDao.getById(notificationId)
    }

    /**
     * 插入通知
     */
    suspend fun insertNotification(notification: AppNotification) {
        notificationDao.insert(notification)
    }

    /**
     * 批量插入通知
     */
    suspend fun insertNotifications(notifications: List<AppNotification>) {
        notificationDao.insertAll(notifications)
    }

    /**
     * 更新通知
     */
    suspend fun updateNotification(notification: AppNotification) {
        notificationDao.update(notification)
    }

    /**
     * 删除通知
     */
    suspend fun deleteNotification(notification: AppNotification) {
        notificationDao.delete(notification)
    }

    /**
     * 根据ID删除通知
     */
    suspend fun deleteNotificationById(notificationId: String) {
        notificationDao.deleteById(notificationId)
    }

    /**
     * 标记通知为已同步
     */
    suspend fun markAsSynced(notificationId: String) {
        notificationDao.markAsSynced(notificationId)
    }

    /**
     * 清除所有通知
     */
    suspend fun clearAll() {
        notificationDao.deleteAll()
    }
}
