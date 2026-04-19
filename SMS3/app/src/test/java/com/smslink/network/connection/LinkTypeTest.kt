package com.smslink.network.connection

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LinkType 单元测试
 */
class LinkTypeTest {

    @Test
    fun `WIFI_LAN should have priority 1`() {
        assertEquals(1, LinkType.WIFI_LAN.priority)
    }

    @Test
    fun `WIFI_HOTSPOT should have priority 2`() {
        assertEquals(2, LinkType.WIFI_HOTSPOT.priority)
    }

    @Test
    fun `BLUETOOTH should have priority 3`() {
        assertEquals(3, LinkType.BLUETOOTH.priority)
    }

    @Test
    fun `WIFI_LAN should have higher priority than WIFI_HOTSPOT`() {
        assertTrue(LinkType.WIFI_LAN.hasHigherPriorityThan(LinkType.WIFI_HOTSPOT))
    }

    @Test
    fun `WIFI_LAN should have higher priority than BLUETOOTH`() {
        assertTrue(LinkType.WIFI_LAN.hasHigherPriorityThan(LinkType.BLUETOOTH))
    }

    @Test
    fun `WIFI_HOTSPOT should have higher priority than BLUETOOTH`() {
        assertTrue(LinkType.WIFI_HOTSPOT.hasHigherPriorityThan(LinkType.BLUETOOTH))
    }

    @Test
    fun `BLUETOOTH should not have higher priority than WIFI_LAN`() {
        assertFalse(LinkType.BLUETOOTH.hasHigherPriorityThan(LinkType.WIFI_LAN))
    }

    @Test
    fun `getAllByPriority should return links in correct order`() {
        val links = LinkType.getAllByPriority()

        assertEquals(3, links.size)
        assertEquals(LinkType.WIFI_LAN, links[0])
        assertEquals(LinkType.WIFI_HOTSPOT, links[1])
        assertEquals(LinkType.BLUETOOTH, links[2])
    }
}
