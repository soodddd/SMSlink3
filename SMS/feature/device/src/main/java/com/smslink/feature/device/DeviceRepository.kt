package com.smslink.feature.device

import com.smslink.core.database.DeviceDao
import com.smslink.core.database.toDeviceInfo
import com.smslink.core.database.toEntity
import com.smslink.core.model.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设备仓库
 * 封装设备数据访问逻辑
 */
class DeviceRepository(private val deviceDao: DeviceDao) {

    /**
     * 保存设备
     */
    suspend fun saveDevice(device: DeviceInfo, isPaired: Boolean = false) {
        deviceDao.insert(device.toEntity(isPaired))
    }

    /**
     * 更新设备
     */
    suspend fun updateDevice(device: DeviceInfo, isPaired: Boolean) {
        deviceDao.insert(device.toEntity(isPaired))
    }

    /**
     * 删除设备
     */
    suspend fun deleteDevice(device: DeviceInfo) {
        deviceDao.delete(device.toEntity())
    }

    /**
     * 根据 ID 获取设备
     */
    suspend fun getDeviceById(deviceId: String): DeviceInfo? {
        return deviceDao.getById(deviceId)?.toDeviceInfo()
    }

    /**
     * 获取所有设备
     */
    fun getAllDevices(): Flow<List<DeviceInfo>> {
        return deviceDao.getAllDevices().map { entities ->
            entities.map { it.toDeviceInfo() }
        }
    }

    /**
     * 获取已配对设备
     */
    fun getPairedDevices(): Flow<List<DeviceInfo>> {
        return deviceDao.getPairedDevices().map { entities ->
            entities.map { it.toDeviceInfo() }
        }
    }

    /**
     * 获取未配对设备
     */
    fun getUnpairedDevices(): Flow<List<DeviceInfo>> {
        return deviceDao.getUnpairedDevices().map { entities ->
            entities.map { it.toDeviceInfo() }
        }
    }

    /**
     * 更新设备最后可见时间
     */
    suspend fun updateLastSeen(deviceId: String) {
        deviceDao.updateLastSeen(deviceId, System.currentTimeMillis())
    }

    /**
     * 更新设备配对状态
     */
    suspend fun updatePairingStatus(deviceId: String, isPaired: Boolean) {
        deviceDao.updatePairingStatus(deviceId, isPaired)
    }

    /**
     * 清理未配对设备
     */
    suspend fun cleanupUnpairedDevices() {
        deviceDao.deleteUnpairedDevices()
    }

    /**
     * 清理旧的未配对设备（超过 24 小时）
     */
    suspend fun cleanupOldUnpairedDevices() {
        val threshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        deviceDao.deleteOldUnpairedDevices(threshold)
    }
}
