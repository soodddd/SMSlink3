package com.smslink.feature.device

import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import kotlin.random.Random

/**
 * 配对管理器
 * 负责设备配对流程：验证码生成、配对请求、配对确认
 */
class PairingManager {

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var currentCode: String? = null
    private var pairingTimeout: Long = 0

    /**
     * 生成 6 位配对验证码
     */
    fun generatePairingCode(): String {
        val code = String.format("%06d", Random.nextInt(0, 1000000))
        currentCode = code
        pairingTimeout = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
        _pairingState.value = PairingState.WaitingForCode(code)
        return code
    }

    /**
     * 验证配对码
     */
    fun verifyPairingCode(code: String): Boolean {
        if (System.currentTimeMillis() > pairingTimeout) {
            _pairingState.value = PairingState.Failed("配对码已过期")
            return false
        }

        if (code != currentCode) {
            _pairingState.value = PairingState.Failed("配对码不正确")
            return false
        }

        return true
    }

    /**
     * 创建配对请求消息
     */
    fun createPairingRequest(deviceId: String, deviceName: String, code: String): Message {
        val payload = buildString {
            append("deviceId:$deviceId\n")
            append("deviceName:$deviceName\n")
            append("code:$code\n")
            append("timestamp:${System.currentTimeMillis()}")
        }.toByteArray()

        return Message(
            type = MessageType.DEVICE_PAIR_REQUEST,
            flags = Message.FLAG_REQUIRES_ACK.toByte(),
            messageId = System.currentTimeMillis(),
            payload = payload
        )
    }

    /**
     * 解析配对请求
     */
    fun parsePairingRequest(message: Message): PairingRequest? {
        if (message.type != MessageType.DEVICE_PAIR_REQUEST) return null

        val data = String(message.payload)
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0] to parts[1]
                } else {
                    null
                }
            }
            .toMap()

        val timestamp = data["timestamp"]?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - timestamp > PAIRING_TIMEOUT_MS) return null

        return PairingRequest(
            deviceId = data["deviceId"] ?: return null,
            deviceName = data["deviceName"] ?: return null,
            code = data["code"] ?: return null,
            timestamp = timestamp
        )
    }

    /**
     * 创建配对响应消息
     */
    fun createPairingResponse(accepted: Boolean, deviceId: String): Message {
        val payload = buildString {
            append("accepted:$accepted\n")
            append("deviceId:$deviceId\n")
            append("timestamp:${System.currentTimeMillis()}")
        }.toByteArray()

        return Message(
            type = MessageType.DEVICE_PAIR_RESPONSE,
            flags = 0,
            messageId = System.currentTimeMillis(),
            payload = payload
        )
    }

    /**
     * 解析配对响应
     */
    fun parsePairingResponse(message: Message): PairingResponse? {
        if (message.type != MessageType.DEVICE_PAIR_RESPONSE) return null

        val payload = String(message.payload)
        val lines = payload.split("\n")
        val data = lines.associate {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }

        val accepted = data["accepted"]?.toBoolean() ?: return null
        val deviceId = data["deviceId"] ?: return null

        return PairingResponse(accepted, deviceId)
    }

    /**
     * 开始配对流程
     */
    fun startPairing() {
        _pairingState.value = PairingState.Pairing
    }

    /**
     * 配对成功
     */
    fun pairingSuccess(deviceId: String) {
        _pairingState.value = PairingState.Success(deviceId)
        currentCode = null
    }

    /**
     * 配对失败
     */
    fun pairingFailed(error: String) {
        _pairingState.value = PairingState.Failed(error)
        currentCode = null
    }

    /**
     * 重置配对状态
     */
    fun reset() {
        _pairingState.value = PairingState.Idle
        currentCode = null
        pairingTimeout = 0
    }

    companion object {
        private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L // 5 分钟
    }
}

/**
 * 配对请求数据
 */
data class PairingRequest(
    val deviceId: String,
    val deviceName: String,
    val code: String,
    val timestamp: Long
)

/**
 * 配对响应数据
 */
data class PairingResponse(
    val accepted: Boolean,
    val deviceId: String
)
