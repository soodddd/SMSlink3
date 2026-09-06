package com.smslink.device

import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备数据仓库
 * 负责设备数据的持久化和查询
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "DeviceRepository"
    }

    /**
     * 保存设备
     */
    suspend fun saveDevice(device: Device) {
        try {
            deviceDao.insert(device)
            logger.d(TAG, "Device saved: ${device.name}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to save device", e)
            throw e
        }
    }

    /**
     * 更新设备
     */
    suspend fun updateDevice(device: Device) {
        try {
            deviceDao.update(device)
            logger.d(TAG, "Device updated: ${device.name}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update device", e)
            throw e
        }
    }

    /**
     * 删除设备
     */
    suspend fun deleteDevice(deviceId: String) {
        try {
            deviceDao.deleteById(deviceId)
            logger.d(TAG, "Device deleted: $deviceId")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to delete device", e)
            throw e
        }
    }

    /**
     * 根据ID获取设备
     */
    suspend fun getDeviceById(deviceId: String): Device? {
        return try {
            deviceDao.getById(deviceId)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get device by id", e)
            null
        }
    }

    /**
     * 获取所有已配对的设备
     */
    fun getAllPairedDevices(): Flow<List<Device>> {
        return deviceDao.getAllPaired()
    }

    /**
     * 获取所有设备
     */
    fun getAllDevices(): Flow<List<Device>> {
        return deviceDao.getAll()
    }

    /**
     * 获取已连接的设备（最近10秒内活跃）
     */
    fun getConnectedDevices(): Flow<List<Device>> {
        return deviceDao.getAllPaired().map { devices ->
            val currentTime = System.currentTimeMillis()
            devices.filter { device ->
                device.isConnected || currentTime - device.lastSeen < 10000 // 连接状态或最近活动
            }
        }
    }

    /**
     * Returns only links whose connection manager has an active authenticated
     * transport. Discovery heartbeats update lastSeen too, so they must not be
     * treated as permission to send business traffic.
     */
    fun getActuallyConnectedDevices(): Flow<List<Device>> =
        deviceDao.getAllPaired().map { devices -> devices.filter(Device::isConnected) }

    /**
     * 更新设备最后可见时间
     */
    suspend fun updateLastSeen(deviceId: String) {
        try {
            deviceDao.updateLastSeen(deviceId, System.currentTimeMillis())
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update last seen", e)
        }
    }

    suspend fun updateConnectionState(deviceId: String, connected: Boolean) {
        try {
            val device = deviceDao.getById(deviceId) ?: return
            deviceDao.update(
                device.copy(
                    isConnected = connected,
                    lastSeen = if (connected) System.currentTimeMillis() else device.lastSeen
                )
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update connection state", e)
        }
    }

    /**
     * 更新设备角色
     */
    suspend fun updateDeviceRole(deviceId: String, role: com.smslink.core.model.DeviceRole) {
        try {
            val device = deviceDao.getById(deviceId)
            if (device != null) {
                val updatedDevice = device.copy(role = role)
                deviceDao.update(updatedDevice)
                logger.d(TAG, "Device role updated: ${device.name} -> $role")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update device role", e)
            throw e
        }
    }

    /**
     * 检查设备是否已配对
     */
    suspend fun isDevicePaired(deviceId: String): Boolean {
        return try {
            val device = deviceDao.getById(deviceId)
            device?.isPaired ?: false
        } catch (e: Exception) {
            logger.e(TAG, "Failed to check if device is paired", e)
            false
        }
    }
}
