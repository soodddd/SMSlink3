package com.smslink.network.security

import com.google.gson.Gson
import com.smslink.security.DeviceIdentityStore
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * Application authentication carried inside the TLS stream.
 *
 * TLS protects the bytes in transit.  These signed messages pin the logical
 * device identity, prevent an IP address from impersonating a paired device,
 * and also make the first post-QR connection capable of completing pairing.
 */
object AuthProtocol {
    const val PROTOCOL_VERSION = 1
    const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
    const val MAX_AUTH_FRAME_BYTES = 16 * 1024

    private const val HELLO_PREFIX = "smslink-auth-hello"
    private const val RESPONSE_PREFIX = "smslink-auth-response"
    private val random = SecureRandom()
    private val gson = Gson()

    data class Hello(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val sourceDevice: String,
        val sourceName: String = "Android device",
        val targetDevice: String,
        val nonce: String,
        val publicKey: String,
        val pairingToken: String? = null,
        val tlsCertificate: String? = null,
        val timestamp: Long,
        val signature: String
    )

    data class Response(
        val protocolVersion: Int = PROTOCOL_VERSION,
        val sourceDevice: String,
        val targetDevice: String,
        val nonce: String,
        val accepted: Boolean,
        val publicKey: String,
        val message: String? = null,
        val timestamp: Long,
        val signature: String
    )

    fun createHello(
        identity: DeviceIdentityStore,
        targetDevice: String,
        sourceName: String = "Android device",
        pairingToken: String?,
        tlsCertificate: String? = null,
        now: Long = System.currentTimeMillis()
    ): Hello {
        val source = identity.deviceId()
        val nonce = ByteArray(32).also(random::nextBytes).toBase64()
        val unsigned = helloCanonical(
            protocolVersion = PROTOCOL_VERSION,
            sourceDevice = source,
            sourceName = sourceName,
            targetDevice = targetDevice,
            nonce = nonce,
            publicKey = identity.publicKeyBase64(),
            pairingToken = pairingToken,
            tlsCertificate = tlsCertificate,
            timestamp = now
        )
        return Hello(
            sourceDevice = source,
            sourceName = sourceName,
            targetDevice = targetDevice,
            nonce = nonce,
            publicKey = identity.publicKeyBase64(),
            pairingToken = pairingToken,
            tlsCertificate = tlsCertificate,
            timestamp = now,
            signature = identity.sign(unsigned)
        )
    }

    fun createResponse(
        identity: DeviceIdentityStore,
        targetDevice: String,
        nonce: String,
        accepted: Boolean,
        message: String? = null,
        now: Long = System.currentTimeMillis()
    ): Response {
        val source = identity.deviceId()
        val publicKey = identity.publicKeyBase64()
        val unsigned = responseCanonical(
            protocolVersion = PROTOCOL_VERSION,
            sourceDevice = source,
            targetDevice = targetDevice,
            nonce = nonce,
            accepted = accepted,
            publicKey = publicKey,
            message = message,
            timestamp = now
        )
        return Response(
            sourceDevice = source,
            targetDevice = targetDevice,
            nonce = nonce,
            accepted = accepted,
            publicKey = publicKey,
            message = message,
            timestamp = now,
            signature = identity.sign(unsigned)
        )
    }

    fun encodeHello(hello: Hello): ByteArray = gson.toJson(hello).toByteArray(StandardCharsets.UTF_8)

    fun encodeResponse(response: Response): ByteArray = gson.toJson(response).toByteArray(StandardCharsets.UTF_8)

    fun decodeHello(bytes: ByteArray): Hello? = runCatching {
        require(bytes.isNotEmpty())
        require(bytes.size <= MAX_AUTH_FRAME_BYTES)
        gson.fromJson(String(bytes, StandardCharsets.UTF_8), Hello::class.java)
    }.getOrNull()

    fun decodeResponse(bytes: ByteArray): Response? = runCatching {
        require(bytes.isNotEmpty())
        require(bytes.size <= MAX_AUTH_FRAME_BYTES)
        gson.fromJson(String(bytes, StandardCharsets.UTF_8), Response::class.java)
    }.getOrNull()

    fun verifyHello(hello: Hello, now: Long = System.currentTimeMillis()): Boolean {
        if (!isValidHello(hello, now)) {
            return false
        }
        return DeviceIdentityStore.forTests().verify(
            hello.publicKey,
            helloCanonical(
                hello.protocolVersion,
                hello.sourceDevice,
                hello.sourceName,
                hello.targetDevice,
                hello.nonce,
                hello.publicKey,
                hello.pairingToken,
                hello.tlsCertificate,
                hello.timestamp
            ),
            hello.signature
        )
    }

    fun verifyHello(hello: Hello, identity: DeviceIdentityStore, now: Long = System.currentTimeMillis()): Boolean {
        if (!isValidHello(hello, now)) {
            return false
        }
        return identity.verify(
            hello.publicKey,
            helloCanonical(
                hello.protocolVersion,
                hello.sourceDevice,
                hello.sourceName,
                hello.targetDevice,
                hello.nonce,
                hello.publicKey,
                hello.pairingToken,
                hello.tlsCertificate,
                hello.timestamp
            ),
            hello.signature
        )
    }

