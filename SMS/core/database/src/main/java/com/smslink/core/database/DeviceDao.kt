package com.smslink.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
    suspend fun insert(device: DeviceEntity)

    /**
     * 更新设备
     */
    @Update
    suspend fun update(device: DeviceEntity)

    /**
     * 删除设备
     */
    @Delete
    suspend fun delete(device: DeviceEntity)

    /**
     * 根据 ID 查询设备
     */
    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?

    /**
     * 查询所有设备
     */
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    /**
     * 查询已配对设备
     */
    @Query("SELECT * FROM devices WHERE isPaired = 1 ORDER BY lastSeen DESC")
    fun getPairedDevices(): Flow<List<DeviceEntity>>

    /**
     * 查询未配对设备
     */
    @Query("SELECT * FROM devices WHERE isPaired = 0 ORDER BY lastSeen DESC")
    fun getUnpairedDevices(): Flow<List<DeviceEntity>>

    /**
     * 更新设备最后可见时间
     */
    @Query("UPDATE devices SET lastSeen = :timestamp WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, timestamp: Long)

    /**
     * 更新设备配对状态
     */
    @Query("UPDATE devices SET isPaired = :isPaired WHERE deviceId = :deviceId")
    suspend fun updatePairingStatus(deviceId: String, isPaired: Boolean)

    /**
     * 删除所有未配对设备
     */
    @Query("DELETE FROM devices WHERE isPaired = 0")
    suspend fun deleteUnpairedDevices()

    /**
     * 删除指定时间之前的未配对设备
     */
    @Query("DELETE FROM devices WHERE isPaired = 0 AND lastSeen < :timestamp")
    suspend fun deleteOldUnpairedDevices(timestamp: Long)
}
