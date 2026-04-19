package com.smslink.network.connection

import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import com.smslink.device.DeviceDiscoveryImpl
import com.smslink.network.encryption.IEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP connection factory.
 * Creates connection instances using discovered peer addresses when available.
 */
@Singleton
class TcpConnectionFactory @Inject constructor(
    private val deviceDao: DeviceDao,
    private val deviceDiscovery: DeviceDiscoveryImpl,
    private val encryption: IEncryption,
    private val logger: ILogger
) {
    /**
     * Create a TCP connection.
     */
    suspend fun create(deviceId: String, type: ConnectionType): TcpConnection = withContext(Dispatchers.IO) {
        deviceDao.getById(deviceId)
            ?: throw IllegalArgumentException("Device not found: $deviceId")

        val (host, port) = when (type) {
            ConnectionType.WIFI -> {
                getDeviceWifiAddress(deviceId) to DEFAULT_WIFI_PORT
            }

            ConnectionType.BLUETOOTH -> {
                getDeviceBluetoothAddress(deviceId) to DEFAULT_BLUETOOTH_PORT
            }

            ConnectionType.HOTSPOT -> {
                getDeviceHotspotAddress(deviceId) to DEFAULT_HOTSPOT_PORT
            }
        }

        logger.i(TAG, "Creating TCP connection to $host:$port for device: $deviceId")

        return@withContext TcpConnectionImpl(
            deviceId = deviceId,
            host = host,
            port = port,
            type = type,
            encryption = encryption,
            logger = logger
        )
    }

    /**
     * Resolve a paired/discovered device id from its current IP address.
     *
     * The inbound TCP listener only sees the remote socket address, so the
     * connection manager uses this helper to recover the real logical device id.
     */
    fun resolveDeviceIdByIpAddress(ipAddress: String): String? {
        val trimmedIp = ipAddress.trim()
        if (trimmedIp.isEmpty()) {
            return null
        }

        return deviceDiscovery.discoveredDevices.value
            .firstOrNull { it.ipAddress == trimmedIp }
            ?.device
            ?.id
    }

    private suspend fun getDeviceWifiAddress(deviceId: String): String {
        val discoveredAddress = deviceDiscovery.discoveredDevices.value
            .firstOrNull { it.device.id == deviceId }
            ?.ipAddress
            ?.takeIf { it.isNotBlank() }

        if (!discoveredAddress.isNullOrBlank()) {
            return discoveredAddress
        }

        if (isEmulatorEnvironment()) {
            return "10.0.2.2"
        }

        return "192.168.1.100"
    }

    private suspend fun getDeviceBluetoothAddress(deviceId: String): String {
        val discoveredAddress = deviceDiscovery.discoveredDevices.value
            .firstOrNull { it.device.id == deviceId }
            ?.bleDevice
            ?.address

        if (!discoveredAddress.isNullOrBlank()) {
            return discoveredAddress
        }

        return "00:00:00:00:00:00"
    }

    private suspend fun getDeviceHotspotAddress(deviceId: String): String {
        val discoveredAddress = deviceDiscovery.discoveredDevices.value
            .firstOrNull { it.device.id == deviceId }
            ?.ipAddress
            ?.takeIf { it.isNotBlank() }

        if (!discoveredAddress.isNullOrBlank()) {
            return discoveredAddress
        }

        if (isEmulatorEnvironment()) {
            return "10.0.2.2"
        }

        return "192.168.43.1"
    }

    private fun isEmulatorEnvironment(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        return fingerprint.contains("generic") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator")
    }

    companion object {
        private const val TAG = "TcpConnectionFactory"
        private const val DEFAULT_WIFI_PORT = 1716
        private const val DEFAULT_BLUETOOTH_PORT = 1717
        private const val DEFAULT_HOTSPOT_PORT = 1716
    }
}
