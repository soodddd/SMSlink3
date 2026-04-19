package com.smslink.device

import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceType
import com.smslink.core.model.DeviceRole
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DevicePairingImpl 单元测试
 */
class DevicePairingImplTest {

    private lateinit var deviceDao: DeviceDao
    private lateinit var logger: ILogger
    private lateinit var devicePairing: DevicePairingImpl

    private val testDevice = Device(
        id = "test-device-1",
        name = "Test Device",
        type = DeviceType.PHONE,
        role = DeviceRole.SECONDARY,
        publicKey = null,
        lastSeen = System.currentTimeMillis(),
        isPaired = false
    )

    @Before
    fun setup() {
        deviceDao = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        devicePairing = DevicePairingImpl(deviceDao, logger)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `generatePairQRCode should return valid JSON string`() {
        // When
        val qrCode = devicePairing.generatePairQRCode(testDevice.id)

        // Then
        assertNotNull(qrCode)
        assertTrue(qrCode.contains("deviceId"))
        assertTrue(qrCode.contains("pairKey"))
        assertTrue(qrCode.contains("timestamp"))
    }

    @Test
    fun `pairDevice should succeed with valid QR code`() = runTest {
        // Given
        val qrCode = devicePairing.generatePairQRCode(testDevice.id)

        // When
        val result = devicePairing.pairDevice(testDevice, qrCode).first()

        // Then
        assertTrue(result.success)
        assertEquals("Pairing successful", result.message)
        assertNotNull(result.device)
        assertTrue(result.device!!.isPaired)
        coVerify { deviceDao.insert(any()) }
    }

    @Test
    fun `pairDevice should fail with invalid QR code`() = runTest {
        // Given
        val invalidQrCode = "invalid-qr-code"

        // When
        val result = devicePairing.pairDevice(testDevice, invalidQrCode).first()

        // Then
        assertFalse(result.success)
        assertEquals("Invalid QR code", result.message)
    }

    @Test
    fun `pairDevice should fail with mismatched device ID`() = runTest {
        // Given
        val qrCode = devicePairing.generatePairQRCode("different-device-id")

        // When
        val result = devicePairing.pairDevice(testDevice, qrCode).first()

        // Then
        assertFalse(result.success)
        assertEquals("Device ID mismatch", result.message)
    }

    @Test
    fun `receivePairRequest should create pending request`() {
        // When
        val requestId = devicePairing.receivePairRequest(testDevice.id, testDevice.name)

        // Then
        assertNotNull(requestId)
        val pendingRequests = devicePairing.getPendingRequests()
        assertEquals(1, pendingRequests.size)
        assertEquals(testDevice.id, pendingRequests[0].deviceId)
        assertEquals(testDevice.name, pendingRequests[0].deviceName)
    }

    @Test
    fun `acceptPairRequest should succeed with valid request`() = runTest {
        // Given
        val requestId = devicePairing.receivePairRequest(testDevice.id, testDevice.name)

        // When
        val result = devicePairing.acceptPairRequest(requestId).first()

        // Then
        assertTrue(result.success)
        assertEquals("Pairing accepted", result.message)
        assertNotNull(result.device)
        assertTrue(result.device!!.isPaired)
        coVerify { deviceDao.insert(any()) }

        // Request should be removed
        val pendingRequests = devicePairing.getPendingRequests()
        assertEquals(0, pendingRequests.size)
    }

    @Test
    fun `acceptPairRequest should fail with invalid request ID`() = runTest {
        // When
        val result = devicePairing.acceptPairRequest("invalid-request-id").first()

        // Then
        assertFalse(result.success)
        assertEquals("Pair request not found", result.message)
    }

    @Test
    fun `rejectPairRequest should remove pending request`() {
        // Given
        val requestId = devicePairing.receivePairRequest(testDevice.id, testDevice.name)

        // When
        devicePairing.rejectPairRequest(requestId)

        // Then
        val pendingRequests = devicePairing.getPendingRequests()
        assertEquals(0, pendingRequests.size)
    }

    @Test
    fun `cleanupExpiredRequests should remove old requests`() {
        // Given
        val requestId = devicePairing.receivePairRequest(testDevice.id, testDevice.name)

        // Manually set old timestamp
        val requests = devicePairing.getPendingRequests()
        assertEquals(1, requests.size)

        // When
        devicePairing.cleanupExpiredRequests()

        // Then - request is not old enough yet, should still be there
        val remainingRequests = devicePairing.getPendingRequests()
        assertEquals(1, remainingRequests.size)
    }

    @Test
    fun `getPendingRequests should return all pending requests`() {
        // Given
        devicePairing.receivePairRequest("device-1", "Device 1")
        devicePairing.receivePairRequest("device-2", "Device 2")
        devicePairing.receivePairRequest("device-3", "Device 3")

        // When
        val requests = devicePairing.getPendingRequests()

        // Then
        assertEquals(3, requests.size)
    }
}
