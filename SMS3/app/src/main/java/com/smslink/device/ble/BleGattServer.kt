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
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val connectedDevices: StateFlow<List<BluetoothDevice>> = _connectedDevices.asStateFlow()

    private var localDeviceInfo: BleDeviceInfo? = null
    private var onPairingRequestReceived: ((BlePairingRequest, BluetoothDevice) -> Unit)? = null

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
            // 创建 GATT 服务器
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

            // 添加 SMS-link 服务
            val service = createSmsLinkService()
            gattServer?.addService(service)

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

        if (bluetoothLeAdvertiser == null) {
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
                    localDeviceInfo?.toBytes() ?: ByteArray(0)
                )
                .build()

            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)

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
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
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
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
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
                    val devices = _connectedDevices.value.toMutableList()
                    if (!devices.contains(device)) {
                        devices.add(device)
                        _connectedDevices.value = devices
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.i(TAG, "Device disconnected: ${device.address}")
                    val devices = _connectedDevices.value.toMutableList()
                    devices.remove(device)
                    _connectedDevices.value = devices
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
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        data
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

            when (characteristic.uuid) {
                BleConstants.PAIRING_CHARACTERISTIC -> {
                    // 处理配对请求
                    val pairingRequest = BlePairingRequest.fromBytes(value)
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

                characteristic?.value = response.toBytes()
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)

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
