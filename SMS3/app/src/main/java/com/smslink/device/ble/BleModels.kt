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
    fun isStructurallyValid(): Boolean = runCatching {
        protocolVersion == BleConstants.PROTOCOL_VERSION &&
            deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH &&
            deviceName.isNotBlank() && deviceName.length <= MAX_DEVICE_NAME_LENGTH &&
            version.isNotBlank() && version.length <= MAX_VERSION_LENGTH
    }.getOrDefault(false)

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
                    ?.takeIf(BleDeviceInfo::isStructurallyValid)
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

        private const val MAX_DEVICE_ID_LENGTH = 128
        private const val MAX_DEVICE_NAME_LENGTH = 128
        private const val MAX_VERSION_LENGTH = 32
    }
}

/**
 * BLE 配对请求
 */
data class BlePairingRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val timestamp: Long,
    val tlsCertificate: String? = null,
    val signature: String? = null
) {
    /** Canonical bytes signed by the requester before the GATT write. */
    fun canonicalPayload(): String = listOf(
        "smslink-ble-pair",
        deviceId,
        deviceName,
        publicKey,
        tlsCertificate.orEmpty(),
        timestamp.toString()
    ).joinToString("|")

    fun isStructurallyValid(): Boolean = runCatching {
        deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH &&
            deviceName.isNotBlank() && deviceName.length <= MAX_DEVICE_NAME_LENGTH &&
            publicKey.isNotBlank() && publicKey.length <= MAX_KEY_MATERIAL_LENGTH &&
            timestamp > 0L &&
            (tlsCertificate == null || tlsCertificate.length <= MAX_CERTIFICATE_LENGTH) &&
            (signature == null || signature.length <= MAX_SIGNATURE_LENGTH)
    }.getOrDefault(false)

    fun toJson(): String = Gson().toJson(this)
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): BlePairingRequest? {
            return try {
                Gson().fromJson(json, BlePairingRequest::class.java)
                    ?.takeIf(BlePairingRequest::isStructurallyValid)
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

        private const val MAX_DEVICE_ID_LENGTH = 128
        private const val MAX_DEVICE_NAME_LENGTH = 128
        private const val MAX_KEY_MATERIAL_LENGTH = 8192
        private const val MAX_CERTIFICATE_LENGTH = 64 * 1024
        private const val MAX_SIGNATURE_LENGTH = 4096
    }
}

/**
 * BLE 配对响应
 */
data class BlePairingResponse(
    val success: Boolean,
    val message: String,
    val publicKey: String?,
    val tlsCertificate: String? = null,
    val timestamp: Long,
    val signature: String? = null,
    /** Signed identity that binds the response to the GATT device-info record. */
    val deviceId: String? = null
) {
    fun canonicalPayload(): String = listOf(
        "smslink-ble-pair-response",
        deviceId.orEmpty(),
        success.toString(),
        message,
        publicKey.orEmpty(),
        tlsCertificate.orEmpty(),
        timestamp.toString()
    ).joinToString("|")

    fun isStructurallyValid(): Boolean = runCatching {
        message.length <= MAX_MESSAGE_LENGTH && timestamp > 0L &&
            (publicKey == null || publicKey.length <= MAX_KEY_MATERIAL_LENGTH) &&
            (tlsCertificate == null || tlsCertificate.length <= MAX_CERTIFICATE_LENGTH) &&
            (signature == null || signature.length <= MAX_SIGNATURE_LENGTH) &&
            (deviceId == null || deviceId.length <= MAX_DEVICE_ID_LENGTH)
    }.getOrDefault(false)

    fun toJson(): String = Gson().toJson(this)
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): BlePairingResponse? {
            return try {
                Gson().fromJson(json, BlePairingResponse::class.java)
                    ?.takeIf(BlePairingResponse::isStructurallyValid)
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

        private const val MAX_MESSAGE_LENGTH = 1024
        private const val MAX_KEY_MATERIAL_LENGTH = 8192
        private const val MAX_CERTIFICATE_LENGTH = 64 * 1024
        private const val MAX_SIGNATURE_LENGTH = 4096
        private const val MAX_DEVICE_ID_LENGTH = 128
    }
}
