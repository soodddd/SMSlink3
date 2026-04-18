package com.smslink.device.ble

import com.google.gson.Gson
import com.smslink.core.model.DeviceType

/**
 * BLE 设备信息
 * 通过 BLE GATT 特征传输的设备信息
 */
data class BleDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val version: String,
    val protocolVersion: Int = BleConstants.PROTOCOL_VERSION
) {
    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return Gson().toJson(this)
    }

    /**
     * 转换为字节数组（用于 BLE 传输）
     */
    fun toBytes(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /**
         * 从 JSON 字符串解析
         */
        fun fromJson(json: String): BleDeviceInfo? {
            return try {
                Gson().fromJson(json, BleDeviceInfo::class.java)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 从字节数组解析
         */
        fun fromBytes(bytes: ByteArray): BleDeviceInfo? {
            return try {
                val json = String(bytes, Charsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * BLE 配对请求
 */
data class BlePairingRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val timestamp: Long
) {
    fun toJson(): String = Gson().toJson(this)
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): BlePairingRequest? {
            return try {
                Gson().fromJson(json, BlePairingRequest::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun fromBytes(bytes: ByteArray): BlePairingRequest? {
            return try {
                val json = String(bytes, Charsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * BLE 配对响应
 */
data class BlePairingResponse(
    val success: Boolean,
    val message: String,
    val publicKey: String?,
    val timestamp: Long
) {
    fun toJson(): String = Gson().toJson(this)
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): BlePairingResponse? {
            return try {
                Gson().fromJson(json, BlePairingResponse::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun fromBytes(bytes: ByteArray): BlePairingResponse? {
            return try {
                val json = String(bytes, Charsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}
