package com.smslink.device.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.smslink.core.log.ILogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * BLE 设备发现单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleDeviceDiscoveryTest {

    private lateinit var context: Context
    private lateinit var permissionHelper: BlePermissionHelper
    private lateinit var logger: ILogger
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleDeviceDiscovery: BleDeviceDiscovery

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        permissionHelper = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        bluetoothManager = mockk(relaxed = true)
        bluetoothAdapter = mockk(relaxed = true)

        // Mock system services
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.isEnabled } returns true
        every { bluetoothAdapter.bluetoothLeScanner } returns mockk(relaxed = true)

        bleDeviceDiscovery = BleDeviceDiscovery(context, permissionHelper, logger)
    }

    @After
    fun teardown() {
        bleDeviceDiscovery.cleanup()
        clearAllMocks()
    }

    @Test
    fun `test initial state`() = runTest {
        // 初始状态应该是未扫描
        assertFalse(bleDeviceDiscovery.isScanning.first())
        assertTrue(bleDeviceDiscovery.discoveredDevices.first().isEmpty())
        assertNull(bleDeviceDiscovery.scanError.first())
    }

    @Test
    fun `test start scan without permissions`() = runTest {
        // 模拟没有权限
        every { permissionHelper.hasAllBlePermissions() } returns false

        bleDeviceDiscovery.startScan()

        // 应该不会开始扫描
        assertFalse(bleDeviceDiscovery.isScanning.first())
        assertNotNull(bleDeviceDiscovery.scanError.first())
    }

    @Test
    fun `test start scan with permissions`() = runTest {
        // 模拟有权限
        every { permissionHelper.hasAllBlePermissions() } returns true

        bleDeviceDiscovery.startScan()

        // 应该开始扫描
        assertTrue(bleDeviceDiscovery.isScanning.first())
        assertNull(bleDeviceDiscovery.scanError.first())
    }

    @Test
    fun `test start scan when bluetooth disabled`() = runTest {
        // 模拟蓝牙未开启
        every { permissionHelper.hasAllBlePermissions() } returns true
        every { bluetoothAdapter.isEnabled } returns false

        bleDeviceDiscovery.startScan()

        // 应该不会开始扫描
        assertFalse(bleDeviceDiscovery.isScanning.first())
        assertNotNull(bleDeviceDiscovery.scanError.first())
    }

    @Test
    fun `test stop scan`() = runTest {
        // 模拟有权限并开始扫描
        every { permissionHelper.hasAllBlePermissions() } returns true

        bleDeviceDiscovery.startScan()
        assertTrue(bleDeviceDiscovery.isScanning.first())

        bleDeviceDiscovery.stopScan()

        // 应该停止扫描
        assertFalse(bleDeviceDiscovery.isScanning.first())
    }

    @Test
    fun `test clear devices`() = runTest {
        bleDeviceDiscovery.clearDevices()

        // 设备列表应该为空
        assertTrue(bleDeviceDiscovery.discoveredDevices.first().isEmpty())
    }

    @Test
    fun `test bluetooth availability`() {
        every { bluetoothAdapter.isEnabled } returns true
        assertTrue(bleDeviceDiscovery.isBluetoothAvailable())

        every { bluetoothAdapter.isEnabled } returns false
        assertFalse(bleDeviceDiscovery.isBluetoothAvailable())
    }

    @Test
    fun `test scan already running`() = runTest {
        every { permissionHelper.hasAllBlePermissions() } returns true

        bleDeviceDiscovery.startScan()
        assertTrue(bleDeviceDiscovery.isScanning.first())

        // 再次启动扫描应该被忽略
        bleDeviceDiscovery.startScan()

        verify(exactly = 1) { logger.d(any(), "BLE scan already running") }
    }
}
