package com.smslink.core.database.dao

import androidx.room.*
import com.smslink.core.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * 设备数据访问对象
 */
@Dao
interface DeviceDao {
    /**
     * 插入设备
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    /**
     * 更新设备
     */
    @Update
    suspend fun update(device: Device)

    /**
     * 删除设备
     */
    @Delete
    suspend fun delete(device: Device)

    /**
     * 根据ID查询设备
     */
    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getById(deviceId: String): Device?

    /**
     * 获取所有已配对的设备
     */
    @Query("SELECT * FROM devices WHERE isPaired = 1 ORDER BY lastSeen DESC")
    fun getAllPaired(): Flow<List<Device>>

    /**
     * 获取所有设备
     */
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAll(): Flow<List<Device>>

    /**
     * 根据ID删除设备
     */
    @Query("DELETE FROM devices WHERE id = :deviceId")
    suspend fun deleteById(deviceId: String)

    /**
     * 更新设备最后可见时间
     */
    @Query("UPDATE devices SET lastSeen = :timestamp WHERE id = :deviceId")
    suspend fun updateLastSeen(deviceId: String, timestamp: Long)
}
