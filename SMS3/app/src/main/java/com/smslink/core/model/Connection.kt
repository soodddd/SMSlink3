package com.smslink.core.model

/**
 * 连接状态
 */
data class Connection(
    val deviceId: String,
    val type: ConnectionType,
    val state: ConnectionState,
    val quality: ConnectionQuality,
    val lastActivity: Long
)

/**
 * 连接类型
 */
enum class ConnectionType {
    BLUETOOTH,  // 蓝牙连接
    WIFI,       // WiFi 连接
    HOTSPOT     // 热点连接
}

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED,   // 未连接
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    RECONNECTING,   // 重连中
    FAILED          // 连接失败
}

/**
 * 连接质量
 */
enum class ConnectionQuality {
    EXCELLENT,  // 优秀
    GOOD,       // 良好
    FAIR,       // 一般
    POOR        // 差
}
