package com.smslink.device

import android.content.Context
import com.google.gson.Gson
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.device.ble.BleDeviceDiscovery
import com.smslink.device.ble.BleGattServer
import com.smslink.device.ble.BlePermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 璁惧鍙戠幇瀹炵幇
 * 鏀寔 BLE 鍜?UDP 鍙屾ā寮忓彂鐜? */
@Singleton
class DeviceDiscoveryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleDeviceDiscovery: BleDeviceDiscovery,
    private val bleGattServer: BleGattServer,
    private val blePermissionHelper: BlePermissionHelper,
    private val deviceRepository: DeviceRepository,
    private val logger: ILogger
) {
    companion object {
        private const val DISCOVERY_PORT = 1716
        private const val EMULATOR_RELAY_PORT = 1816
        private const val BROADCAST_INTERVAL = 3000L // 3绉掑箍鎾竴娆?
        private const val DEVICE_TIMEOUT = 10000L // 10绉掓湭鍝嶅簲瑙嗕负绂荤嚎
        private const val BLE_FALLBACK_DELAY = 5000L
        private const val TAG = "DeviceDiscovery"
    }

    private val gson = Gson()
    private var discoveryJob: Job? = null
    private var broadcastJob: Job? = null
    private var socket: DatagramSocket? = null

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _discoveryMode = MutableStateFlow(DiscoveryMode.BLE_FIRST)
    val discoveryMode: StateFlow<DiscoveryMode> = _discoveryMode.asStateFlow()

    private var settings = DiscoverySettings.DEFAULT

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bleMonitorJob: Job? = null
    private var bleFallbackJob: Job? = null

    /**
     * 开始设备发现
     */
    fun startDiscovery(localDevice: Device) {
        logger.i(TAG, "Starting device discovery with mode: ${settings.mode}")

        when (settings.mode) {
            DiscoveryMode.BLE_ONLY -> startBleDiscovery(localDevice)
            DiscoveryMode.UDP_ONLY -> startUdpDiscovery(localDevice)
            DiscoveryMode.BLE_FIRST -> startBleFirstDiscovery(localDevice)
            DiscoveryMode.BOTH -> startBothDiscovery(localDevice)
        }
    }

    /**
     * 启动 BLE 发现
     */
    private fun startBleDiscovery(localDevice: Device) {
        if (!blePermissionHelper.hasAllBlePermissions()) {
            logger.w(TAG, "BLE permissions not granted, falling back to UDP")
            if (settings.mode == DiscoveryMode.BLE_FIRST) {
                startUdpDiscovery(localDevice)
            }
            return
        }

        if (!bleDeviceDiscovery.isBluetoothAvailable()) {
            logger.w(TAG, "Bluetooth not available, falling back to UDP")
            if (settings.mode == DiscoveryMode.BLE_FIRST) {
                startUdpDiscovery(localDevice)
            }
            return
        }

        logger.i(TAG, "Starting BLE discovery")

        bleGattServer.startServer(localDevice) { pairingRequest, _ ->
            logger.i(TAG, "Received BLE pairing request from ${pairingRequest.deviceName}")
        }
        bleGattServer.startAdvertising()
        bleDeviceDiscovery.startScan()
        startBleDeviceMonitoring()
        scheduleUdpFallback(localDevice)
    }
    /**
     * 启动 UDP 或模拟器 relay 发现
     */
    private fun startUdpDiscovery(localDevice: Device) {
        if (discoveryJob?.isActive == true) {
            logger.d(TAG, "UDP discovery already running")
            return
        }

        if (isEmulatorEnvironment()) {
            startEmulatorRelayDiscovery(localDevice)
            return
        }

        logger.i(TAG, "Starting UDP discovery")

        try {
            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                reuseAddress = true
            }

            // 启动广播任务
            broadcastJob = scope.launch {
                while (isActive) {
                    broadcastIdentity(localDevice)
                    delay(settings.udpBroadcastInterval)
                }
            }

            // 启动监听任务
            discoveryJob = scope.launch {
                listenForDevices()
            }

            // 启动设备超时检查任务
            scope.launch {
                while (isActive) {
                    delay(5000L)
                    removeStaleDevices()
                }
            }

        } catch (e: Exception) {
            logger.e(TAG, "Failed to start UDP discovery", e)
        }
    }

    /**
     * 启动模拟器专用的 relay 发现。
     * 通过宿主机上的轻量 HTTP relay 交换身份信息，避免模拟器 UDP 广播隔离。
     */
    private fun startEmulatorRelayDiscovery(localDevice: Device) {
        logger.i(TAG, "Starting emulator relay discovery")

        val relayBaseUrl = "http://10.0.2.2:$EMULATOR_RELAY_PORT"
        val localIdentity = IdentityPacket(
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            deviceType = localDevice.type,
            protocolVersion = 1
        )

        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            while (isActive) {
                postRelayIdentity(relayBaseUrl, localIdentity)
                delay(settings.udpBroadcastInterval)
            }
        }

        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive) {
                fetchRelayDevices(relayBaseUrl, localDevice.id).forEach { packet ->
                    if (packet.deviceId != localDevice.id) {
                        handleDiscoveredDevice(packet, "10.0.2.2")
                    }
                }
                delay(2000L)
            }
        }

        scope.launch {
            while (isActive) {
                delay(5000L)
                removeStaleDevices()
            }
        }
    }

    /**
     * 判断是否运行在模拟器中。
     */
    private fun isEmulatorEnvironment(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        return fingerprint.contains("generic") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator")
    }

    /**
     * 将本地身份写入宿主机 relay。
     */
    private fun postRelayIdentity(baseUrl: String, identityPacket: IdentityPacket) {
        try {
            val connection = java.net.URL("$baseUrl/register").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(gson.toJson(identityPacket).toByteArray(Charsets.UTF_8))
            }
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            logger.d(TAG, "Relay identity posted")
        } catch (e: Exception) {
            logger.w(TAG, "Failed to post relay identity: ${e.message}")
        }
    }

    /**
     * 从宿主机 relay 拉取其它设备列表。
     */
    private fun fetchRelayDevices(baseUrl: String, deviceId: String): List<IdentityPacket> {
        return try {
            val connection = java.net.URL("$baseUrl/peers?deviceId=$deviceId").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.inputStream.bufferedReader().use { reader ->
                val json = reader.readText()
                gson.fromJson(json, Array<IdentityPacket>::class.java)?.toList() ?: emptyList()
            }.also { connection.disconnect() }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to fetch relay peers: ${e.message}")
            emptyList()
        }
    }

    private fun startBleFirstDiscovery(localDevice: Device) {
        if (blePermissionHelper.hasAllBlePermissions() && bleDeviceDiscovery.isBluetoothAvailable()) {
            startBleDiscovery(localDevice)
        } else {
            logger.i(TAG, "BLE not available, using UDP")
            startUdpDiscovery(localDevice)
        }
    }

    /**
     * 在 BLE 首选模式下，如果一段时间内没有发现任何设备，则自动回落到 UDP。
     * 这样可以保证模拟器和 BLE 不可用环境仍然具备可验证的发现路径。
     */
    private fun scheduleUdpFallback(localDevice: Device) {
        if (settings.mode != DiscoveryMode.BLE_FIRST) {
            return
        }


        bleFallbackJob = scope.launch {
            delay(BLE_FALLBACK_DELAY)

            if (!scope.isActive || settings.mode != DiscoveryMode.BLE_FIRST) {
                return@launch
            }

            val hasDiscoveredDevices = _discoveredDevices.value.isNotEmpty()
            val hasBleDevices = _discoveredDevices.value.any { it.discoveryMethod == DiscoveryMethod.BLE }

            if (!hasDiscoveredDevices || !hasBleDevices) {
                logger.i(TAG, "No BLE devices found, falling back to UDP discovery")
                startUdpDiscovery(localDevice)
            }
        }
    }

    /**
     * 鍚屾椂鍚姩 BLE 鍜?UDP 鍙戠幇
     */
    private fun startBothDiscovery(localDevice: Device) {
        logger.i(TAG, "Starting both BLE and UDP discovery")

        // 鍚姩 BLE锛堝鏋滃彲鐢級
        if (blePermissionHelper.hasAllBlePermissions() && bleDeviceDiscovery.isBluetoothAvailable()) {
            startBleDiscovery(localDevice)
        }

        // 鍚姩 UDP
        startUdpDiscovery(localDevice)
    }

    /**
     * 鐩戝惉 BLE 鍙戠幇鐨勮澶?     */
    private fun startBleDeviceMonitoring() {
        bleMonitorJob?.cancel()
        bleFallbackJob?.cancel()

        bleMonitorJob = scope.launch {
            bleDeviceDiscovery.discoveredDevices.collect { bleDevices ->
                // 合并 BLE 发现的设备到总列表
                val currentDevices = _discoveredDevices.value.toMutableList()

                bleDevices.forEach { bleDevice ->
                    val existingIndex = currentDevices.indexOfFirst {
                        it.device.id == bleDevice.device.id
                    }

                    val discoveredDevice = DiscoveredDevice(
                        device = bleDevice.device,
                        ipAddress = "", // BLE 璁惧娌℃湁 IP 鍦板潃
                        lastSeen = bleDevice.lastSeen,
                        discoveryMethod = DiscoveryMethod.BLE,
                        rssi = bleDevice.rssi,
                        bleDevice = bleDevice.bleDevice
                    )

                    if (existingIndex >= 0) {
                        currentDevices[existingIndex] = discoveredDevice
                    } else {
                        currentDevices.add(discoveredDevice)
                    }
                }

                _discoveredDevices.value = currentDevices
            }
        }
    }

    /**
     * 鍋滄璁惧鍙戠幇
     */
    fun stopDiscovery() {
        logger.i(TAG, "Stopping device discovery")

        // 鍋滄 BLE
        bleDeviceDiscovery.stopScan()
        bleGattServer.stopAdvertising()
        bleGattServer.stopServer()
        bleMonitorJob?.cancel()
        bleFallbackJob?.cancel()


        // 鍋滄 UDP
        discoveryJob?.cancel()
        broadcastJob?.cancel()
        socket?.close()
        socket = null

        _discoveredDevices.value = emptyList()
    }

    /**
     * 骞挎挱鏈澶囪韩浠戒俊鎭?     */
    private suspend fun broadcastIdentity(localDevice: Device) {
        try {
            val identityPacket = IdentityPacket(
                deviceId = localDevice.id,
                deviceName = localDevice.name,
                deviceType = localDevice.type,
                protocolVersion = 1
            )

            val message = gson.toJson(identityPacket).toByteArray()
            val broadcastAddresses = getBroadcastAddresses()

            broadcastAddresses.forEach { address ->
                try {
                    val packet = DatagramPacket(
                        message,
                        message.size,
                        InetAddress.getByName(address),
                        DISCOVERY_PORT
                    )
                    socket?.send(packet)
                    logger.d(TAG, "Broadcast sent to $address")
                } catch (e: Exception) {
                    logger.w(TAG, "Failed to broadcast to $address")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to broadcast identity", e)
        }
    }

    /**
     * 鐩戝惉鍏朵粬璁惧鐨勫箍鎾?     */
    private suspend fun listenForDevices() {
        val buffer = ByteArray(1024)

        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val message = String(packet.data, 0, packet.length)
                val identityPacket = gson.fromJson(message, IdentityPacket::class.java)

                // 蹇界暐鏈澶囩殑骞挎挱
                if (identityPacket.deviceId != getLocalDeviceId()) {
                    handleDiscoveredDevice(identityPacket, packet.address.hostAddress ?: "")
                }
            } catch (e: Exception) {
                if (scope.isActive) {
                    logger.w(TAG, "Error receiving packet")
                }
            }
        }
    }

    /**
     * 澶勭悊鍙戠幇鐨勮澶?     */
    private fun handleDiscoveredDevice(identityPacket: IdentityPacket, ipAddress: String) {
        val currentDevices = _discoveredDevices.value.toMutableList()
        val existingIndex = currentDevices.indexOfFirst { it.device.id == identityPacket.deviceId }

        logger.d(
            TAG,
            "Handling discovered device: id=${identityPacket.deviceId}, name=${identityPacket.deviceName}, ip=$ipAddress"
        )

        val discoveredDevice = DiscoveredDevice(
            device = Device(
                id = identityPacket.deviceId,
                name = identityPacket.deviceName,
                type = identityPacket.deviceType,
                role = DeviceRole.SECONDARY,
                publicKey = null,
                lastSeen = System.currentTimeMillis(),
                isPaired = false
            ),
            ipAddress = ipAddress,
            lastSeen = System.currentTimeMillis(),
            discoveryMethod = DiscoveryMethod.UDP,
            rssi = null,
            bleDevice = null
        )

        if (existingIndex >= 0) {
            currentDevices[existingIndex] = discoveredDevice
        } else {
            currentDevices.add(discoveredDevice)
            logger.i(TAG, "Discovered new device: ${identityPacket.deviceName} at $ipAddress")
        }

        _discoveredDevices.value = currentDevices
        scope.launch {
            val persistedDevice = deviceRepository.getDeviceById(identityPacket.deviceId)
            val deviceToPersist = (persistedDevice ?: discoveredDevice.device).copy(
                lastSeen = System.currentTimeMillis(),
                isPaired = true
            )
            deviceRepository.saveDevice(deviceToPersist)
        }
    }

    /**
     * 绉婚櫎瓒呮椂鐨勮澶?     */
    private fun removeStaleDevices() {
        val currentTime = System.currentTimeMillis()
        val activeDevices = _discoveredDevices.value.filter {
            currentTime - it.lastSeen < settings.deviceTimeout
        }

        if (activeDevices.size != _discoveredDevices.value.size) {
            logger.d(TAG, "Removed ${_discoveredDevices.value.size - activeDevices.size} stale devices")
            _discoveredDevices.value = activeDevices
        }
    }

    /**
     * 鑾峰彇骞挎挱鍦板潃鍒楄〃
     */
    private fun getBroadcastAddresses(): List<String> {
        val addresses = mutableListOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcast = interfaceAddress.broadcast
                    val hostAddress = broadcast?.hostAddress
                    if (!hostAddress.isNullOrBlank()) {
                        addresses.add(hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get broadcast addresses", e)
        }

        val fingerprint = android.os.Build.FINGERPRINT.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        val isEmulator = fingerprint.contains("generic", ignoreCase = true) ||
            model.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true)

        if (addresses.isEmpty()) {
            addresses.add("255.255.255.255")
        }

        if (isEmulator) {
            addresses.add("255.255.255.255")
            addresses.add("10.0.2.255")
        }

        return addresses.distinct()
    }

    /**
     * 鑾峰彇鏈湴璁惧ID
     */
    private fun getLocalDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    /**
     * 娓呯悊璧勬簮
     */
    fun cleanup() {
        stopDiscovery()
        bleDeviceDiscovery.cleanup()
        bleGattServer.cleanup()
        scope.cancel()
    }
}

/**
 * 鍙戠幇鏂规硶
 */
enum class DiscoveryMethod {
    BLE,    // 閫氳繃 BLE 鍙戠幇
    UDP     // 閫氳繃 UDP 骞挎挱鍙戠幇
}

/**
 * 韬唤骞挎挱鍖? */
data class IdentityPacket(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val protocolVersion: Int
)

/**
 * 鍙戠幇鐨勮澶? */
data class DiscoveredDevice(
    val device: Device,
    val ipAddress: String,
    val lastSeen: Long,
    val discoveryMethod: DiscoveryMethod = DiscoveryMethod.UDP,
    val rssi: Int? = null,
    val bleDevice: android.bluetooth.BluetoothDevice? = null
)











