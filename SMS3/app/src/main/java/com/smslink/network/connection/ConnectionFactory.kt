package com.smslink.network.connection

import android.bluetooth.BluetoothAdapter
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import com.smslink.network.encryption.IEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接工厂
 * 根据链路类型创建相应的连接实例
 */
@Singleton
class ConnectionFactory @Inject constructor(
    private val deviceDao: DeviceDao,
    private val encryption: IEncryption,
    private val logger: ILogger,
    private val linkSelector: LinkSelector
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    /**
     * 创建连接
     * 自动选择最佳链路类型
     */
    suspend fun create(deviceId: String): Connection = withContext(Dispatchers.IO) {
        // 从数据库获取设备信息
        val device = deviceDao.getById(deviceId)
            ?: throw IllegalArgumentException("Device not found: $deviceId")

        // 获取设备的 IP 和蓝牙地址
        val targetIp = getDeviceIpAddress(deviceId)
        val bluetoothAddress = getDeviceBluetoothAddress(deviceId)

        // 选择最佳链路
        val linkType = linkSelector.selectBestLink(deviceId, targetIp, bluetoothAddress)
            ?: throw IllegalStateException("No available link for device: $deviceId")

        return@withContext createByLinkType(deviceId, linkType, targetIp, bluetoothAddress)
    }

    /**
     * 根据指定的链路类型创建连接
     */
    suspend fun createByLinkType(
        deviceId: String,
        linkType: LinkType,
        targetIp: String?,
        bluetoothAddress: String?
    ): Connection = withContext(Dispatchers.IO) {
        logger.i(TAG, "Creating connection with link type: ${linkType.description} for device: $deviceId")

        return@withContext when (linkType) {
            LinkType.WIFI_LAN -> createTcpConnection(deviceId, targetIp, DEFAULT_WIFI_PORT, linkType)
            LinkType.WIFI_HOTSPOT -> createTcpConnection(deviceId, targetIp, DEFAULT_HOTSPOT_PORT, linkType)
            LinkType.BLUETOOTH -> createBluetoothConnection(deviceId, bluetoothAddress, linkType)
        }
    }

    /**
     * 创建 TCP 连接
     */
    private fun createTcpConnection(
        deviceId: String,
        targetIp: String?,
        port: Int,
        linkType: LinkType
    ): Connection {
        if (targetIp.isNullOrEmpty()) {
            throw IllegalArgumentException("Target IP is required for TCP connection")
        }

        val connectionType = when (linkType) {
            LinkType.WIFI_LAN -> ConnectionType.WIFI
            LinkType.WIFI_HOTSPOT -> ConnectionType.HOTSPOT
            else -> ConnectionType.WIFI
        }

        return TcpConnectionWrapper(
            TcpConnectionImpl(
                deviceId = deviceId,
                host = targetIp,
                port = port,
                type = connectionType,
                encryption = encryption,
                logger = logger
            ),
            linkType
        )
    }

    /**
     * 创建蓝牙连接
     */
    private fun createBluetoothConnection(
        deviceId: String,
        bluetoothAddress: String?,
        linkType: LinkType
    ): Connection {
        if (bluetoothAddress.isNullOrEmpty()) {
            throw IllegalArgumentException("Bluetooth address is required for Bluetooth connection")
        }

        if (bluetoothAdapter == null) {
            throw IllegalStateException("Bluetooth adapter not available")
        }

        return BluetoothConnectionWrapper(
            BluetoothConnectionImpl(
                deviceId = deviceId,
                bluetoothAddress = bluetoothAddress,
                bluetoothAdapter = bluetoothAdapter,
                logger = logger
            ),
            linkType
        )
    }

    /**
     * 获取设备 IP 地址
     */
    private suspend fun getDeviceIpAddress(deviceId: String): String? {
        // TODO: 从设备发现模块或数据库获取
        // 这里简化处理，返回默认值
        return "192.168.1.100"
    }

    /**
     * 获取设备蓝牙地址
     */
    private suspend fun getDeviceBluetoothAddress(deviceId: String): String? {
        // TODO: 从数据库获取
        // 这里简化处理，返回 null
        return null
    }

    companion object {
        private const val TAG = "ConnectionFactory"
        private const val DEFAULT_WIFI_PORT = 1716
        private const val DEFAULT_HOTSPOT_PORT = 1716
    }
}

/**
 * TCP 连接包装器
 * 将 TcpConnection 适配为统一的 Connection 接口
 */
private class TcpConnectionWrapper(
    private val tcpConnection: TcpConnection,
    private val linkType: LinkType
) : Connection {
    override suspend fun connect() = tcpConnection.connect()
    override suspend fun send(data: ByteArray) = tcpConnection.send(data)
    override fun receiveFlow() = tcpConnection.receiveFlow()
    override fun isConnected() = tcpConnection.isConnected()
    override fun getType() = tcpConnection.getType()
    override fun getLinkType() = linkType
    override fun close() = tcpConnection.close()
}

/**
 * 蓝牙连接包装器
 * 将 BluetoothConnection 适配为统一的 Connection 接口
 */
private class BluetoothConnectionWrapper(
    private val bluetoothConnection: BluetoothConnection,
    private val linkType: LinkType
) : Connection {
    override suspend fun connect() = bluetoothConnection.connect()
    override suspend fun send(data: ByteArray) = bluetoothConnection.send(data)
    override fun receiveFlow() = bluetoothConnection.receiveFlow()
    override fun isConnected() = bluetoothConnection.isConnected()
    override fun getType() = bluetoothConnection.getType()
    override fun getLinkType() = linkType
    override fun close() = bluetoothConnection.close()
}
