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
import com.smslink.security.DeviceIdentityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val logger: ILogger,
    private val identityStore: DeviceIdentityStore,
    private val devicePairing: DevicePairingImpl
) {
    companion object {
        private const val DISCOVERY_PORT = 1716
        private const val BROADCAST_INTERVAL = 3000L // 3绉掑箍鎾竴娆?
        private const val DEVICE_TIMEOUT = 10000L // 10绉掓湭鍝嶅簲瑙嗕负绂荤嚎
        private const val BLE_FALLBACK_DELAY = 5000L
        private const val MAX_DISCOVERY_PACKET_BYTES = 4096
        private const val TAG = "DeviceDiscovery"
    }

    private val gson = Gson()
    private var discoveryJob: Job? = null
    private var broadcastJob: Job? = null
    private var staleDeviceJob: Job? = null
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
        if (discoveryJob?.isActive == true || broadcastJob?.isActive == true ||
            bleMonitorJob?.isActive == true || bleFallbackJob?.isActive == true
        ) {
            logger.d(TAG, "Discovery already running; restarting with current settings")
            stopDiscovery()
        }
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

        bleGattServer.startServer(localDevice) { pairingRequest, bluetoothDevice ->
            logger.i(TAG, "Received BLE pairing request from ${pairingRequest.deviceName}")
            devicePairing.receiveBlePairRequest(pairingRequest, bluetoothDevice)
        }
        bleGattServer.startAdvertising()
        bleDeviceDiscovery.startScan()
        startBleDeviceMonitoring()
        scheduleUdpFallback(localDevice)
    }
    /** 启动 UDP 发现。 */
    private fun startUdpDiscovery(localDevice: Device) {
        if (discoveryJob?.isActive == true) {
            logger.d(TAG, "UDP discovery already running")
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
            staleDeviceJob = scope.launch {
                while (isActive) {
                    delay(5000L)
                    removeStaleDevices()
                }
            }

        } catch (e: Exception) {
            logger.e(TAG, "Failed to start UDP discovery", e)
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

    /** 在 BLE 首选模式下，如果没有发现 BLE 设备，则自动回落到 UDP。 */
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
        staleDeviceJob?.cancel()
        staleDeviceJob = null

        bleMonitorJob = scope.launch {
            bleDeviceDiscovery.discoveredDevices.collect { bleDevices ->
                // 合并 BLE 发现的设备到总列表
                _discoveredDevices.update { existingDevices ->
                    val currentDevices = existingDevices.toMutableList()

                    bleDevices.forEach { bleDevice ->
                        val existingIndex = currentDevices.indexOfFirst {
                            it.device.id == bleDevice.device.id
                        }
                        val existing = currentDevices.getOrNull(existingIndex)

                        val discoveredDevice = DiscoveredDevice(
                            device = bleDevice.device.copy(
                                ipAddress = existing?.device?.ipAddress,
                                port = existing?.device?.port ?: bleDevice.device.port,
                                bluetoothAddress = bleDevice.bleDevice.address
                            ),
                            ipAddress = existing?.ipAddress.orEmpty(),
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

                    currentDevices
                }
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
        staleDeviceJob?.cancel()
        staleDeviceJob = null

        // 鍋滄 UDP
        discoveryJob?.cancel()
        broadcastJob?.cancel()
        discoveryJob = null
        broadcastJob = null
        socket?.close()
        socket = null

        _discoveredDevices.value = emptyList()
    }

    /**
     * 骞挎挱鏈澶囪韩浠戒俊鎭?     */
    private suspend fun broadcastIdentity(localDevice: Device) {
        try {
            val identityPacket = signedIdentityPacket(localDevice)

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
        // RSA signatures and public keys make a signed identity packet larger
        // than the old 1 KiB buffer on some device names/encodings. A fixed
        // bounded buffer prevents truncation without accepting unbounded UDP
        // input.
        val buffer = ByteArray(MAX_DISCOVERY_PACKET_BYTES)

        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val message = String(packet.data, 0, packet.length)
                val identityPacket = try {
                    gson.fromJson(message, IdentityPacket::class.java)
                } catch (parseError: Exception) {
                    logger.w(
                        TAG,
                        "Ignoring malformed discovery packet from ${packet.address?.hostAddress}: " +
                            "${parseError::class.simpleName}: ${parseError.message}"
                    )
                    null
                } ?: continue

                // 蹇界暐鏈澶囩殑骞挎挱
                if (identityPacket.deviceId != getLocalDeviceId() &&
                    identityPacket.isAuthentic(identityStore)
                ) {
                    handleDiscoveredDevice(identityPacket, packet.address.hostAddress ?: "")
                }
            } catch (e: Exception) {
                if (scope.isActive) {
                    logger.w(TAG, "Error receiving packet: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * 澶勭悊鍙戠幇鐨勮澶?     */
    private fun handleDiscoveredDevice(identityPacket: IdentityPacket, ipAddress: String) {
        val deviceType = identityPacket.deviceType ?: return
        logger.d(
            TAG,
            "Handling discovered device: id=${identityPacket.deviceId}, name=${identityPacket.deviceName}, ip=$ipAddress"
        )

        val discoveredDevice = DiscoveredDevice(
            device = Device(
                id = identityPacket.deviceId,
                name = identityPacket.deviceName,
                type = deviceType,
                role = DeviceRole.SECONDARY,
                publicKey = identityPacket.publicKey,
                lastSeen = System.currentTimeMillis(),
                isPaired = false,
                ipAddress = ipAddress,
                port = identityPacket.port
            ),
            ipAddress = ipAddress,
            lastSeen = System.currentTimeMillis(),
            discoveryMethod = DiscoveryMethod.UDP,
            rssi = null,
            bleDevice = null
        )

        _discoveredDevices.update { existingDevices ->
            val currentDevices = existingDevices.toMutableList()
            val existingIndex = currentDevices.indexOfFirst { it.device.id == identityPacket.deviceId }
            if (existingIndex >= 0) {
                currentDevices[existingIndex] = discoveredDevice
            } else {
                currentDevices.add(discoveredDevice)
                logger.i(TAG, "Discovered new device: ${identityPacket.deviceName} at $ipAddress")
            }
            currentDevices
        }
        scope.launch {
            val persistedDevice = deviceRepository.getDeviceById(identityPacket.deviceId)
            if (persistedDevice?.isPaired == true && identityPacket.isAuthentic(identityStore)) {
                if (!persistedDevice.publicKey.isNullOrBlank() &&
                    !identityPacket.publicKey.isNullOrBlank() &&
                    persistedDevice.publicKey != identityPacket.publicKey
                ) {
                    logger.w(TAG, "Ignoring discovery key change for pinned device ${identityPacket.deviceId}")
                    return@launch
                }
                deviceRepository.saveDevice(
                    persistedDevice.copy(
                        name = identityPacket.deviceName,
                        type = deviceType,
                        publicKey = persistedDevice.publicKey ?: identityPacket.publicKey,
                        lastSeen = System.currentTimeMillis(),
                        ipAddress = ipAddress,
                        port = identityPacket.port
                    )
                )
            }
        }
    }

    /**
     * 绉婚櫎瓒呮椂鐨勮澶?     */
    private fun removeStaleDevices() {
        val currentTime = System.currentTimeMillis()
        _discoveredDevices.update { devices ->
            val activeDevices = devices.filter {
                currentTime - it.lastSeen < settings.deviceTimeout
            }
            if (activeDevices.size != devices.size) {
                logger.d(TAG, "Removed ${devices.size - activeDevices.size} stale devices")
            }
            activeDevices
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

        if (addresses.isEmpty()) {
            addresses.add("255.255.255.255")
        }

        return addresses.distinct()
    }

    /**
     * 鑾峰彇鏈湴璁惧ID
     */
    private fun getLocalDeviceId(): String {
        return identityStore.deviceId()
    }

    private fun signedIdentityPacket(localDevice: Device): IdentityPacket {
        val unsigned = IdentityPacket(
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            deviceType = localDevice.type,
            protocolVersion = 1,
            publicKey = localDevice.publicKey,
            port = DISCOVERY_PORT,
            signature = ""
        )
        return unsigned.copy(signature = identityStore.sign(unsigned.canonicalPayload()))
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
    val deviceType: DeviceType?,
    val protocolVersion: Int,
    val publicKey: String? = null,
    val port: Int = 1716,
    val signature: String? = null
) {
    fun canonicalPayload(): String = listOf(
        "smslink-discovery",
        protocolVersion.toString(),
        deviceId,
        deviceName,
        deviceType?.name.orEmpty(),
        port.toString(),
        publicKey.orEmpty()
    ).joinToString("|")

    fun isStructurallyValid(): Boolean =
        protocolVersion == 1 && deviceId.isNotBlank() && deviceId.length <= 128 &&
            deviceName.isNotBlank() && deviceName.length <= 128 &&
            deviceType != null &&
            port in 1..65535 && (publicKey.isNullOrBlank() || !signature.isNullOrBlank())

    fun isAuthentic(identityStore: DeviceIdentityStore): Boolean {
        if (!isStructurallyValid()) return false
        val key = publicKey ?: return false
        val sig = signature ?: return false
        return identityStore.verify(key, canonicalPayload(), sig)
    }
}

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











