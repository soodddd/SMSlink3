package com.smslink.ui.bridge

import android.content.Context
import com.smslink.core.model.NotificationInfo
import com.smslink.feature.notification.NotificationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知桥接层
 * 连接 UI 和后端通知管理逻辑
 */
@Singleton
class NotificationBridge @Inject constructor(
    private val notificationRepository: NotificationRepository
) {
    /**
     * 获取通知历史
     */
    fun getNotificationHistory(): Flow<List<NotificationInfo>> {
        return notificationRepository.getNotificationHistory()
    }

    /**
     * 搜索通知
     */
    fun searchNotifications(query: String): Flow<List<NotificationInfo>> {
        return notificationRepository.searchNotifications(query)
    }

    /**
     * 按设备筛选通知
     */
    fun filterByDevice(deviceId: String?): Flow<List<NotificationInfo>> {
        return notificationRepository.filterByDevice(deviceId)
    }

    /**
     * 获取所有设备 ID（用于筛选）
     */
    fun getAllDeviceIds(): Flow<List<String>> {
        return notificationRepository.getAllDeviceIds()
    }

    /**
     * 获取未读通知数量
     */
    fun getUnreadCount(): Flow<Int> {
        return notificationRepository.getUnreadCount()
    }

    /**
     * 标记为已读
     */
    suspend fun markAsRead(notificationId: String) {
        notificationRepository.markAsRead(notificationId)
    }

    /**
     * 标记所有为已读
     */
    suspend fun markAllAsRead() {
        notificationRepository.markAllAsRead()
    }

    /**
     * 删除通知
     */
    suspend fun deleteNotification(notificationId: String) {
        notificationRepository.deleteNotification(notificationId)
    }

    /**
     * 清空通知历史
     */
    suspend fun clearHistory() {
        notificationRepository.clearHistory()
    }
}
