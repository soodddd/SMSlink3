package com.smslink.notification

import com.smslink.core.model.AppNotification
import kotlinx.coroutines.flow.Flow

/**
 * 通知管理器接口
 * 负责应用通知的同步和管理
 */
interface INotificationManager {
    /**
     * 开始监听通知
     */
    fun startListening()

    /**
     * 停止监听通知
     */
    fun stopListening()

    /**
     * 获取通知流
     * @return 通知流
     */
    fun getNotifications(): Flow<AppNotification>

    /**
     * 同步通知到其他设备
     * @param notification 通知
     * @param targetDeviceId 目标设备ID
     */
    suspend fun syncNotification(notification: AppNotification, targetDeviceId: String)

    /**
     * 获取历史通知
     * @param limit 数量限制
     * @return 通知列表
     */
    suspend fun getHistoryNotifications(limit: Int): List<AppNotification>

    /**
     * 清除通知
     * @param notificationId 通知ID
     */
    suspend fun clearNotification(notificationId: String)
}
