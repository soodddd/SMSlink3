package com.smslink.device.ble

import java.util.*

/**
 * BLE 常量定义
 * 定义 SMS-link 专用的 BLE Service 和 Characteristic UUID
 */
object BleConstants {
    // 主服务 UUID - SMS-link 设备识别服务
    val SMSLINK_SERVICE_UUID: UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")

    // 设备信息特征 UUID - 用于读取设备基本信息
    val DEVICE_INFO_CHARACTERISTIC: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")

    // 配对特征 UUID - 用于配对请求和响应
    val PAIRING_CHARACTERISTIC: UUID = UUID.fromString("00001236-0000-1000-8000-00805f9b34fb")

    // 控制特征 UUID - 用于控制信令
    val CONTROL_CHARACTERISTIC: UUID = UUID.fromString("00001237-0000-1000-8000-00805f9b34fb")

    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // BLE 扫描设置
    const val SCAN_PERIOD = 10000L // 扫描周期 10秒
    const val SCAN_INTERVAL = 5000L // 扫描间隔 5秒

    // 设备超时设置
    const val DEVICE_TIMEOUT = 15000L // 15秒未发现视为离线

    // GATT 连接超时
    const val GATT_CONNECTION_TIMEOUT = 10000L // 10秒连接超时

    // 协议版本
    const val PROTOCOL_VERSION = 1
}
