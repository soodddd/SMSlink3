package com.smslink.network.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import com.smslink.device.DeviceDiscoveryImpl
import com.smslink.network.encryption.IEncryption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Creates a connection only from a user-paired device's persisted endpoint. */
@Singleton
class TcpConnectionFactory @Inject constructor(
    private val deviceDao: DeviceDao,
    private val deviceDiscovery: DeviceDiscoveryImpl,
    private val encryption: IEncryption,
    private val logger: ILogger,
    @ApplicationContext private val context: Context
) {
    @SuppressLint("MissingPermission")
    suspend fun create(deviceId: String, type: ConnectionType): Connection = withContext(Dispatchers.IO) {
        val device = deviceDao.getById(deviceId)
            ?: throw IllegalArgumentException("Device not found: $deviceId")
        if (!device.isPaired) throw SecurityException("Device is not paired: $deviceId")

        when (type) {
            ConnectionType.WIFI, ConnectionType.HOTSPOT -> {
                val host = device.ipAddress?.trim().orEmpty()
                require(host.isNotEmpty()) {
                    "No discovered LAN endpoint for $deviceId; start discovery and pair again"
                }
                val port = device.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
                logger.i(TAG, "Creating ${type.name} connection to $host:$port for $deviceId")
                TcpConnectionImpl(
                    deviceId = deviceId,
                    host = host,
                    port = port,
                    type = type,
                    encryption = encryption,
                    logger = logger
                )
            }

            ConnectionType.BLUETOOTH -> {
                val address = device.bluetoothAddress?.trim().orEmpty()
                require(address.isNotEmpty()) {
                    "No bonded Bluetooth address for $deviceId"
                }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Bluetooth adapter not available")
                BluetoothConnectionAdapter(
                    BluetoothConnectionImpl(
                        deviceId = deviceId,
                        bluetoothAddress = address,
                        bluetoothAdapter = adapter,
                        logger = logger
                    )
                )
            }
        }
    }

    /** Used by the inbound listener only as a best-effort diagnostic hint. */
    fun resolveDeviceIdByIpAddress(ipAddress: String): String? {
        val normalized = ipAddress.trim()
        if (normalized.isEmpty()) return null
        return deviceDiscovery.discoveredDevices.value
            .firstOrNull { it.ipAddress == normalized }
            ?.device
            ?.id
    }

    companion object {
        private const val TAG = "ConnectionFactory"
        private const val DEFAULT_PORT = 1716
    }
}

private class BluetoothConnectionAdapter(
    private val delegate: BluetoothConnection
) : Connection {
    override suspend fun connect() = delegate.connect()
    override suspend fun send(data: ByteArray) = delegate.send(data)
    override fun receiveFlow() = delegate.receiveFlow()
    override suspend fun receiveOne() = delegate.receiveOne()
    override fun isConnected() = delegate.isConnected()
    override fun getType() = delegate.getType()
    override fun getLinkType() = LinkType.BLUETOOTH
    override fun close() = delegate.close()
}
