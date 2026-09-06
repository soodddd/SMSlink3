package com.smslink.device.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE GATT 服务器实现
 * 创建 GATT 服务器，广播设备信息，处理客户端连接和请求
 */
@Singleton
class BleGattServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: BlePermissionHelper,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "BleGattServer"
        private const val BLE_NOTIFY_DELAY_MS = 15L
        private const val MAX_INCOMING_CHUNKS = 0xFFFF
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val connectedDevices: StateFlow<List<BluetoothDevice>> = _connectedDevices.asStateFlow()

    private var localDeviceInfo: BleDeviceInfo? = null
    private var onPairingRequestReceived: ((BlePairingRequest, BluetoothDevice) -> Unit)? = null
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val incomingChunks = ConcurrentHashMap<String, MutableMap<Int, BleChunkCodec.DecodedChunk>>()

    /**
     * 启动 GATT 服务器
     */
    @SuppressLint("MissingPermission")
    fun startServer(localDevice: Device, onPairingRequest: (BlePairingRequest, BluetoothDevice) -> Unit) {
        if (gattServer != null) {
            logger.d(TAG, "GATT server already running")
            return
        }

        // 检查权限
        if (!permissionHelper.hasAllBlePermissions()) {
            logger.w(TAG, "Missing BLE permissions")
            return
        }

        // 检查蓝牙是否可用
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            logger.w(TAG, "Bluetooth not enabled")
            return
        }

        logger.i(TAG, "Starting GATT server")

        this.onPairingRequestReceived = onPairingRequest
        this.localDeviceInfo = BleDeviceInfo(
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            deviceType = localDevice.type,
            version = "1.0.0"
        )

        try {
            // 创建 GATT 服务器并确认服务真正注册成功. Advertising a
            // device whose GATT database failed to open produces a peer that
            // can be discovered but can never be paired.
            val server = bluetoothManager?.openGattServer(context, gattServerCallback)
                ?: throw IllegalStateException("Bluetooth GATT server unavailable")
            if (!server.addService(createSmsLinkService())) {
                server.close()
                throw IllegalStateException("Unable to register SMS-link GATT service")
            }
            gattServer = server

            logger.i(TAG, "GATT server started successfully")

        } catch (e: SecurityException) {
            logger.e(TAG, "Security exception starting GATT server", e)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start GATT server", e)
        }
    }

    /**
     * 开始广播
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (_isAdvertising.value) {
            logger.d(TAG, "Already advertising")
            return
        }

        if (gattServer == null) {
            logger.w(TAG, "Cannot advertise before GATT server is ready")
            return
        }

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            logger.w(TAG, "BLE advertiser not available")
            return
        }

        logger.i(TAG, "Starting BLE advertising")

        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleConstants.SMSLINK_SERVICE_UUID))
                .addServiceData(
                    ParcelUuid(BleConstants.SMSLINK_SERVICE_UUID),
                    // Keep the advertisement within the legacy 31-byte
                    // budget. Full identity is read over GATT after connect.
                    byteArrayOf(0x53, 0x4c, BleConstants.PROTOCOL_VERSION.toByte())
                )
                .build()

            advertiser.startAdvertising(settings, data, advertiseCallback)

        } catch (e: SecurityException) {
            logger.e(TAG, "Security exception during advertising", e)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start advertising", e)
        }
    }

    /**
     * 停止广播
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value) {
            return
        }

        logger.i(TAG, "Stopping BLE advertising")

        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
        } catch (e: Exception) {
            logger.e(TAG, "Error stopping advertising", e)
        }
    }

    /**
     * 停止 GATT 服务器
     */
    @SuppressLint("MissingPermission")
    fun stopServer() {
        logger.i(TAG, "Stopping GATT server")

        stopAdvertising()

        try {
            gattServer?.close()
            gattServer = null
            _connectedDevices.value = emptyList()
            subscribedDevices.clear()
            incomingChunks.clear()
        } catch (e: Exception) {
            logger.e(TAG, "Error stopping GATT server", e)
        }
    }

    /**
     * 创建 SMS-link GATT 服务
     */
    private fun createSmsLinkService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleConstants.SMSLINK_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 设备信息特征（可读）
        val deviceInfoCharacteristic = BluetoothGattCharacteristic(
            BleConstants.DEVICE_INFO_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(deviceInfoCharacteristic)

        // 配对特征（可读写）
            val pairingCharacteristic = BluetoothGattCharacteristic(
                BleConstants.PAIRING_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            pairingCharacteristic.addDescriptor(
                BluetoothGattDescriptor(
                    BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        service.addCharacteristic(pairingCharacteristic)

        // 控制特征（可读写通知）
        val controlCharacteristic = BluetoothGattCharacteristic(
            BleConstants.CONTROL_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(controlCharacteristic)

        return service
    }

    /**
     * GATT 服务器回调
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.i(TAG, "Device connected: ${device.address}")
                    _connectedDevices.update { current ->
                        current.toMutableList().apply {
                            if (!contains(device)) add(device)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.i(TAG, "Device disconnected: ${device.address}")
                    _connectedDevices.update { current ->
                        current.filterNot { it == device }
                    }
                    subscribedDevices.remove(device)
                    incomingChunks.remove(device.address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            logger.d(TAG, "Read request from ${device.address} for ${characteristic.uuid}")

            when (characteristic.uuid) {
                BleConstants.DEVICE_INFO_CHARACTERISTIC -> {
                    val data = localDeviceInfo?.toBytes() ?: ByteArray(0)
                    if (offset < 0 || offset > data.size) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_OFFSET,
                            offset,
                            null
                        )
                        return
                    }
                    // A default ATT connection can carry only 20 bytes in a
                    // response. Android will issue follow-up long-read
                    // requests with increasing offsets when needed.
                    val end = minOf(offset + 20, data.size)
                    val response = data.copyOfRange(offset, end)
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        response
                    )
                }
                else -> {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            logger.d(TAG, "Write request from ${device.address} for ${characteristic.uuid}")

            if (offset != 0 || preparedWrite) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                }
                return
            }

            when (characteristic.uuid) {
                BleConstants.PAIRING_CHARACTERISTIC -> {
                    // A request may be split across several characteristic
                    // writes. An incomplete but valid chunk is accepted and
                    // must not be reported as GATT_FAILURE; otherwise the
                    // client stops before the final chunk arrives.
                    val isValidChunk = BleChunkCodec.decode(value) != null
                    val pairingRequest = decodePairingRequest(device, value)
                    if (pairingRequest != null) {
                        logger.i(TAG, "Received pairing request from ${pairingRequest.deviceName}")
                        onPairingRequestReceived?.invoke(pairingRequest, device)

                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                            )
                        }
                    } else if (isValidChunk && incomingChunks.containsKey(device.address)) {
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                            )
                        }
                    } else {
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_FAILURE,
                                offset,
                                null
                            )
                        }
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> subscribedDevices.add(device)
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> subscribedDevices.remove(device)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    private fun decodePairingRequest(device: BluetoothDevice, value: ByteArray): BlePairingRequest? {
        // Accept a complete legacy write as well as the chunked protocol so
        // older SMS-link builds can still be upgraded through BLE.
        BlePairingRequest.fromBytes(value)?.let { return it }
        val chunk = BleChunkCodec.decode(value) ?: return null
        val chunks = incomingChunks.getOrPut(device.address) { ConcurrentHashMap() }
        if (chunks.values.firstOrNull()?.total != null &&
            chunks.values.firstOrNull()?.total != chunk.total
        ) {
            chunks.clear()
        }
        chunks[chunk.sequence] = chunk
        if (chunks.size > MAX_INCOMING_CHUNKS ||
            chunks.values.sumOf { it.payload.size } > BleChunkCodec.MAX_ASSEMBLED_SIZE
        ) {
            incomingChunks.remove(device.address)
            return null
        }
        val assembled = BleChunkCodec.assemble(chunks.values) ?: return null
        incomingChunks.remove(device.address)
        return BlePairingRequest.fromBytes(assembled)
    }

    /**
     * 广播回调
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logger.i(TAG, "BLE advertising started successfully")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "数据过大"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播器过多"
                ADVERTISE_FAILED_ALREADY_STARTED -> "已在广播"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "内部错误"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                else -> "未知错误: $errorCode"
            }
            logger.e(TAG, "BLE advertising failed: $errorMessage")
            _isAdvertising.value = false
        }
    }

    /**
     * 发送配对响应
     */
    @SuppressLint("MissingPermission")
    fun sendPairingResponse(device: BluetoothDevice, response: BlePairingResponse) {
        scope.launch {
            try {
                val service = gattServer?.getService(BleConstants.SMSLINK_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleConstants.PAIRING_CHARACTERISTIC)

                if (characteristic == null || !subscribedDevices.contains(device)) {
                    logger.w(TAG, "Pairing response skipped; client is not subscribed")
                    return@launch
                }
                BleChunkCodec.encode(response.toBytes()).forEach { chunk ->
                    characteristic.value = chunk
                    if (gattServer?.notifyCharacteristicChanged(device, characteristic, false) != true) {
                        throw IllegalStateException("GATT notification was not accepted")
                    }
                    delay(BLE_NOTIFY_DELAY_MS)
                }

                logger.i(TAG, "Sent pairing response to ${device.address}")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to send pairing response", e)
            }
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopServer()
        scope.cancel()
    }

}
