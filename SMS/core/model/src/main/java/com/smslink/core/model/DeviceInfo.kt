package com.smslink.core.model

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val capabilities: Set<DeviceCapability>,
    val ipAddress: String? = null,
    val port: Int = 8888,
    val timestamp: Long = System.currentTimeMillis()
)

enum class DeviceType {
    PHONE,
    TABLET
}

enum class DeviceCapability {
    NOTIFICATION,
    CALL,
    TRANSFER
}
