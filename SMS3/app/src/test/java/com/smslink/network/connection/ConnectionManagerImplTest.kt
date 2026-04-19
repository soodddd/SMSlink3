package com.smslink.network.connection

import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.ConnectionType
import com.smslink.network.encryption.IEncryption
import com.smslink.network.monitor.NetworkMonitor
import com.smslink.network.monitor.NetworkState
import com.smslink.network.monitor.NetworkType
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConnectionManagerImpl 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerImplTest {

    private lateinit var connectionManager: ConnectionManagerImpl
    private lateinit var logger: ILogger
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tcpConnectionFactory: TcpConnectionFactory
    private lateinit var linkSelector: LinkSelector
    private lateinit var encryption: IEncryption

    @Before
    fun setup() {
        logger = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        tcpConnectionFactory = mockk(relaxed = true)
        linkSelector = mockk(relaxed = true)
        encryption = mockk(relaxed = true)

        // Mock network monitor to return WiFi available
        every { networkMonitor.observeNetworkState() } returns flowOf(
            NetworkState.Available(NetworkType.WIFI)
        )
        every { encryption.createSSLContext(any()) } returns SSLContext.getDefault()

        connectionManager = ConnectionManagerImpl(
            logger = logger,
            networkMonitor = networkMonitor,
            tcpConnectionFactory = tcpConnectionFactory,
            linkSelector = linkSelector,
            encryption = encryption
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `connect should establish connection successfully`() = runTest {
        // Given
        val deviceId = "test-device"
        val connectionType = ConnectionType.WIFI
        val mockConnection = mockk<TcpConnection>(relaxed = true)

        every { mockConnection.isConnected() } returns true
        every { mockConnection.getType() } returns connectionType
        coEvery { mockConnection.connect() } just Runs
        every { mockConnection.receiveFlow() } returns flowOf()

        coEvery { tcpConnectionFactory.create(deviceId, connectionType) } returns mockConnection

        // When
        val result = connectionManager.connect(deviceId, connectionType).first()

        // Then
        assertEquals(ConnectionState.CONNECTING, result.state)
        coVerify { tcpConnectionFactory.create(deviceId, connectionType) }
        coVerify { mockConnection.connect() }
    }

    @Test
    fun `sendData should return true when connection exists and is connected`() = runTest {
        // Given
        val deviceId = "test-device"
        val data = "test data".toByteArray()
        val mockConnection = mockk<TcpConnection>(relaxed = true)

        every { mockConnection.isConnected() } returns true
        coEvery { mockConnection.send(data) } just Runs

        // Manually add connection to active connections
        val activeConnectionsField = ConnectionManagerImpl::class.java
            .getDeclaredField("activeConnections")
        activeConnectionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeConnections = activeConnectionsField.get(connectionManager) as java.util.concurrent.ConcurrentHashMap<String, TcpConnection>
        activeConnections[deviceId] = mockConnection

        // When
        val result = connectionManager.sendData(deviceId, data)

        // Then
        assertTrue(result)
        coVerify { mockConnection.send(data) }
    }

    @Test
    fun `sendData should return false when connection does not exist`() = runTest {
        // Given
        val deviceId = "non-existent-device"
        val data = "test data".toByteArray()

        // When
        val result = connectionManager.sendData(deviceId, data)

        // Then
        assertFalse(result)
    }

    @Test
    fun `disconnect should close connection and remove from active connections`() = runTest {
        // Given
        val deviceId = "test-device"
        val mockConnection = mockk<TcpConnection>(relaxed = true)

        every { mockConnection.close() } just Runs

        // Manually add connection to active connections
        val activeConnectionsField = ConnectionManagerImpl::class.java
            .getDeclaredField("activeConnections")
        activeConnectionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeConnections = activeConnectionsField.get(connectionManager) as java.util.concurrent.ConcurrentHashMap<String, TcpConnection>
        activeConnections[deviceId] = mockConnection

        // When
        connectionManager.disconnect(deviceId)

        // Then
        verify { mockConnection.close() }
        assertFalse(activeConnections.containsKey(deviceId))
    }
}
