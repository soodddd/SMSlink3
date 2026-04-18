package com.smslink.network.connection

/**
 * 链路类型
 * 定义不同的网络连接链路及其优先级
 */
enum class LinkType(val priority: Int, val description: String) {
    /**
     * L1: WiFi 局域网直连
     * 最高优先级，速度快，延迟低
     */
    WIFI_LAN(1, "WiFi LAN"),

    /**
     * L2: WiFi 热点
     * 中等优先级，适用于不在同一局域网的场景
     */
    WIFI_HOTSPOT(2, "WiFi Hotspot"),

    /**
     * L3: 蓝牙 RFCOMM
     * 最低优先级，作为最终兜底方案
     */
    BLUETOOTH(3, "Bluetooth RFCOMM");

    /**
     * 是否比另一个链路类型优先级更高
     */
    fun hasHigherPriorityThan(other: LinkType): Boolean {
        return this.priority < other.priority
    }

    companion object {
        /**
         * 获取所有链路类型，按优先级排序
         */
        fun getAllByPriority(): List<LinkType> {
            return values().sortedBy { it.priority }
        }
    }
}
