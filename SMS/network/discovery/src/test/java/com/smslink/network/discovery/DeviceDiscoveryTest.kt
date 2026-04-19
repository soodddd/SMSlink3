package com.smslink.network.discovery

import com.smslink.core.model.DeviceCapability
import com.smslink.core.model.DeviceInfo
import com.smslink.core.model.DeviceType
import org.junit.Test
import org.junit.Assert.*

class DeviceDiscoveryTest {

    @Test
    fun `device info creation`() {
        val deviceInfo = DeviceInfo(
            deviceId = "test-device-id",
            deviceName = "Test Device",
            deviceType = DeviceType.PHONE,
            capabilities = setOf(DeviceCapability.NOTIFICATION, DeviceCapability.CALL),
            ipAddress = "192.168.1.100",
            port = 8888
        )

        assertEquals("test-device-id", deviceInfo.deviceId)
        assertEquals("Test Device", deviceInfo.deviceName)
        assertEquals(DeviceType.PHONE, deviceInfo.deviceType)
        assertEquals(2, deviceInfo.capabilities.size)
        assertTrue(deviceInfo.capabilities.contains(DeviceCapability.NOTIFICATION))
        assertTrue(deviceInfo.capabilities.contains(DeviceCapability.CALL))
        assertEquals("192.168.1.100", deviceInfo.ipAddress)
        assertEquals(8888, deviceInfo.port)
    }

    @Test
    fun `device type enum values`() {
        assertEquals(2, DeviceType.values().size)
        assertNotNull(DeviceType.valueOf("PHONE"))
        assertNotNull(DeviceType.valueOf("TABLET"))
    }

    @Test
    fun `device capability enum values`() {
        assertEquals(3, DeviceCapability.values().size)
        assertNotNull(DeviceCapability.valueOf("NOTIFICATION"))
        assertNotNull(DeviceCapability.valueOf("CALL"))
        assertNotNull(DeviceCapability.valueOf("TRANSFER"))
    }
}
