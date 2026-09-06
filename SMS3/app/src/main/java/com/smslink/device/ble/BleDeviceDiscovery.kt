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
import kotlinx.coroutines.flow.update
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
            // Resolve the scanner at start time. Some devices expose it only
            // after Bluetooth has been enabled, while this singleton may have
            // been created earlier during application startup.
            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                _scanError.value = "BLE 扫描器不可用"
                _isScanning.value = false
                return
            }

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
                scanner.startScan(scanFilters, scanSettings, scanCallback)
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
                    // stopScan() cancels scanJob, which is this coroutine.
                    // Stop only the hardware scan here so the restart is not
                    // cancelled before startScan() gets a chance to run.
                    stopHardwareScan()
                    _isScanning.value = false
                    scanJob = null
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
            _isScanning.value = false
            scanJob?.cancel()
            timeoutJob?.cancel()
            scanJob = null
            timeoutJob = null
        }
    }

    /**
     * 停止 BLE 扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value && scanJob == null && timeoutJob == null) {
            return
        }

        logger.i(TAG, "Stopping BLE scan")

        stopHardwareScan()

        scanJob?.cancel()
        timeoutJob?.cancel()
        _isScanning.value = false
        scanJob = null
        timeoutJob = null
    }

    @SuppressLint("MissingPermission")
    private fun stopHardwareScan() {
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            logger.e(TAG, "Error stopping scan", e)
        }
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
            scanJob?.cancel()
            timeoutJob?.cancel()
            scanJob = null
            timeoutJob = null
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

            _discoveredDevices.update { existingDevices ->
                val currentDevices = existingDevices.toMutableList()
                val existingIndex = currentDevices.indexOfFirst { it.address == deviceAddress }
                if (existingIndex >= 0) {
                    currentDevices[existingIndex] = discoveredDevice
                } else {
                    currentDevices.add(discoveredDevice)
                    logger.i(TAG, "New BLE device discovered: ${discoveredDevice.device.name}")
                }
                currentDevices
            }

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
        _discoveredDevices.update { devices ->
            val activeDevices = devices.filter {
                currentTime - it.lastSeen < BleConstants.DEVICE_TIMEOUT
            }

            if (activeDevices.size != devices.size) {
                val removed = devices.size - activeDevices.size
                logger.d(TAG, "Removed $removed stale BLE devices")
            }
            activeDevices
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
    @SuppressLint("MissingPermission")
    fun isBluetoothAvailable(): Boolean {
        // Permission is checked by startScan()/DeviceDiscoveryImpl before a
        // scan is started. Keep this method focused on adapter availability;
        // callers also use it for UI state and should not see "disabled"
        // merely because a runtime permission has not been granted yet.
        return runCatching { bluetoothAdapter != null && bluetoothAdapter.isEnabled }
            .getOrDefault(false)
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
