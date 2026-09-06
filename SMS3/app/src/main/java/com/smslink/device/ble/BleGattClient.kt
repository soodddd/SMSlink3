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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionTimeoutJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<BleDeviceInfo?>(null)
    val deviceInfo: StateFlow<BleDeviceInfo?> = _deviceInfo.asStateFlow()

    private var onPairingResponseReceived: ((BlePairingResponse) -> Unit)? = null
    private val pendingOperations = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private var notificationReady: CompletableDeferred<Boolean>? = null
    private val incomingChunks = ConcurrentHashMap<Int, BleChunkCodec.DecodedChunk>()
    private val gattOperationMutex = Mutex()

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
            if (bluetoothGatt == null) {
                _connectionState.value = ConnectionState.Error("GATT 连接不可用")
                return
            }

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
            disconnect()
            _connectionState.value = ConnectionState.Error("权限错误")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect", e)
            disconnect()
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

        val gatt = bluetoothGatt
        bluetoothGatt = null
        runCatching { gatt?.disconnect() }
            .onFailure { logger.e(TAG, "Error requesting GATT disconnect", it) }
        runCatching { gatt?.close() }
            .onFailure { logger.e(TAG, "Error closing GATT", it) }

        _connectionState.value = ConnectionState.Disconnected
        _deviceInfo.value = null
        failPendingOperations()
    }

    /**
     * 读取设备信息
     */
    @SuppressLint("MissingPermission")
    suspend fun readDeviceInfo(): BleDeviceInfo? = withContext(Dispatchers.IO) {
        gattOperationMutex.withLock {
            if (_connectionState.value !is ConnectionState.Connected) {
                logger.w(TAG, "Not connected")
                return@withLock null
            }

            val service = bluetoothGatt?.getService(BleConstants.SMSLINK_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConstants.DEVICE_INFO_CHARACTERISTIC)

            if (characteristic == null) {
                logger.w(TAG, "Device info characteristic not found")
                return@withLock null
            }

            val gatt = bluetoothGatt ?: return@withLock null
            val uuid = characteristic.uuid.toString()
            val deferred = CompletableDeferred<ByteArray?>()
            pendingOperations[uuid] = deferred
            try {
                if (!gatt.readCharacteristic(characteristic)) {
                    logger.w(TAG, "Failed to read characteristic")
                    return@withLock null
                }

                val data = withTimeoutOrNull(5000L) {
                    deferred.await()
                } ?: return@withLock null

                val info = BleDeviceInfo.fromBytes(data)
                _deviceInfo.value = info
                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Error reading device info", e)
                null
            } finally {
                pendingOperations.remove(uuid, deferred)
            }
        }
    }

    /**
     * 发送配对请求
     */
    @SuppressLint("MissingPermission")
    suspend fun sendPairingRequest(request: BlePairingRequest): Boolean = withContext(Dispatchers.IO) {
        gattOperationMutex.withLock {
            if (_connectionState.value !is ConnectionState.Connected) {
                logger.w(TAG, "Not connected")
                return@withLock false
            }

            val service = bluetoothGatt?.getService(BleConstants.SMSLINK_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleConstants.PAIRING_CHARACTERISTIC)

            if (characteristic == null) {
                logger.w(TAG, "Pairing characteristic not found")
                return@withLock false
            }

            val notificationsEnabled = withTimeoutOrNull(5000L) {
                notificationReady?.await() ?: false
            } ?: false
            if (!notificationsEnabled) {
                logger.w(TAG, "Pairing notifications are not enabled")
                return@withLock false
            }

            val gatt = bluetoothGatt ?: return@withLock false
            val uuid = characteristic.uuid.toString()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            var success = true
            try {
                for (chunk in BleChunkCodec.encode(request.toBytes())) {
                    val deferred = CompletableDeferred<ByteArray?>()
                    pendingOperations[uuid] = deferred
                    characteristic.value = chunk
                    val chunkSuccess = try {
                        val started = gatt.writeCharacteristic(characteristic)
                        if (!started) {
                            false
                        } else {
                            withTimeoutOrNull(5000L) { deferred.await() != null } ?: false
                        }
                    } finally {
                        pendingOperations.remove(uuid, deferred)
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Error sending pairing request", e)
                success = false
            }

            logger.i(TAG, "Pairing request sent: $success")
            success
        }
    }

    private fun failPendingOperations() {
        pendingOperations.values.forEach { it.complete(null) }
        pendingOperations.clear()
        notificationReady?.complete(false)
        notificationReady = null
        incomingChunks.clear()
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean = bluetoothGatt === gatt

    @SuppressLint("MissingPermission")
    private fun closeDisconnectedGatt(gatt: BluetoothGatt) {
        if (isCurrentGatt(gatt)) {
            bluetoothGatt = null
        }
        runCatching { gatt.close() }
    }

    /**
     * GATT 回调
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!isCurrentGatt(gatt)) {
                        closeDisconnectedGatt(gatt)
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        logger.w(TAG, "GATT connection reported failure: $status")
                        connectionTimeoutJob?.cancel()
                        closeDisconnectedGatt(gatt)
                        failPendingOperations()
                        _connectionState.value = ConnectionState.Error("GATT 连接失败: $status")
                        return
                    }
                    logger.i(TAG, "Connected to GATT server")

                    // 发现服务
                    if (!gatt.discoverServices()) {
                        logger.w(TAG, "Failed to start service discovery")
                        _connectionState.value = ConnectionState.Error("服务发现启动失败")
                        disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // A late callback from an old GATT instance must not
                    // tear down the newly connected instance or fail its
                    // pending operations.
                    if (!isCurrentGatt(gatt)) {
                        closeDisconnectedGatt(gatt)
                        return
                    }
                    logger.i(TAG, "Disconnected from GATT server")
                    connectionTimeoutJob?.cancel()
                    closeDisconnectedGatt(gatt)
                    failPendingOperations()
                    _connectionState.value = ConnectionState.Disconnected
                    _deviceInfo.value = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logger.i(TAG, "Services discovered")

                // 检查是否有 SMS-link 服务
                val service = gatt.getService(BleConstants.SMSLINK_SERVICE_UUID)
                if (service != null) {
                    logger.i(TAG, "SMS-link service found")
                    enablePairingNotifications(gatt)
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
            if (!isCurrentGatt(gatt)) return
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
            if (!isCurrentGatt(gatt)) return
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
            if (!isCurrentGatt(gatt)) return
            logger.d(TAG, "Characteristic changed: ${characteristic.uuid}")

            if (characteristic.uuid == BleConstants.PAIRING_CHARACTERISTIC) {
                val raw = characteristic.value ?: return
                val response = BlePairingResponse.fromBytes(raw) ?: run {
                    val chunk = BleChunkCodec.decode(raw) ?: return
                    incomingChunks[chunk.sequence] = chunk
                    if (incomingChunks.size > 0xFFFF ||
                        incomingChunks.values.sumOf { it.payload.size } > BleChunkCodec.MAX_ASSEMBLED_SIZE
                    ) {
                        incomingChunks.clear()
                        return
                    }
                    val assembled = BleChunkCodec.assemble(incomingChunks.values.toList())
                    if (assembled == null) return
                    incomingChunks.clear()
                    BlePairingResponse.fromBytes(assembled)
                }
                if (response != null) {
                    logger.i(TAG, "Received pairing response")
                    try {
                        onPairingResponseReceived?.invoke(response)
                    } catch (e: Exception) {
                        logger.e(TAG, "Pairing response callback failed", e)
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            if (descriptor.uuid == BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                connectionTimeoutJob?.cancel()
                if (success) {
                    logger.i(TAG, "Pairing notifications enabled")
                    _connectionState.value = ConnectionState.Connected
                } else {
                    logger.w(TAG, "Failed to enable pairing notifications: $status")
                    _connectionState.value = ConnectionState.Error("通知订阅失败")
                }
                notificationReady?.complete(success)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enablePairingNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(BleConstants.SMSLINK_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(BleConstants.PAIRING_CHARACTERISTIC)
        val descriptor = characteristic?.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (characteristic == null || descriptor == null) {
            _connectionState.value = ConnectionState.Error("配对通知不可用")
            disconnect()
            _connectionState.value = ConnectionState.Error("配对通知不可用")
            return
        }

        notificationReady?.cancel()
        notificationReady = CompletableDeferred()
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            notificationReady?.complete(false)
            _connectionState.value = ConnectionState.Error("通知注册失败")
            disconnect()
            _connectionState.value = ConnectionState.Error("通知注册失败")
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            notificationReady?.complete(false)
            _connectionState.value = ConnectionState.Error("通知订阅失败")
            disconnect()
            _connectionState.value = ConnectionState.Error("通知订阅失败")
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
