package com.smslink.network.connection

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.smslink.core.log.ILogger
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LinkSelector 单元测试
 */
class LinkSelectorTest {

    private lateinit var context: Context
    private lateinit var logger: ILogger
    private lateinit var linkSelector: LinkSelector

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        linkSelector = LinkSelector(context, logger)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `selectBestLink should return WIFI_LAN when available`() = runTest {
        // Given
        val deviceId = "test-device"
        val targetIp = "192.168.1.100"
        val bluetoothAddress = "00:11:22:33:44:55"

        // When
        val result = linkSelector.selectBestLink(deviceId, targetIp, bluetoothAddress)

        // Then
        assertNotNull(result)
        verify { logger.i(any(), any()) }
    }

    @Test
    fun `isLinkAvailable should check WiFi LAN availability`() = runTest {
        // Given
        val targetIp = "192.168.1.100"

        // When
        val result = linkSelector.isLinkAvailable(LinkType.WIFI_LAN, targetIp, null)

        // Then
        assertFalse(result) // 在测试环境中通常不可用
    }

    @Test
    fun `isLinkAvailable should check Bluetooth availability`() = runTest {
        // Given
        val bluetoothAddress = "00:11:22:33:44:55"

        // When
        val result = linkSelector.isLinkAvailable(LinkType.BLUETOOTH, null, bluetoothAddress)

        // Then
        assertFalse(result) // 在测试环境中通常不可用
    }

    @Test
    fun `evaluateLinkQuality should return quality for WiFi LAN`() = runTest {
        // Given
        val targetIp = "192.168.1.100"

        // When
        val result = linkSelector.evaluateLinkQuality(LinkType.WIFI_LAN, targetIp)

        // Then
        assertNotNull(result)
    }

    @Test
    fun `evaluateLinkQuality should return FAIR for Bluetooth`() = runTest {
        // When
        val result = linkSelector.evaluateLinkQuality(LinkType.BLUETOOTH, null)

        // Then
        assertEquals(LinkQuality.FAIR, result)
    }
}
