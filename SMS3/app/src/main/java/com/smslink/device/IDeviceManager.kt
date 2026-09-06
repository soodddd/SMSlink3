package com.smslink.device

import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.PairResult
import kotlinx.coroutines.flow.Flow

/**
 * 设备管理器接口
 * 负责设备发现、配对和管理
 */
interface IDeviceManager {
    /**
     * 开始设备发现
     */
    fun startDiscovery()

    /**
     * 停止设备发现
     */
    fun stopDiscovery()

    /**
     * 配对设备
     * @param deviceId 设备ID
     * @param qrCode 二维码内容
     * @return 配对结果流
     */
    fun pairDevice(deviceId: String, qrCode: String): Flow<PairResult>

    /**
     * 获取已连接的设备列表
     * @return 设备列表流
     */
    fun getConnectedDevices(): Flow<List<Device>>

    /** Authenticated live links for business traffic. Compatibility
     * implementations fall back to getConnectedDevices(). */
    fun getLiveConnectedDevices(): Flow<List<Device>> = getConnectedDevices()

    /**
     * 设置设备角色
     * @param deviceId 设备ID
     * @param role 设备角色
     */
    suspend fun setDeviceRole(deviceId: String, role: DeviceRole)

    /**
     * 移除设备
     * @param deviceId 设备ID
     */
    suspend fun removeDevice(deviceId: String)

    /**
     * 获取本地设备信息
     * @return 本地设备
     */
    fun getLocalDevice(): Device
}
