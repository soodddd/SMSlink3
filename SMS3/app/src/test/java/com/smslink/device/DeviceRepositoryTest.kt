package com.smslink.device

import com.smslink.core.database.dao.DeviceDao
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DeviceRepository 单元测试
 */
class DeviceRepositoryTest {

    private lateinit var deviceDao: DeviceDao
    private lateinit var logger: ILogger
    private lateinit var repository: DeviceRepository

    private val testDevice = Device(
        id = "test-device-1",
        name = "Test Device",
        type = DeviceType.PHONE,
        role = DeviceRole.SECONDARY,
        publicKey = "test-public-key",
        lastSeen = System.currentTimeMillis(),
        isPaired = true
    )

    @Before
    fun setup() {
        deviceDao = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        repository = DeviceRepository(deviceDao, logger)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `saveDevice should insert device into database`() = runTest {
        // When
        repository.saveDevice(testDevice)

        // Then
        coVerify { deviceDao.insert(testDevice) }
        verify { logger.d("DeviceRepository", "Device saved: ${testDevice.name}") }
    }

    @Test
    fun `updateDevice should update device in database`() = runTest {
        // When
        repository.updateDevice(testDevice)

        // Then
        coVerify { deviceDao.update(testDevice) }
        verify { logger.d("DeviceRepository", "Device updated: ${testDevice.name}") }
    }

    @Test
    fun `deleteDevice should delete device from database`() = runTest {
        // When
        repository.deleteDevice(testDevice.id)

        // Then
        coVerify { deviceDao.deleteById(testDevice.id) }
        verify { logger.d("DeviceRepository", "Device deleted: ${testDevice.id}") }
    }

    @Test
    fun `getDeviceById should return device when exists`() = runTest {
        // Given
        coEvery { deviceDao.getById(testDevice.id) } returns testDevice

        // When
        val result = repository.getDeviceById(testDevice.id)

        // Then
        assertEquals(testDevice, result)
    }

    @Test
    fun `getDeviceById should return null when device not found`() = runTest {
        // Given
        coEvery { deviceDao.getById(any()) } returns null

        // When
        val result = repository.getDeviceById("non-existent")

        // Then
        assertEquals(null, result)
    }

    @Test
    fun `getAllPairedDevices should return flow of paired devices`() = runTest {
        // Given
        val devices = listOf(testDevice)
        every { deviceDao.getAllPaired() } returns flowOf(devices)

        // When
        val result = repository.getAllPairedDevices().first()

        // Then
        assertEquals(devices, result)
    }

    @Test
    fun `getConnectedDevices should filter devices by last seen time`() = runTest {
        // Given
        val currentTime = System.currentTimeMillis()
        val connectedDevice = testDevice.copy(lastSeen = currentTime - 5000) // 5 seconds ago
        val disconnectedDevice = testDevice.copy(
            id = "test-device-2",
            lastSeen = currentTime - 15000 // 15 seconds ago
        )
        every { deviceDao.getAllPaired() } returns flowOf(listOf(connectedDevice, disconnectedDevice))

        // When
        val result = repository.getConnectedDevices().first()

        // Then
        assertEquals(1, result.size)
        assertEquals(connectedDevice.id, result[0].id)
    }

    @Test
    fun `updateLastSeen should update device last seen timestamp`() = runTest {
        // When
        repository.updateLastSeen(testDevice.id)

        // Then
        coVerify { deviceDao.updateLastSeen(testDevice.id, any()) }
    }

    @Test
    fun `updateDeviceRole should update device role`() = runTest {
        // Given
        coEvery { deviceDao.getById(testDevice.id) } returns testDevice
        val newRole = DeviceRole.MAIN

        // When
        repository.updateDeviceRole(testDevice.id, newRole)

        // Then
        coVerify { deviceDao.update(match { it.role == newRole }) }
    }

    @Test
    fun `isDevicePaired should return true when device is paired`() = runTest {
        // Given
        coEvery { deviceDao.getById(testDevice.id) } returns testDevice

        // When
        val result = repository.isDevicePaired(testDevice.id)

        // Then
        assertTrue(result)
    }

    @Test
    fun `isDevicePaired should return false when device is not paired`() = runTest {
        // Given
        val unpairedDevice = testDevice.copy(isPaired = false)
        coEvery { deviceDao.getById(testDevice.id) } returns unpairedDevice

        // When
        val result = repository.isDevicePaired(testDevice.id)

        // Then
        assertFalse(result)
    }

    @Test
    fun `isDevicePaired should return false when device not found`() = runTest {
        // Given
        coEvery { deviceDao.getById(any()) } returns null

        // When
        val result = repository.isDevicePaired("non-existent")

        // Then
        assertFalse(result)
    }
}
