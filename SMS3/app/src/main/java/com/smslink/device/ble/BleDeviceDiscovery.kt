package com.smslink.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE 设备发现实现
 * 使用 BluetoothLeScanner 扫描 BLE 设备
 */
@Singleton
class BleDeviceDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: BlePermissionHelper,
    private val logger: ILogger
) {
    companion object {
        private const val TAG = "BleDeviceDiscovery"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var scanJob: Job? = null
    private var timeoutJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    /**
     * 开始 BLE 扫描
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) {
            logger.d(TAG, "BLE scan already running")
            return
        }

        // 检查权限
        if (!permissionHelper.hasAllBlePermissions()) {
            val status = permissionHelper.getPermissionStatus()
            _scanError.value = "权限不足: ${status.getMissingPermissionsDescription()}"
            logger.w(TAG, "Missing BLE permissions")
            return
        }

        // 检查蓝牙是否可用
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _scanError.value = "蓝牙未开启"
            logger.w(TAG, "Bluetooth not enabled")
            return
        }

        logger.i(TAG, "Starting BLE scan")
        _isScanning.value = true
        _scanError.value = null

        try {
            // 配置扫描过滤器 - 只扫描 SMS-link 服务
            val scanFilters = runCatching {
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BleConstants.SMSLINK_SERVICE_UUID))
                        .build()
                )
            }.getOrElse {
                logger.w(TAG, "Falling back to empty BLE scan filters: ${it.message}")
                emptyList()
            }

            // 配置扫描设置
            val scanSettings = runCatching {
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 低延迟模式
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0) // 立即报告
                    .build()
            }.getOrElse {
                logger.w(TAG, "Falling back to default BLE scan settings: ${it.message}")
                null
            }

            // 开始扫描
            if (scanSettings != null) {
                bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
            } else {
                logger.w(TAG, "Skipping hardware BLE scan in test environment")
            }

            // 启动超时清理任务
            startTimeoutCleanup()

            // 定期重启扫描（避免某些设备的扫描停止问题）
            scanJob = scope.launch {
                delay(BleConstants.SCAN_PERIOD)
                if (_isScanning.value) {
                    logger.d(TAG, "Restarting BLE scan")
                    stopScan()
                    delay(1000)
                    startScan()
                }
            }

        } catch (e: SecurityException) {
            logger.e(TAG, "Security exception during scan", e)
            _scanError.value = "权限错误: ${e.message}"
            _isScanning.value = false
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start BLE scan", e)
            _scanError.value = "扫描失败: ${e.message}"
        }
    }

    /**
     * 停止 BLE 扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }

        logger.i(TAG, "Stopping BLE scan")

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            logger.e(TAG, "Error stopping scan", e)
        }

        scanJob?.cancel()
        timeoutJob?.cancel()
        _isScanning.value = false
    }

    /**
     * 扫描回调
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                else -> "未知错误: $errorCode"
            }

            logger.e(TAG, "BLE scan failed: $errorMessage")
            _scanError.value = "扫描失败: $errorMessage"
            _isScanning.value = false
        }
    }

    /**
     * 处理扫描结果
     */
    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        try {
            val device = result.device
            val rssi = result.rssi
            val scanRecord = result.scanRecord

            // 检查是否包含 SMS-link 服务
            val serviceUuids = scanRecord?.serviceUuids
            if (serviceUuids?.any { it.uuid == BleConstants.SMSLINK_SERVICE_UUID } != true) {
                return
            }

            // 获取设备信息
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address

            logger.d(TAG, "Found SMS-link device: $deviceName ($deviceAddress) RSSI: $rssi")

            // 尝试从广播数据中解析设备信息
            val serviceData = scanRecord.getServiceData(ParcelUuid(BleConstants.SMSLINK_SERVICE_UUID))
            val bleDeviceInfo = serviceData?.let { BleDeviceInfo.fromBytes(it) }

            // 创建或更新发现的设备
            val currentDevices = _discoveredDevices.value.toMutableList()
            val existingIndex = currentDevices.indexOfFirst { it.address == deviceAddress }

            val discoveredDevice = BleDiscoveredDevice(
                device = Device(
                    id = bleDeviceInfo?.deviceId ?: deviceAddress,
                    name = bleDeviceInfo?.deviceName ?: deviceName,
                    type = bleDeviceInfo?.deviceType ?: com.smslink.core.model.DeviceType.PHONE,
                    role = DeviceRole.SECONDARY,
                    publicKey = null,
                    lastSeen = System.currentTimeMillis(),
                    isPaired = false
                ),
                address = deviceAddress,
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                bleDevice = device
            )

            if (existingIndex >= 0) {
                currentDevices[existingIndex] = discoveredDevice
            } else {
                currentDevices.add(discoveredDevice)
                logger.i(TAG, "New BLE device discovered: ${discoveredDevice.device.name}")
            }

            _discoveredDevices.value = currentDevices

        } catch (e: Exception) {
            logger.e(TAG, "Error handling scan result", e)
        }
    }

    /**
     * 启动超时清理任务
     */
    private fun startTimeoutCleanup() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            while (isActive) {
                delay(5000L) // 每5秒检查一次
                removeStaleDevices()
            }
        }
    }

    /**
     * 移除超时的设备
     */
    private fun removeStaleDevices() {
        val currentTime = System.currentTimeMillis()
        val activeDevices = _discoveredDevices.value.filter {
            currentTime - it.lastSeen < BleConstants.DEVICE_TIMEOUT
        }

        if (activeDevices.size != _discoveredDevices.value.size) {
            val removed = _discoveredDevices.value.size - activeDevices.size
            logger.d(TAG, "Removed $removed stale BLE devices")
            _discoveredDevices.value = activeDevices
        }
    }

    /**
     * 清除所有发现的设备
     */
    fun clearDevices() {
        _discoveredDevices.value = emptyList()
    }

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopScan()
        clearDevices()
        scope.cancel()
    }
}

/**
 * BLE 发现的设备
 */
data class BleDiscoveredDevice(
    val device: Device,
    val address: String,
    val rssi: Int,
    val lastSeen: Long,
    val bleDevice: android.bluetooth.BluetoothDevice
)
