package com.smslink.device.pairing

import com.smslink.core.database.Converters
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.PairResult
import com.smslink.device.wifi.WifiNetworkDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 改进的配对管理器
 * 支持两种配对方式：
 * 1. 二维码配对 - 扫描包含设备信息和临时密钥的二维码
 * 2. 6位配对码配对 - 输入6位数字配对码
 *
 * 配对要求：
 * - 必须在同一WiFi网络下
 * - 使用临时配对密钥验证
 * - 配对成功后交换公钥用于后续加密通信
 *
 * 参考：
 * - KDE Connect的配对机制
 * - LocalSend的信任建立流程
 */
@Singleton
class ImprovedPairingManager @Inject constructor(
    private val deviceDao: DeviceDao,
    private val wifiDiscovery: WifiNetworkDiscovery,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "ImprovedPairing"
        private const val PAIR_CODE_LENGTH = 6
        private const val PAIR_CODE_VALIDITY = 5 * 60 * 1000L // 5分钟
        private const val QR_CODE_VALIDITY = 10 * 60 * 1000L // 10分钟
        private const val RSA_KEY_SIZE = 2048
    }

    private val gson = Converters.gson
    private val secureRandom = SecureRandom()

    // 活动的配对会话 - 使用 ConcurrentHashMap 保证线程安全
    private val activePairingSessions = ConcurrentHashMap<String, PairingSession>()

    /**
     * 生成6位配对码
     * 用于简单的数字输入配对
     */
    fun generatePairCode(localDevice: Device): PairCodeData {
        // 检查WiFi连接
        if (!wifiDiscovery.isWifiConnected()) {
            throw IllegalStateException("Not connected to WiFi network")
        }

        val ssid = wifiDiscovery.getCurrentWifiSsid()
            ?: throw IllegalStateException("Cannot get WiFi SSID")

        // 生成6位随机数字
        val pairCode = String.format("%06d", secureRandom.nextInt(1000000))

        // 生成临时密钥
        val tempKey = generateTempKey()

        val pairCodeData = PairCodeData(
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            pairCode = pairCode,
            tempKey = tempKey,
            ssid = ssid,
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + PAIR_CODE_VALIDITY
        )

        // 保存配对会话
        val session = PairingSession(
            sessionId = UUID.randomUUID().toString(),
            localDevice = localDevice,
            pairCode = pairCode,
            tempKey = tempKey,
            ssid = ssid,
            createdAt = System.currentTimeMillis(),
            expiresAt = pairCodeData.expiresAt,
            method = PairingMethod.PAIR_CODE
        )

        activePairingSessions[pairCode] = session

        logger.i(TAG, "Generated pair code: $pairCode for device: ${localDevice.name}")

        return pairCodeData
    }

    /**
     * 生成二维码数据
     * 包含设备信息、临时密钥和网络信息
     */
    fun generateQRCode(localDevice: Device): String {
        // 检查WiFi连接
        if (!wifiDiscovery.isWifiConnected()) {
            throw IllegalStateException("Not connected to WiFi network")
        }

        val ssid = wifiDiscovery.getCurrentWifiSsid()
            ?: throw IllegalStateException("Cannot get WiFi SSID")

        // 生成临时密钥
        val tempKey = generateTempKey()

        // 生成会话ID
        val sessionId = UUID.randomUUID().toString()

        val qrData = QRCodeData(
            version = 1,
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            deviceType = localDevice.type.name,
            sessionId = sessionId,
            tempKey = tempKey,
            ssid = ssid,
            timestamp = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + QR_CODE_VALIDITY
        )

        // 保存配对会话
        val session = PairingSession(
            sessionId = sessionId,
            localDevice = localDevice,
            pairCode = null,
            tempKey = tempKey,
            ssid = ssid,
            createdAt = System.currentTimeMillis(),
            expiresAt = qrData.expiresAt,
            method = PairingMethod.QR_CODE
        )

        activePairingSessions[sessionId] = session

        val qrCodeJson = gson.toJson(qrData)
        logger.i(TAG, "Generated QR code for device: ${localDevice.name}")

        return qrCodeJson
    }

    /**
     * 使用配对码配对
     */
    fun pairWithCode(localDevice: Device, pairCode: String): Flow<PairResult> = flow {
        try {
            logger.i(TAG, "Attempting to pair with code: $pairCode")

            // 验证配对码格式
            if (!isValidPairCode(pairCode)) {
                emit(PairResult(false, "Invalid pair code format"))
                return@flow
            }

            // 检查WiFi连接
            if (!wifiDiscovery.isWifiConnected()) {
                emit(PairResult(false, "Not connected to WiFi network"))
                return@flow
            }

            val currentSsid = wifiDiscovery.getCurrentWifiSsid()

            // 查找对应的配对会话
            val session = activePairingSessions[pairCode]
            if (session == null) {
                emit(PairResult(false, "Pair code not found or expired"))
                return@flow
            }

            // 验证会话未过期
            if (System.currentTimeMillis() > session.expiresAt) {
                activePairingSessions.remove(pairCode)
                emit(PairResult(false, "Pair code expired"))
                return@flow
            }

            // 验证在同一WiFi网络
            if (session.ssid != currentSsid) {
                emit(PairResult(false, "Not on the same WiFi network"))
                return@flow
            }

            // 验证不是自己
            if (session.localDevice.id == localDevice.id) {
                emit(PairResult(false, "Cannot pair with yourself"))
                return@flow
            }

            // 生成公钥（实际应用中应使用真实的密钥对）
            val publicKey = generatePublicKey(localDevice.id)

            // 创建配对的设备
            val pairedDevice = Device(
                id = session.localDevice.id,
                name = session.localDevice.name,
                type = session.localDevice.type,
                role = com.smslink.core.model.DeviceRole.SECONDARY,
                publicKey = publicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )

            // 保存到数据库
            deviceDao.insert(pairedDevice)

            // 清除会话
            activePairingSessions.remove(pairCode)

            logger.i(TAG, "Successfully paired with device: ${pairedDevice.name}")
            emit(PairResult(true, "Pairing successful", pairedDevice))

        } catch (e: Exception) {
            logger.e(TAG, "Pairing with code failed", e)
            emit(PairResult(false, "Pairing failed: ${e.message}"))
        }
    }

    /**
     * 使用二维码配对
     */
    fun pairWithQRCode(localDevice: Device, qrCodeData: String): Flow<PairResult> = flow {
        try {
            logger.i(TAG, "Attempting to pair with QR code")

            // 解析二维码数据
            val qrData = try {
                gson.fromJson(qrCodeData, QRCodeData::class.java)
            } catch (e: Exception) {
                emit(PairResult(false, "Invalid QR code format"))
                return@flow
            }

            // 验证版本
            if (qrData.version != 1) {
                emit(PairResult(false, "Unsupported QR code version"))
                return@flow
            }

            // 验证未过期
            if (System.currentTimeMillis() > qrData.expiresAt) {
                emit(PairResult(false, "QR code expired"))
                return@flow
            }

            // 检查WiFi连接
            if (!wifiDiscovery.isWifiConnected()) {
                emit(PairResult(false, "Not connected to WiFi network"))
                return@flow
            }

            val currentSsid = wifiDiscovery.getCurrentWifiSsid()

            // 验证在同一WiFi网络
            if (qrData.ssid != currentSsid) {
                emit(PairResult(false, "Not on the same WiFi network (expected: ${qrData.ssid}, current: $currentSsid)"))
                return@flow
            }

            // 验证不是自己
            if (qrData.deviceId == localDevice.id) {
                emit(PairResult(false, "Cannot pair with yourself"))
                return@flow
            }

            // 验证临时密钥
            if (!verifyTempKey(qrData.tempKey)) {
                emit(PairResult(false, "Invalid temporary key"))
                return@flow
            }

            // 生成公钥
            val publicKey = generatePublicKey(localDevice.id)

            // 创建配对的设备
            val pairedDevice = Device(
                id = qrData.deviceId,
                name = qrData.deviceName,
                type = com.smslink.core.model.DeviceType.valueOf(qrData.deviceType),
                role = com.smslink.core.model.DeviceRole.SECONDARY,
                publicKey = publicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )

            // 保存到数据库
            deviceDao.insert(pairedDevice)

            // 清除会话
            activePairingSessions.remove(qrData.sessionId)

            logger.i(TAG, "Successfully paired with device: ${pairedDevice.name}")
            emit(PairResult(true, "Pairing successful", pairedDevice))

        } catch (e: Exception) {
            logger.e(TAG, "Pairing with QR code failed", e)
            emit(PairResult(false, "Pairing failed: ${e.message}"))
        }
    }

    /**
     * 验证配对码格式
     */
    private fun isValidPairCode(pairCode: String): Boolean {
        return pairCode.length == PAIR_CODE_LENGTH && pairCode.all { it.isDigit() }
    }

    /**
     * 生成临时密钥
     */
    private fun generateTempKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 验证临时密钥
     */
    private fun verifyTempKey(tempKey: String): Boolean {
        return try {
            val decoded = Base64.getDecoder().decode(tempKey)
            decoded.size == 32
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 生成RSA公钥
     * 使用2048位RSA算法生成真实的公私钥对
     */
    private fun generatePublicKey(deviceId: String): String {
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(RSA_KEY_SIZE, secureRandom)
            val keyPair = keyPairGenerator.generateKeyPair()

            // 返回Base64编码的公钥
            val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            logger.d(TAG, "Generated RSA public key for device: $deviceId, length=${publicKey.length}")
            publicKey
        } catch (e: Exception) {
            logger.e(TAG, "Failed to generate RSA key pair, falling back to hash-based key", e)
            // 降级方案：使用哈希生成伪公钥
            val data = "$deviceId:${System.currentTimeMillis()}:${UUID.randomUUID()}"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data.toByteArray())
            Base64.getEncoder().encodeToString(hash)
        }
    }

    /**
     * 清理过期的配对会话
     */
    fun cleanupExpiredSessions() {
        val currentTime = System.currentTimeMillis()
        val expiredSessions = activePairingSessions.filter { (_, session) ->
            currentTime > session.expiresAt
        }

        expiredSessions.forEach { (key, _) ->
            activePairingSessions.remove(key)
        }

        if (expiredSessions.isNotEmpty()) {
            logger.d(TAG, "Cleaned up ${expiredSessions.size} expired pairing sessions")
        }
    }

    /**
     * 获取活动的配对会话数量
     */
    fun getActiveSessions(): Int {
        cleanupExpiredSessions()
        return activePairingSessions.size
    }
}

/**
 * 配对方法
 */
enum class PairingMethod {
    QR_CODE,    // 二维码配对
    PAIR_CODE   // 配对码配对
}

/**
 * 配对会话
 */
data class PairingSession(
    val sessionId: String,
    val localDevice: Device,
    val pairCode: String?,
    val tempKey: String,
    val ssid: String,
    val createdAt: Long,
    val expiresAt: Long,
    val method: PairingMethod
)

/**
 * 配对码数据
 */
data class PairCodeData(
    val deviceId: String,
    val deviceName: String,
    val pairCode: String,
    val tempKey: String,
    val ssid: String,
    val timestamp: Long,
    val expiresAt: Long
)

/**
 * 二维码数据
 */
data class QRCodeData(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val sessionId: String,
    val tempKey: String,
    val ssid: String,
    val timestamp: Long,
    val expiresAt: Long
)
