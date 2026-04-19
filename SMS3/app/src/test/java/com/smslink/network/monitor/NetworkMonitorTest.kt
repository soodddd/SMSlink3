package com.smslink.network.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.smslink.core.log.ILogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NetworkMonitor 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var logger: ILogger

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        networkMonitor = NetworkMonitor(
            context = context,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `isWifiConnected should return true when WiFi is connected`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        // When
        val result = networkMonitor.isWifiConnected()

        // Then
        assertTrue(result)
    }

    @Test
    fun `isWifiConnected should return false when WiFi is not connected`() {
        // Given
        every { connectivityManager.activeNetwork } returns null

        // When
        val result = networkMonitor.isWifiConnected()

        // Then
        assertFalse(result)
    }

    @Test
    fun `isCellularConnected should return true when cellular is connected`() {
        // Given
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()

        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true

        // When
        val result = networkMonitor.isCellularConnected()

        // Then
        assertTrue(result)
    }

    @Test
    fun `getCurrentNetworkState should return Lost when no network available`() {
        // Given
        every { connectivityManager.activeNetwork } returns null

        // When
        val result = networkMonitor.getCurrentNetworkState()

        // Then
        assertTrue(result is NetworkState.Lost)
    }
}
