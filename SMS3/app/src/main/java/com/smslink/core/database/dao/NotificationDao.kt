package com.smslink.core.database.dao

import androidx.room.*
import com.smslink.core.model.AppNotification
import kotlinx.coroutines.flow.Flow

/**
 * 通知数据访问对象
 */
@Dao
interface NotificationDao {
    /**
     * 插入通知
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: AppNotification)

    /**
     * 插入多条通知
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<AppNotification>)

    /**
     * 更新通知
     */
    @Update
    suspend fun update(notification: AppNotification)

    /**
     * 删除通知
     */
    @Delete
    suspend fun delete(notification: AppNotification)

    /**
     * 根据ID查询通知
     */
    @Query("SELECT * FROM notifications WHERE id = :notificationId")
    suspend fun getById(notificationId: String): AppNotification?

    /**
     * 获取所有通知
     */
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT :limit")
    fun getAll(limit: Int): Flow<List<AppNotification>>

    /**
     * 根据设备ID获取通知
     */
    @Query("SELECT * FROM notifications WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getByDeviceId(deviceId: String): Flow<List<AppNotification>>

    /**
     * 获取未同步的通知
     */
    @Query("SELECT * FROM notifications WHERE isSynced = 0 ORDER BY timestamp DESC")
    fun getUnsynced(): Flow<List<AppNotification>>

    /**
     * 标记通知为已同步
     */
    @Query("UPDATE notifications SET isSynced = 1 WHERE id = :notificationId")
    suspend fun markAsSynced(notificationId: String)

    /**
     * 根据ID删除通知
     */
    @Query("DELETE FROM notifications WHERE id = :notificationId")
    suspend fun deleteById(notificationId: String)

    /**
     * 清除所有通知
     */
    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}
