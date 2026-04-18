package com.smslink.device

/**
 * 设备发现模式
 */
enum class DiscoveryMode {
    BLE_ONLY,      // 仅使用 BLE 发现
    UDP_ONLY,      // 仅使用 UDP 广播发现
    BLE_FIRST,     // BLE 优先，UDP 作为降级方案
    BOTH           // 同时使用 BLE 和 UDP
}

/**
 * 发现设置
 */
data class DiscoverySettings(
    val mode: DiscoveryMode = DiscoveryMode.BLE_FIRST,
    val bleScanInterval: Long = 5000L,      // BLE 扫描间隔（毫秒）
    val udpBroadcastInterval: Long = 3000L, // UDP 广播间隔（毫秒）
    val deviceTimeout: Long = 15000L,       // 设备超时时间（毫秒）
    val autoRetry: Boolean = true,          // 自动重试
    val retryDelay: Long = 2000L            // 重试延迟（毫秒）
) {
    companion object {
        /**
         * 默认设置
         */
        val DEFAULT = DiscoverySettings()

        /**
         * 仅 BLE 模式
         */
        val BLE_ONLY = DiscoverySettings(mode = DiscoveryMode.BLE_ONLY)

        /**
         * 仅 UDP 模式
         */
        val UDP_ONLY = DiscoverySettings(mode = DiscoveryMode.UDP_ONLY)

        /**
         * 双模式
         */
        val BOTH = DiscoverySettings(mode = DiscoveryMode.BOTH)
    }

    /**
     * 是否启用 BLE
     */
    fun isBleEnabled(): Boolean {
        return mode == DiscoveryMode.BLE_ONLY ||
               mode == DiscoveryMode.BLE_FIRST ||
               mode == DiscoveryMode.BOTH
    }

    /**
     * 是否启用 UDP
     */
    fun isUdpEnabled(): Boolean {
        return mode == DiscoveryMode.UDP_ONLY ||
               mode == DiscoveryMode.BLE_FIRST ||
               mode == DiscoveryMode.BOTH
    }

    /**
     * 是否 BLE 优先
     */
    fun isBlePriority(): Boolean {
        return mode == DiscoveryMode.BLE_FIRST || mode == DiscoveryMode.BLE_ONLY
    }
}
