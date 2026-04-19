package com.smslink.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 通知数据访问对象
 */
@Dao
interface NotificationDao {

    /**
     * 获取所有通知（按时间倒序）
     */
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    /**
     * 根据 ID 获取通知
     */
    @Query("SELECT * FROM notifications WHERE id = :notificationId")
    suspend fun getNotificationById(notificationId: String): NotificationEntity?

    /**
     * 搜索通知（按应用名、标题、内容）
     */
    @Query("""
        SELECT * FROM notifications
        WHERE appName LIKE '%' || :query || '%'
        OR title LIKE '%' || :query || '%'
        OR text LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    fun searchNotifications(query: String): Flow<List<NotificationEntity>>

    /**
     * 按设备筛选通知
     */
    @Query("SELECT * FROM notifications WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getNotificationsByDevice(deviceId: String): Flow<List<NotificationEntity>>

    /**
     * 按应用筛选通知
     */
    @Query("SELECT * FROM notifications WHERE appPackage = :appPackage ORDER BY timestamp DESC")
    fun getNotificationsByApp(appPackage: String): Flow<List<NotificationEntity>>

    /**
     * 获取未读通知数量
     */
    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    /**
     * 插入通知
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    /**
     * 批量插入通知
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<NotificationEntity>)

    /**
     * 更新通知
     */
    @Update
    suspend fun updateNotification(notification: NotificationEntity)

    /**
     * 标记为已读
     */
    @Query("UPDATE notifications SET isRead = 1 WHERE id = :notificationId")
    suspend fun markAsRead(notificationId: String)

    /**
     * 标记所有为已读
     */
    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    /**
     * 删除通知
     */
    @Query("DELETE FROM notifications WHERE id = :notificationId")
    suspend fun deleteNotification(notificationId: String)

    /**
     * 删除设备的所有通知
     */
    @Query("DELETE FROM notifications WHERE deviceId = :deviceId")
    suspend fun deleteNotificationsByDevice(deviceId: String)

    /**
     * 清空所有通知
     */
    @Query("DELETE FROM notifications")
    suspend fun clearAll()

    /**
     * 获取所有设备 ID（用于筛选）
     */
    @Query("SELECT DISTINCT deviceId FROM notifications")
    fun getAllDeviceIds(): Flow<List<String>>

    /**
     * 获取所有应用包名（用于筛选）
     */
    @Query("SELECT DISTINCT appPackage FROM notifications")
    fun getAllAppPackages(): Flow<List<String>>
}
