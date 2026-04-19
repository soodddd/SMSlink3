package com.smslink.feature.device

/**
 * 设备角色定义
 */
enum class DeviceRole {
    /**
     * 主设备 - 手机端，提供通知、通话等功能
     */
    PRIMARY,

    /**
     * 副设备 - 电脑端，接收通知、控制通话
     */
    SECONDARY,

    /**
     * 未配对 - 初始状态
     */
    UNPAIRED
}

/**
 * 设备连接状态
 */
sealed class DeviceConnectionState {
    object Disconnected : DeviceConnectionState()
    object Connecting : DeviceConnectionState()
    data class Connected(val deviceId: String) : DeviceConnectionState()
    data class Failed(val error: Exception) : DeviceConnectionState()
}

/**
 * 配对状态
 */
sealed class PairingState {
    object Idle : PairingState()
    data class WaitingForCode(val code: String) : PairingState()
    object Pairing : PairingState()
    data class Success(val deviceId: String) : PairingState()
    data class Failed(val error: String) : PairingState()
}
