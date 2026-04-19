package com.smslink.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.smslink.core.model.DeviceCapability
import com.smslink.core.model.DeviceInfo
import com.smslink.core.model.DeviceType

/**
 * 设备数据库实体
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val ipAddress: String?,
    val port: Int,
    val capabilities: String, // 逗号分隔的能力列表
    val isPaired: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 转换为 DeviceInfo
 */
fun DeviceEntity.toDeviceInfo(): DeviceInfo {
    return DeviceInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceType = DeviceType.valueOf(deviceType),
        capabilities = capabilities.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { DeviceCapability.valueOf(it) }.getOrNull() }
            .toSet(),
        ipAddress = ipAddress,
        port = port,
        timestamp = lastSeen
    )
}

/**
 * 从 DeviceInfo 转换
 */
fun DeviceInfo.toEntity(isPaired: Boolean = false): DeviceEntity {
    return DeviceEntity(
        deviceId = deviceId,
        deviceName = deviceName,
        deviceType = deviceType.name,
        ipAddress = ipAddress,
        port = port,
        capabilities = capabilities.joinToString(",") { it.name },
        isPaired = isPaired,
        lastSeen = timestamp
    )
}
