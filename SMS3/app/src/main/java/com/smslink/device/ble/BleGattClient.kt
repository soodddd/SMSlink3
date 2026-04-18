package com.smslink.device.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE GATT 客户端实现
 * 连接到远程 GATT 服务器，读取设备信息，发送配对请求
 */
@Singleton
class BleGattClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: BlePermissionHelper,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "BleGattClient"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionTimeoutJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDeviceInfo?>(null)
    val deviceInfo: StateFlow<BleDeviceInfo?> = _deviceInfo.asStateFlow()

    private var onPairingResponseReceived: ((BlePairingResponse) -> Unit)? = null
    private val pendingOperations = mutableMapOf<String, CompletableDeferred<ByteArray?>>()

    /**
     * 连接到 BLE 设备
     */
    @SuppressLint("MissingPermission")
    fun connect(device: android.bluetooth.BluetoothDevice, onPairingResponse: (BlePairingResponse) -> Unit) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            logger.d(TAG, "Already connected or connecting")
            return
        }

        // 检查权限
        if (!permissionHelper.hasAllBlePermissions()) {
            logger.w(TAG, "Missing BLE permissions")
            _connectionState.value = ConnectionState.Error("权限不足")
            return
        }

        logger.i(TAG, "Connecting to device: ${device.address}")
        _connectionState.value = ConnectionState.Connecting

        this.onPairingResponseReceived = onPairingResponse

        try {
            // 连接到 GATT 服务器
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            // 设置连接超时
            connectionTimeoutJob = scope.launch {
                delay(BleConstants.GATT_CONNECTION_TIMEOUT)
                if (_connectionState.value is ConnectionState.Connecting) {
                    logger.w(TAG, "Connection timeout")
                    disconnect()
                    _connectionState.value = ConnectionState.Error("连接超时")
                }
            }

        } catch (e: SecurityException) {
            logger.e(TAG, "Security exception during connection", e)
            _connectionState.value = ConnectionState.Error("权限错误")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect", e)
            _connectionState.value = ConnectionState.Error("连接失败: ${e.message}")
        }
    }

    /**
     * 断开连接
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        logger.i(TAG, "Disconnecting")

        connectionTimeoutJob?.cancel()

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: Exception) {
            logger.e(TAG, "Error during disconnect", e)
        }

        _connectionState.value = ConnectionState.Disconnected
        _deviceInfo.value = null
    }

    /**
     * 读取设备信息
     */
    @SuppressLint("MissingPermission")
    suspend fun readDeviceInfo(): BleDeviceInfo? = withContext(Dispatchers.IO) {
        if (_connectionState.value !is ConnectionState.Connected) {
            logger.w(TAG, "Not connected")
            return@withContext null
        }

        try {
            val service = bluetoothGatt?.getService(BleConstants.SMSLINK_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConstants.DEVICE_INFO_CHARACTERISTIC)

            if (characteristic == null) {
                logger.w(TAG, "Device info characteristic not found")
                return@withContext null
            }

            val deferred = CompletableDeferred<ByteArray?>()
            pendingOperations[characteristic.uuid.toString()] = deferred

            if (!bluetoothGatt!!.readCharacteristic(characteristic)) {
                logger.w(TAG, "Failed to read characteristic")
                pendingOperations.remove(characteristic.uuid.toString())
                return@withContext null
            }

            // 等待读取完成
            val data = withTimeoutOrNull(5000L) {
                deferred.await()
            }

            if (data != null) {
                val info = BleDeviceInfo.fromBytes(data)
                _deviceInfo.value = info
                return@withContext info
            }

            return@withContext null

        } catch (e: Exception) {
            logger.e(TAG, "Error reading device info", e)
            return@withContext null
        }
    }

    /**
     * 发送配对请求
     */
    @SuppressLint("MissingPermission")
    suspend fun sendPairingRequest(request: BlePairingRequest): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value !is ConnectionState.Connected) {
            logger.w(TAG, "Not connected")
            return@withContext false
        }

        try {
            val service = bluetoothGatt?.getService(BleConstants.SMSLINK_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConstants.PAIRING_CHARACTERISTIC)

            if (characteristic == null) {
                logger.w(TAG, "Pairing characteristic not found")
                return@withContext false
            }

            characteristic.value = request.toBytes()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val deferred = CompletableDeferred<ByteArray?>()
            pendingOperations[characteristic.uuid.toString()] = deferred

            if (!bluetoothGatt!!.writeCharacteristic(characteristic)) {
                logger.w(TAG, "Failed to write characteristic")
                pendingOperations.remove(characteristic.uuid.toString())
                return@withContext false
            }

            // 等待写入完成
            val success = withTimeoutOrNull(5000L) {
                deferred.await()
                true
            } ?: false

            logger.i(TAG, "Pairing request sent: $success")
            return@withContext success

        } catch (e: Exception) {
            logger.e(TAG, "Error sending pairing request", e)
            return@withContext false
        }
    }

    /**
     * GATT 回调
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.i(TAG, "Connected to GATT server")
                    connectionTimeoutJob?.cancel()

                    // 发现服务
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.i(TAG, "Disconnected from GATT server")
                    _connectionState.value = ConnectionState.Disconnected
                    _deviceInfo.value = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger.i(TAG, "Services discovered")

                // 检查是否有 SMS-link 服务
                val service = gatt.getService(BleConstants.SMSLINK_SERVICE_UUID)
                if (service != null) {
                    logger.i(TAG, "SMS-link service found")
                    _connectionState.value = ConnectionState.Connected
                } else {
                    logger.w(TAG, "SMS-link service not found")
                    _connectionState.value = ConnectionState.Error("服务不可用")
                    disconnect()
                }
            } else {
                logger.w(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("服务发现失败")
                disconnect()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val uuid = characteristic.uuid.toString()
            val deferred = pendingOperations.remove(uuid)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger.d(TAG, "Characteristic read success: $uuid")
                deferred?.complete(characteristic.value)
            } else {
                logger.w(TAG, "Characteristic read failed: $uuid, status: $status")
                deferred?.complete(null)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val uuid = characteristic.uuid.toString()
            val deferred = pendingOperations.remove(uuid)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger.d(TAG, "Characteristic write success: $uuid")
                deferred?.complete(ByteArray(0))
            } else {
                logger.w(TAG, "Characteristic write failed: $uuid, status: $status")
                deferred?.complete(null)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            logger.d(TAG, "Characteristic changed: ${characteristic.uuid}")

            // 处理配对响应通知
            if (characteristic.uuid == BleConstants.PAIRING_CHARACTERISTIC) {
                val response = BlePairingResponse.fromBytes(characteristic.value)
                if (response != null) {
                    logger.i(TAG, "Received pairing response")
                    onPairingResponseReceived?.invoke(response)
                }
            }
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}

/**
 * 连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
