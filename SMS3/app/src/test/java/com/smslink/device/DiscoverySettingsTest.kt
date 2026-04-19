package com.smslink.device

import org.junit.Test
import org.junit.Assert.*

/**
 * 发现设置单元测试
 */
class DiscoverySettingsTest {

    @Test
    fun `test default settings`() {
        val settings = DiscoverySettings.DEFAULT

        assertEquals(DiscoveryMode.BLE_FIRST, settings.mode)
        assertEquals(5000L, settings.bleScanInterval)
        assertEquals(3000L, settings.udpBroadcastInterval)
        assertEquals(15000L, settings.deviceTimeout)
        assertTrue(settings.autoRetry)
        assertEquals(2000L, settings.retryDelay)
    }

    @Test
    fun `test BLE only settings`() {
        val settings = DiscoverySettings.BLE_ONLY

        assertEquals(DiscoveryMode.BLE_ONLY, settings.mode)
        assertTrue(settings.isBleEnabled())
        assertFalse(settings.isUdpEnabled())
        assertTrue(settings.isBlePriority())
    }

    @Test
    fun `test UDP only settings`() {
        val settings = DiscoverySettings.UDP_ONLY

        assertEquals(DiscoveryMode.UDP_ONLY, settings.mode)
        assertFalse(settings.isBleEnabled())
        assertTrue(settings.isUdpEnabled())
        assertFalse(settings.isBlePriority())
    }

    @Test
    fun `test both mode settings`() {
        val settings = DiscoverySettings.BOTH

        assertEquals(DiscoveryMode.BOTH, settings.mode)
        assertTrue(settings.isBleEnabled())
        assertTrue(settings.isUdpEnabled())
        assertFalse(settings.isBlePriority())
    }

    @Test
    fun `test BLE first mode`() {
        val settings = DiscoverySettings(mode = DiscoveryMode.BLE_FIRST)

        assertTrue(settings.isBleEnabled())
        assertTrue(settings.isUdpEnabled())
        assertTrue(settings.isBlePriority())
    }

    @Test
    fun `test custom settings`() {
        val settings = DiscoverySettings(
            mode = DiscoveryMode.BOTH,
            bleScanInterval = 10000L,
            udpBroadcastInterval = 5000L,
            deviceTimeout = 20000L,
            autoRetry = false,
            retryDelay = 3000L
        )

        assertEquals(DiscoveryMode.BOTH, settings.mode)
        assertEquals(10000L, settings.bleScanInterval)
        assertEquals(5000L, settings.udpBroadcastInterval)
        assertEquals(20000L, settings.deviceTimeout)
        assertFalse(settings.autoRetry)
        assertEquals(3000L, settings.retryDelay)
    }

    @Test
    fun `test settings copy`() {
        val original = DiscoverySettings.DEFAULT
        val modified = original.copy(mode = DiscoveryMode.UDP_ONLY)

        assertEquals(DiscoveryMode.BLE_FIRST, original.mode)
        assertEquals(DiscoveryMode.UDP_ONLY, modified.mode)
        assertEquals(original.bleScanInterval, modified.bleScanInterval)
    }

    @Test
    fun `test all discovery modes`() {
        val modes = listOf(
            DiscoveryMode.BLE_ONLY,
            DiscoveryMode.UDP_ONLY,
            DiscoveryMode.BLE_FIRST,
            DiscoveryMode.BOTH
        )

        assertEquals(4, modes.size)
        assertTrue(modes.contains(DiscoveryMode.BLE_ONLY))
        assertTrue(modes.contains(DiscoveryMode.UDP_ONLY))
        assertTrue(modes.contains(DiscoveryMode.BLE_FIRST))
        assertTrue(modes.contains(DiscoveryMode.BOTH))
    }
}
