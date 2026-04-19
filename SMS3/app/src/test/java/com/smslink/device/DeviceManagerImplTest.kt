package com.smslink.device

import android.content.Context
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * DeviceManagerImpl 单元测试
 */
class DeviceManagerImplTest {

    private lateinit var context: Context
    private lateinit var deviceDiscovery: DeviceDiscoveryImpl
    private lateinit var devicePairing: DevicePairingImpl
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var logger: ILogger
    private lateinit var deviceManager: DeviceManagerImpl

    private val testDevice = Device(
        id = "test-device-1",
        name = "Test Device",
        type = DeviceType.PHONE,
        role = DeviceRole.SECONDARY,
        publicKey = "test-key",
        lastSeen = System.currentTimeMillis(),
        isPaired = true
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        deviceDiscovery = mockk(relaxed = true)
        devicePairing = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        every { deviceRepository.getAllPairedDevices() } returns flowOf(emptyList())

        // Mock Android system services
        val contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { context.resources } returns mockk(relaxed = true)
        every { context.resources.configuration } returns mockk(relaxed = true)

        mockkStatic(android.provider.Settings.Secure::class)
        every {
            android.provider.Settings.Secure.getString(any(), any())
        } returns "test-device-id"

        deviceManager = DeviceManagerImpl(
            context,
            deviceDiscovery,
            devicePairing,
            deviceRepository,
            logger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `startDiscovery should start device discovery`() {
        // When
        deviceManager.startDiscovery()

        // Then
        verify { deviceDiscovery.startDiscovery(any()) }
        verify { logger.i("DeviceManager", "Starting device discovery") }
    }

    @Test
    fun `stopDiscovery should stop device discovery`() {
        // When
        deviceManager.stopDiscovery()

        // Then
        verify { deviceDiscovery.stopDiscovery() }
        verify { logger.i("DeviceManager", "Stopping device discovery") }
    }

    @Test
    fun `pairDevice should initiate pairing with discovered device`() = runTest {
        // Given
        val discoveredDevice = DiscoveredDevice(
            device = testDevice,
            ipAddress = "192.168.1.100",
            lastSeen = System.currentTimeMillis()
        )
        every { deviceDiscovery.discoveredDevices } returns kotlinx.coroutines.flow.MutableStateFlow(
            listOf(discoveredDevice)
        )
        every { devicePairing.pairDevice(any(), any<DiscoveredDevice>(), any<String>()) } returns flowOf(
            com.smslink.core.model.PairResult(true, "Success", testDevice)
        )

        // When
        val result = deviceManager.pairDevice(testDevice.id, "qr-code").first()

        // Then
        assertEquals(true, result.success)
    }

    @Test
    fun `pairDevice should fail when device not found`() = runTest {
        // Given
        every { deviceDiscovery.discoveredDevices } returns kotlinx.coroutines.flow.MutableStateFlow(
            emptyList()
        )

        // When
        val result = deviceManager.pairDevice("non-existent", "qr-code").first()

        // Then
        assertEquals(false, result.success)
        assertEquals("Device not found", result.message)
    }

    @Test
    fun `getConnectedDevices should return connected devices from repository`() = runTest {
        // Given
        every { deviceRepository.getConnectedDevices() } returns flowOf(listOf(testDevice))

        // When
        val result = deviceManager.getConnectedDevices().first()

        // Then
        assertEquals(1, result.size)
        assertEquals(testDevice, result[0])
    }

    @Test
    fun `setDeviceRole should update device role in repository`() = runTest {
        // When
        deviceManager.setDeviceRole(testDevice.id, DeviceRole.MAIN)

        // Then
        coVerify { deviceRepository.updateDeviceRole(testDevice.id, DeviceRole.MAIN) }
    }

    @Test
    fun `setDeviceRole should update cached local role when targeting local device`() = runTest {
        // When
        deviceManager.setDeviceRole("test-device-id", DeviceRole.SECONDARY)

        // Then
        assertEquals(DeviceRole.SECONDARY, deviceManager.getLocalDevice().role)
        coVerify { deviceRepository.updateDeviceRole("test-device-id", DeviceRole.SECONDARY) }
    }

    @Test
    fun `setDeviceRole should demote conflicting main devices when promoting local device`() = runTest {
        // Given
        val conflictingDevice = testDevice.copy(role = DeviceRole.MAIN)
        every { deviceRepository.getAllPairedDevices() } returns flowOf(listOf(conflictingDevice))

        // When
        deviceManager.setDeviceRole("test-device-id", DeviceRole.MAIN)

        // Then
        coVerify { deviceRepository.updateDeviceRole("test-device-id", DeviceRole.MAIN) }
        coVerify { deviceRepository.updateDeviceRole(conflictingDevice.id, DeviceRole.SECONDARY) }
        assertEquals(DeviceRole.MAIN, deviceManager.getLocalDevice().role)
    }

    @Test
    fun `setDeviceRole should demote local exclusive role when remote device becomes main`() = runTest {
        // Given
        every { deviceRepository.getAllPairedDevices() } returns flowOf(emptyList())

        deviceManager.setDeviceRole("test-device-id", DeviceRole.CELLULAR_SOURCE)
        assertEquals(DeviceRole.CELLULAR_SOURCE, deviceManager.getLocalDevice().role)

        // When
        deviceManager.setDeviceRole(testDevice.id, DeviceRole.MAIN)

        // Then
        coVerify { deviceRepository.updateDeviceRole("test-device-id", DeviceRole.CELLULAR_SOURCE) }
        coVerify { deviceRepository.updateDeviceRole(testDevice.id, DeviceRole.MAIN) }
        assertEquals(DeviceRole.SECONDARY, deviceManager.getLocalDevice().role)
    }

    @Test
    fun `removeDevice should delete device from repository`() = runTest {
        // When
        deviceManager.removeDevice(testDevice.id)

        // Then
        coVerify { deviceRepository.deleteDevice(testDevice.id) }
    }

    @Test
    fun `getLocalDevice should return local device info`() {
        // When
        val localDevice = deviceManager.getLocalDevice()

        // Then
        assertNotNull(localDevice)
        assertEquals("test-device-id", localDevice.id)
        assertEquals(DeviceRole.MAIN, localDevice.role)
    }

    @Test
    fun `generatePairQRCode should generate QR code for local device`() {
        // Given
        every { devicePairing.generatePairQRCode(any()) } returns "qr-code-content"

        // When
        val qrCode = deviceManager.generatePairQRCode()

        // Then
        assertEquals("qr-code-content", qrCode)
        verify { devicePairing.generatePairQRCode("test-device-id") }
    }

    @Test
    fun `receivePairRequest should delegate to pairing implementation`() {
        // Given
        every { devicePairing.receivePairRequest(any(), any()) } returns "request-id"

        // When
        val requestId = deviceManager.receivePairRequest("device-id", "Device Name")

        // Then
        assertEquals("request-id", requestId)
        verify { devicePairing.receivePairRequest("device-id", "Device Name") }
    }

    @Test
    fun `acceptPairRequest should delegate to pairing implementation`() = runTest {
        // Given
        every { devicePairing.acceptPairRequest(any()) } returns flowOf(
            com.smslink.core.model.PairResult(true, "Accepted", testDevice)
        )

        // When
        val result = deviceManager.acceptPairRequest("request-id").first()

        // Then
        assertEquals(true, result.success)
        verify { devicePairing.acceptPairRequest("request-id") }
    }

    @Test
    fun `rejectPairRequest should delegate to pairing implementation`() {
        // When
        deviceManager.rejectPairRequest("request-id")

        // Then
        verify { devicePairing.rejectPairRequest("request-id") }
    }
}
