package com.smslink.device

import com.google.gson.Gson
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.core.model.PairResult
import com.smslink.device.ble.BleGattClient
import com.smslink.device.ble.BleGattServer
import com.smslink.device.ble.BlePairingRequest
import com.smslink.device.ble.BlePairingResponse
import com.smslink.device.pairing.PairingTokenStore
import com.smslink.network.encryption.CertificateCodec
import com.smslink.network.encryption.IEncryption
import com.smslink.security.DeviceIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairing is capability based. A QR issuer signs a short-lived random token;
 * the scanner verifies it and pins the issuer key. The scanner presents that
 * token in the first signed application handshake, allowing the issuer to
 * learn and pin the scanner key. BLE requests still require user acceptance.
 */
@Singleton
class DevicePairingImpl @Inject constructor(
    private val deviceDao: DeviceDao,
    private val bleGattClient: BleGattClient?,
    private val logger: ILogger,
    private val identityStore: DeviceIdentityStore,
    private val tokenStore: PairingTokenStore,
    private val bleGattServer: BleGattServer?,
    private val encryption: IEncryption
) {
    /** Compatibility constructor retained for the original JVM test suite. */
    constructor(deviceDao: DeviceDao, logger: ILogger) : this(
        deviceDao = deviceDao,
        bleGattClient = null,
        logger = logger,
        identityStore = DeviceIdentityStore.forTests(),
        tokenStore = PairingTokenStore.forTests(),
        bleGattServer = null,
        encryption = LegacyPairingEncryption
    )

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val pendingPairRequests = ConcurrentHashMap<String, PairRequest>()
    private val pendingBleDevices = ConcurrentHashMap<String, android.bluetooth.BluetoothDevice>()

    fun pairDevice(
        device: Device,
        discoveredDevice: DiscoveredDevice? = null,
        qrCode: String? = null
    ): Flow<PairResult> = flow {
        try {
            logger.i(TAG, "Starting pairing with device: ${device.name}")

            if (qrCode != null) {
                emit(pairViaQrCode(device, discoveredDevice, qrCode))
                return@flow
            }

            if (discoveredDevice?.bleDevice != null && bleGattClient != null) {
                emit(pairViaBle(device, discoveredDevice))
                return@flow
            }

            emit(PairResult(false, "No pairing method available"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Pairing failed", e)
            emit(PairResult(false, "Pairing failed: ${e.message}"))
        }
    }

    /** Compatibility overload used by older callers. */
    @Deprecated("Use pairDevice(device, discoveredDevice, qrCode)")
    fun pairDevice(device: Device, qrCode: String): Flow<PairResult> =
        pairDevice(device, null, qrCode)

    private suspend fun pairViaBle(device: Device, discoveredDevice: DiscoveredDevice): PairResult {
        val client = bleGattClient ?: return PairResult(false, "BLE pairing unavailable")
        val bleDevice = discoveredDevice.bleDevice
            ?: return PairResult(false, "BLE device unavailable")
        var response: BlePairingResponse? = null

        return try {
            client.connect(bleDevice) { response = it }
            val connected = withTimeoutOrNull(BLE_CONNECT_RETRIES * BLE_POLL_INTERVAL_MS) {
                while (client.connectionState.value !is com.smslink.device.ble.ConnectionState.Connected) {
                    delay(BLE_POLL_INTERVAL_MS)
                }
                true
            } == true
            if (!connected) {
                return PairResult(false, "Failed to connect to BLE device")
            }

            val info = client.readDeviceInfo() ?: return PairResult(false, "Failed to read device info")
            if (info.deviceId.isBlank() || info.deviceId == identityStore.deviceId()) {
                return PairResult(false, "Invalid remote BLE identity")
            }
            val request = BlePairingRequest(
                deviceId = identityStore.deviceId(),
                deviceName = android.os.Build.MODEL.orEmpty().ifBlank { "Android device" },
                publicKey = identityStore.publicKeyBase64(),
                timestamp = System.currentTimeMillis(),
                tlsCertificate = localTlsCertificate(),
                signature = ""
            ).let { unsigned -> unsigned.copy(signature = identityStore.sign(unsigned.canonicalPayload())) }
            if (!client.sendPairingRequest(request)) {
                return PairResult(false, "Failed to send pairing request")
            }

            val responseReceived = withTimeoutOrNull(BLE_RESPONSE_RETRIES * BLE_POLL_INTERVAL_MS) {
                while (response == null) delay(BLE_POLL_INTERVAL_MS)
                response
            }
            val pairingResponse = responseReceived ?: return PairResult(false, "Pairing response timeout")
            if (!pairingResponse.success) return PairResult(false, pairingResponse.message)
            if (pairingResponse.deviceId != info.deviceId) {
                return PairResult(false, "BLE response identity mismatch")
            }
            val responseKey = pairingResponse.publicKey
            val responseSignature = pairingResponse.signature
            if (responseKey.isNullOrBlank() || responseSignature.isNullOrBlank() ||
                !identityStore.verify(
                    responseKey,
                    pairingResponse.canonicalPayload(),
                    responseSignature
                )
            ) {
                return PairResult(false, "Invalid BLE pairing response signature")
            }

            val remoteCertificate = decodeAndValidateCertificate(pairingResponse.tlsCertificate)
                ?: return PairResult(false, "BLE pairing response has no valid TLS certificate")

            val existing = deviceDao.getById(info.deviceId)
            if (existing?.isPaired == true &&
                !existing.publicKey.isNullOrBlank() && existing.publicKey != responseKey
            ) {
                return PairResult(false, "Remote public key mismatch")
            }
            val existingCertificate = encryption.getDeviceCertificate(info.deviceId)
            if (existingCertificate != null &&
                !existingCertificate.encoded.contentEquals(remoteCertificate.encoded)
            ) {
                return PairResult(false, "Remote TLS certificate mismatch")
            }

            val pairedDevice = device.copy(
                id = info.deviceId,
                name = info.deviceName.ifBlank { device.name },
                isPaired = true,
                publicKey = responseKey,
                bluetoothAddress = bleDevice.address,
                lastSeen = System.currentTimeMillis()
            )
            if (pairedDevice.publicKey.isNullOrBlank()) {
                return PairResult(false, "Remote device did not provide a public key")
            }
            encryption.saveDeviceCertificate(info.deviceId, remoteCertificate)
            deviceDao.insert(pairedDevice)
            logger.i(TAG, "Successfully paired with device via BLE: ${device.name}")
            PairResult(true, "Pairing successful", pairedDevice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "BLE pairing failed", e)
            PairResult(false, "BLE pairing failed: ${e.message}")
        } finally {
            client.disconnect()
        }
    }

    private suspend fun pairViaQrCode(
        device: Device,
        discoveredDevice: DiscoveredDevice?,
        qrCode: String
    ): PairResult {
        val pairData = parsePairQRCode(qrCode) ?: return PairResult(false, "Invalid QR code")
        if (pairData.deviceId == identityStore.deviceId()) {
            return PairResult(false, "Cannot pair the local device with itself")
        }

        // BLE advertisements may only expose the MAC address. In that case
        // verify the QR identity over the actual GATT link before replacing
        // the temporary discovery id with the signed device id.
        val requiresBleIdentityCheck = pairData.deviceId != device.id
        if (requiresBleIdentityCheck && discoveredDevice?.bleDevice == null) {
            logger.d(TAG, "QR pairing id mismatch: expected=${device.id}, actual=${pairData.deviceId}")
            return PairResult(false, "Device ID mismatch")
        }

        if (!isValidQrData(pairData)) return PairResult(false, "Invalid pair key")
        if (pairData.expiresAt <= System.currentTimeMillis()) return PairResult(false, "QR code expired")

        val qrPublicKey = pairData.publicKey
        val signature = pairData.signature
        if (qrPublicKey.isNullOrBlank() || signature.isNullOrBlank() ||
            !identityStore.verify(qrPublicKey, pairData.canonicalPayload(), signature)
        ) {
            return PairResult(false, "Invalid QR signature")
        }

        val remoteCertificate = decodeAndValidateCertificate(pairData.tlsCertificate)
            ?: return PairResult(false, "QR code has no valid TLS certificate")

        val existing = deviceDao.getById(pairData.deviceId)
        if (existing?.isPaired == true &&
            !existing.publicKey.isNullOrBlank() && existing.publicKey != qrPublicKey
        ) {
            return PairResult(false, "Remote public key mismatch")
        }
        val existingCertificate = encryption.getDeviceCertificate(pairData.deviceId)
        if (existingCertificate != null &&
            !existingCertificate.encoded.contentEquals(remoteCertificate.encoded)
        ) {
            return PairResult(false, "Remote TLS certificate mismatch")
        }

        if (requiresBleIdentityCheck) {
            val client = bleGattClient ?: return PairResult(false, "BLE identity verification unavailable")
            val bleDevice = discoveredDevice?.bleDevice
                ?: return PairResult(false, "BLE device unavailable")
            try {
                client.connect(bleDevice) { }
                val connected = withTimeoutOrNull(BLE_CONNECT_RETRIES * BLE_POLL_INTERVAL_MS) {
                    while (client.connectionState.value !is com.smslink.device.ble.ConnectionState.Connected) {
                        delay(BLE_POLL_INTERVAL_MS)
                    }
                    true
                } == true
                if (!connected) return PairResult(false, "Failed to verify BLE device")

                val info = client.readDeviceInfo()
                if (info?.deviceId != pairData.deviceId) {
                    logger.w(
                        TAG,
                        "BLE identity mismatch: advertised=${info?.deviceId}, qr=${pairData.deviceId}"
                    )
                    return PairResult(false, "BLE device identity mismatch")
                }
            } finally {
                client.disconnect()
            }
        }

        // Never let a broadcast replace a key already pinned by the user.
        if (!device.publicKey.isNullOrBlank() && device.publicKey != qrPublicKey) {
            return PairResult(false, "Remote public key mismatch")
        }

        val pairedDevice = device.copy(
            id = pairData.deviceId,
            name = pairData.deviceName?.ifBlank { device.name } ?: device.name,
            isPaired = true,
            publicKey = qrPublicKey,
            ipAddress = discoveredDevice?.ipAddress?.ifBlank { null } ?: device.ipAddress,
            port = discoveredDevice?.device?.port ?: device.port,
            bluetoothAddress = discoveredDevice?.bleDevice?.address ?: device.bluetoothAddress,
            lastSeen = System.currentTimeMillis()
        )
        encryption.saveDeviceCertificate(pairData.deviceId, remoteCertificate)
        deviceDao.insert(pairedDevice)
        // The BLE-only discovery row may use the MAC address as a temporary
        // id. The durable peer id is the signed id from the QR payload, and
        // that is the id used by the authenticated TCP/RFCOMM handshake.
        tokenStore.savePeer(pairData.deviceId, pairData.pairKey, pairData.expiresAt)

        logger.i(TAG, "Successfully paired with device via QR code: ${device.name}")
        return PairResult(true, "Pairing successful", pairedDevice)
    }

    fun receivePairRequest(
        deviceId: String,
        deviceName: String,
        publicKey: String? = null,
        bluetoothAddress: String? = null,
        tlsCertificate: String? = null
    ): String {
        val requestId = UUID.randomUUID().toString()
        pendingPairRequests[requestId] = PairRequest(
            requestId = requestId,
            deviceId = deviceId,
            deviceName = deviceName,
            timestamp = System.currentTimeMillis(),
            publicKey = publicKey,
            tlsCertificate = tlsCertificate,
            bluetoothAddress = bluetoothAddress
        )
        logger.i(TAG, "Received pair request from: $deviceName")
        return requestId
    }

    fun receiveBlePairRequest(request: BlePairingRequest, bluetoothDevice: android.bluetooth.BluetoothDevice): String {
        if (request.deviceId == identityStore.deviceId() || !isValidBlePairingRequest(request)) {
            logger.w(TAG, "Rejected unsigned or invalid BLE pairing request from ${bluetoothDevice.address}")
            return ""
        }
        val requestId = receivePairRequest(
            deviceId = request.deviceId,
            deviceName = request.deviceName,
            publicKey = request.publicKey,
            tlsCertificate = request.tlsCertificate,
            bluetoothAddress = bluetoothDevice.address
        )
        pendingBleDevices[requestId] = bluetoothDevice
        return requestId
    }

    private fun isValidBlePairingRequest(request: BlePairingRequest): Boolean {
        if (!request.isStructurallyValid() || request.deviceId.isBlank() || request.deviceName.isBlank() ||
            request.publicKey.isBlank() || request.signature.isNullOrBlank()
        ) return false
        val now = System.currentTimeMillis()
        if (request.timestamp < now - BLE_REQUEST_CLOCK_SKEW_MS ||
            request.timestamp > now + BLE_REQUEST_CLOCK_SKEW_MS
        ) {
            return false
        }
        return identityStore.verify(
            request.publicKey,
            request.canonicalPayload(),
            request.signature
        ) && (request.tlsCertificate == null || decodeAndValidateCertificate(request.tlsCertificate) != null)
    }

    fun acceptPairRequest(requestId: String): Flow<PairResult> = flow {
        val request = pendingPairRequests[requestId]
        if (request == null) {
            emit(PairResult(false, "Pair request not found"))
            return@flow
        }

        try {
            val existing = try {
                deviceDao.getById(request.deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Could not load existing pair request device: ${e.message}")
                throw e
            }?.takeIf { it.id == request.deviceId }
            val remoteCertificate = decodeAndValidateCertificate(request.tlsCertificate)
            if (request.tlsCertificate != null && remoteCertificate == null) {
                emit(PairResult(false, "Invalid TLS certificate in pairing request"))
                return@flow
            }
            if (existing?.isPaired == true &&
                !existing.publicKey.isNullOrBlank() &&
                !request.publicKey.isNullOrBlank() &&
                existing.publicKey != request.publicKey
            ) {
                emit(PairResult(false, "Remote public key mismatch"))
                return@flow
            }
            val existingCertificate = encryption.getDeviceCertificate(request.deviceId)
            if (existingCertificate != null && remoteCertificate != null &&
                !existingCertificate.encoded.contentEquals(remoteCertificate.encoded)
            ) {
                emit(PairResult(false, "Remote TLS certificate mismatch"))
                return@flow
            }

            val pairedDevice = (existing ?: Device(
                id = request.deviceId,
                name = request.deviceName,
                type = DeviceType.PHONE,
                role = DeviceRole.SECONDARY,
                publicKey = request.publicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = true,
                bluetoothAddress = request.bluetoothAddress
            )).copy(
                name = request.deviceName,
                publicKey = request.publicKey ?: existing?.publicKey,
                bluetoothAddress = request.bluetoothAddress ?: existing?.bluetoothAddress,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )

            // Old UI-only requests have no key and remain compatible with the
            // local model, but cannot authenticate a network connection.
            remoteCertificate?.let { encryption.saveDeviceCertificate(request.deviceId, it) }
            deviceDao.insert(pairedDevice)
            pendingPairRequests.remove(requestId)
            pendingBleDevices.remove(requestId)?.let { bluetoothDevice ->
                bleGattServer?.sendPairingResponse(bluetoothDevice, signedBleResponse(true, "Pairing accepted"))
            }
            emit(PairResult(true, "Pairing accepted", pairedDevice))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to accept pair request", e)
            emit(PairResult(false, "Failed to accept: ${e.message}"))
        }
    }

    fun rejectPairRequest(requestId: String) {
        pendingPairRequests.remove(requestId)?.let {
            pendingBleDevices.remove(requestId)?.let { bluetoothDevice ->
                bleGattServer?.sendPairingResponse(bluetoothDevice, signedBleResponse(false, "Pairing rejected"))
            }
            logger.i(TAG, "Rejected pair request from: ${it.deviceName}")
        }
    }

    fun generatePairQRCode(deviceId: String): String {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        val issuedAt = System.currentTimeMillis()
        val data = PairQRData(
            version = QR_VERSION,
            deviceId = deviceId,
            pairKey = randomToken(),
            timestamp = issuedAt,
            deviceName = android.os.Build.MODEL.orEmpty().ifBlank { "Android device" },
            deviceType = DeviceType.ANDROID,
            publicKey = identityStore.publicKeyBase64(),
            expiresAt = issuedAt + QR_VALIDITY_MS,
            tlsCertificate = localTlsCertificate(),
            signature = ""
        )
        val signed = data.copy(signature = identityStore.sign(data.canonicalPayload()))
        tokenStore.saveIssued(signed.pairKey, deviceId, signed.expiresAt)
        return gson.toJson(signed)
    }

    private fun signedBleResponse(success: Boolean, message: String): BlePairingResponse {
        val unsigned = BlePairingResponse(
            success = success,
            message = message,
            publicKey = identityStore.publicKeyBase64(),
            tlsCertificate = localTlsCertificate(),
            timestamp = System.currentTimeMillis(),
            deviceId = identityStore.deviceId()
        )
        return unsigned.copy(signature = identityStore.sign(unsigned.canonicalPayload()))
    }

    /** Returns the one-time QR capability to place in the first handshake. */
    fun getPairingToken(deviceId: String): String? = tokenStore.getPeer(deviceId)

    fun clearPairingToken(deviceId: String) = tokenStore.removePeer(deviceId)

    /** Remove every peer-only credential when a device is unpaired. */
    suspend fun removeDeviceSecrets(deviceId: String) {
        if (deviceId.isBlank()) return
        tokenStore.removePeer(deviceId)
        encryption.removeDeviceCertificate(deviceId)
    }

    /** Consume an issuer-side QR capability and pin the scanner public key. */
    suspend fun acceptIncomingPairing(
        sourceDeviceId: String,
        sourceDeviceName: String,
        sourcePublicKey: String,
        pairingToken: String,
        ipAddress: String? = null,
        port: Int = DEFAULT_PORT,
        bluetoothAddress: String? = null,
        tlsCertificate: String? = null
    ): Boolean {
        if (sourceDeviceId.isBlank() || sourceDeviceId.length > 128 ||
            sourceDeviceId == identityStore.deviceId() || sourceDeviceName.isBlank() ||
            sourceDeviceName.length > 128 || sourcePublicKey.isBlank() || pairingToken.isBlank()
        ) return false

        val remoteCertificate = decodeAndValidateCertificate(tlsCertificate)
        if (tlsCertificate != null && remoteCertificate == null) return false

        val existing = deviceDao.getById(sourceDeviceId)
        if (existing?.isPaired == true &&
            !existing.publicKey.isNullOrBlank() && existing.publicKey != sourcePublicKey
        ) {
            return false
        }

        val existingCertificate = encryption.getDeviceCertificate(sourceDeviceId)
        if (existingCertificate != null && remoteCertificate != null &&
            !existingCertificate.encoded.contentEquals(remoteCertificate.encoded)
        ) {
            return false
        }

        // Consume only after every request field and pinned identity has been
        // validated. A malformed or stale connection must not burn the QR
        // capability and force the user to restart pairing unnecessarily.
        if (!tokenStore.consumeIssued(pairingToken, identityStore.deviceId())) return false

        deviceDao.insert(
            (existing ?: Device(
                id = sourceDeviceId,
                name = sourceDeviceName,
                type = DeviceType.PHONE,
                role = DeviceRole.SECONDARY,
                publicKey = sourcePublicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )).copy(
                name = sourceDeviceName,
                publicKey = sourcePublicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = true,
                ipAddress = ipAddress ?: existing?.ipAddress,
                port = port,
                bluetoothAddress = bluetoothAddress ?: existing?.bluetoothAddress
            )
        )
        remoteCertificate?.let { encryption.saveDeviceCertificate(sourceDeviceId, it) }
        return true
    }

    fun getPendingRequests(): List<PairRequest> = pendingPairRequests.values.sortedBy { it.timestamp }

    fun cleanupExpiredRequests() {
        val cutoff = System.currentTimeMillis() - REQUEST_VALIDITY_MS
        pendingPairRequests.entries.removeIf { it.value.timestamp < cutoff }
        tokenStore.cleanup()
    }

    private fun parsePairQRCode(qrCode: String): PairQRData? = runCatching {
        gson.fromJson(qrCode, PairQRData::class.java)
    }.getOrNull()

    private fun localTlsCertificate(): String =
        CertificateCodec.encode(encryption.generateSelfSignedCertificate())

    private fun decodeAndValidateCertificate(value: String?) =
        CertificateCodec.decode(value)?.takeIf { encryption.verifyCertificate(it) }

    private fun isValidQrData(data: PairQRData): Boolean {
        val now = System.currentTimeMillis()
        if (data.version != QR_VERSION || data.deviceId.isBlank() ||
            data.deviceId.length > 128 || data.timestamp < now - QR_CLOCK_SKEW_MS ||
            data.timestamp > now + QR_CLOCK_SKEW_MS || data.expiresAt <= data.timestamp ||
            data.expiresAt > data.timestamp + QR_VALIDITY_MS
        ) return false
        val token = runCatching { Base64.getDecoder().decode(data.pairKey) }.getOrNull()
        return token?.size == TOKEN_BYTES && data.timestamp > 0L
    }

    private fun randomToken(): String = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        .let { Base64.getEncoder().encodeToString(it) }

    companion object {
        private const val TAG = "DevicePairing"
        private const val QR_VERSION = 2
        private const val TOKEN_BYTES = 32
        private const val QR_VALIDITY_MS = 5 * 60 * 1000L
        private const val QR_CLOCK_SKEW_MS = 2 * 60 * 1000L
        private const val REQUEST_VALIDITY_MS = 5 * 60 * 1000L
        private const val DEFAULT_PORT = 1716
        private const val BLE_CONNECT_RETRIES = 10
        private const val BLE_RESPONSE_RETRIES = 20
        private const val BLE_POLL_INTERVAL_MS = 500L
        private const val BLE_REQUEST_CLOCK_SKEW_MS = 5 * 60 * 1000L
    }
}

data class PairQRData(
    val version: Int = 2,
    val deviceId: String,
    val pairKey: String,
    val timestamp: Long,
    val deviceName: String? = null,
    val deviceType: DeviceType? = null,
    val publicKey: String? = null,
    val expiresAt: Long = timestamp,
    val tlsCertificate: String? = null,
    val signature: String? = null
) {
    fun canonicalPayload(): String = listOf(
        "smslink-qr",
        version.toString(),
        deviceId,
        deviceName.orEmpty(),
        (deviceType ?: DeviceType.ANDROID).name,
        pairKey,
        timestamp.toString(),
        expiresAt.toString(),
        publicKey.orEmpty(),
        tlsCertificate.orEmpty()
    ).joinToString("|")
}

data class PairRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: Long,
    val publicKey: String? = null,
    val tlsCertificate: String? = null,
    val bluetoothAddress: String? = null
)

/** Compatibility-only dependency for the original two-argument JVM tests. */
private object LegacyPairingEncryption : IEncryption {
    override fun createSSLContext(deviceId: String): SSLContext = SSLContext.getDefault()
    override fun getDeviceCertificate(deviceId: String): java.security.cert.X509Certificate? = null
    override suspend fun saveDeviceCertificate(
        deviceId: String,
        certificate: java.security.cert.X509Certificate
    ) = Unit
    override suspend fun removeDeviceCertificate(deviceId: String) = Unit
    override fun verifyCertificate(certificate: java.security.cert.X509Certificate): Boolean = true
    override fun generateSelfSignedCertificate(): java.security.cert.X509Certificate {
        throw UnsupportedOperationException("TLS certificate generation is unavailable in compatibility tests")
    }
}