    fun verifyResponse(
        response: Response,
        expectedSource: String,
        expectedTarget: String,
        expectedNonce: String,
        expectedPublicKey: String,
        identity: DeviceIdentityStore,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isValidResponse(response, now) ||
            response.sourceDevice != expectedSource ||
            response.targetDevice != expectedTarget ||
            response.nonce != expectedNonce ||
            response.publicKey != expectedPublicKey
        ) {
            return false
        }
        return identity.verify(
            response.publicKey,
            responseCanonical(
                response.protocolVersion,
                response.sourceDevice,
                response.targetDevice,
                response.nonce,
                response.accepted,
                response.publicKey,
                response.message,
                response.timestamp
            ),
            response.signature
        )
    }

    fun helloCanonical(hello: Hello): String = helloCanonical(
        hello.protocolVersion,
        hello.sourceDevice,
        hello.sourceName,
        hello.targetDevice,
        hello.nonce,
        hello.publicKey,
        hello.pairingToken,
        hello.tlsCertificate,
        hello.timestamp
    )

    fun responseCanonical(response: Response): String = responseCanonical(
        response.protocolVersion,
        response.sourceDevice,
        response.targetDevice,
        response.nonce,
        response.accepted,
        response.publicKey,
        response.message,
        response.timestamp
    )

    private fun helloCanonical(
        protocolVersion: Int,
        sourceDevice: String,
        sourceName: String,
        targetDevice: String,
        nonce: String,
        publicKey: String,
        pairingToken: String?,
        tlsCertificate: String?,
        timestamp: Long
    ): String = listOf(
        HELLO_PREFIX,
        protocolVersion.toString(),
        sourceDevice,
        sourceName,
        targetDevice,
        nonce,
        publicKey,
        pairingToken.orEmpty(),
        tlsCertificate.orEmpty(),
        timestamp.toString()
    ).joinToString("|")

    private fun responseCanonical(
        protocolVersion: Int,
        sourceDevice: String,
        targetDevice: String,
        nonce: String,
        accepted: Boolean,
        publicKey: String,
        message: String?,
        timestamp: Long
    ): String = listOf(
        RESPONSE_PREFIX,
        protocolVersion.toString(),
        sourceDevice,
        targetDevice,
        nonce,
        accepted.toString(),
        publicKey,
        message.orEmpty(),
        timestamp.toString()
    ).joinToString("|")

    private fun isValidCommon(
        protocolVersion: Int,
        sourceDevice: String,
        targetDevice: String,
        nonce: String,
        timestamp: Long,
        now: Long
    ): Boolean {
        return protocolVersion == PROTOCOL_VERSION &&
            sourceDevice.isNotBlank() && sourceDevice.length <= 128 &&
            targetDevice.isNotBlank() && targetDevice.length <= 128 &&
            decodeBase64(nonce)?.size == 32 &&
            isTimestampAcceptable(timestamp, now)
    }

    private fun isValidHello(hello: Hello, now: Long): Boolean {
        return isValidCommon(
            hello.protocolVersion,
            hello.sourceDevice,
            hello.targetDevice,
            hello.nonce,
            hello.timestamp,
            now
        ) && hello.sourceName.isNotBlank() &&
            hello.sourceName.length <= MAX_TEXT_LENGTH &&
            hasKeyMaterial(hello.publicKey) &&
            hasSignature(hello.signature) &&
            (hello.pairingToken == null || decodeBase64(hello.pairingToken)?.size == 32) &&
            (hello.tlsCertificate == null || isBoundedBase64(hello.tlsCertificate))
    }

    private fun isValidResponse(response: Response, now: Long): Boolean {
        return isValidCommon(
            response.protocolVersion,
            response.sourceDevice,
            response.targetDevice,
            response.nonce,
            response.timestamp,
            now
        ) && hasKeyMaterial(response.publicKey) &&
            hasSignature(response.signature) &&
            (response.message == null || response.message.length <= MAX_TEXT_LENGTH)
    }

    private fun hasKeyMaterial(value: String): Boolean {
        val decoded = decodeBase64(value) ?: return false
        return decoded.size in MIN_KEY_BYTES..MAX_KEY_BYTES
    }

    private fun hasSignature(value: String): Boolean {
        val decoded = decodeBase64(value) ?: return false
        return decoded.size in 1..MAX_SIGNATURE_BYTES
    }

    private fun isBoundedBase64(value: String): Boolean {
        val decoded = decodeBase64(value) ?: return false
        return decoded.isNotEmpty() && decoded.size <= MAX_CERTIFICATE_BYTES
    }

    private fun isTimestampAcceptable(timestamp: Long, now: Long): Boolean =
        timestamp >= now - MAX_CLOCK_SKEW_MS && timestamp <= now + MAX_CLOCK_SKEW_MS

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()

    private const val MAX_TEXT_LENGTH = 128
    private const val MIN_KEY_BYTES = 64
    private const val MAX_KEY_BYTES = 4096
    private const val MAX_SIGNATURE_BYTES = 1024
    private const val MAX_CERTIFICATE_BYTES = 32 * 1024
}
