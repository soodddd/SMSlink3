package com.smslink.network.transport

import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class TcpTransportTest {

    private var server: TcpServer? = null
    private var client: TcpClient? = null

    @Before
    fun setup() {
        // Clean up any existing instances
        server?.stop()
        client?.close()
    }

    @After
    fun tearDown() {
        client?.close()
        server?.stop()
    }

    @Test
    fun `server starts and stops successfully`() = runBlocking {
        val server = TcpServer(port = freePort())
        server.start()

        // Give server time to start
        delay(100)

        server.stop()
    }

    @Test
    fun `client connects to server successfully`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)

        withTimeout(5000) {
            client.connect()
            assertTrue(client.isConnected)
        }

        client.close()
        server.stop()
    }

    @Test
    fun `client and server exchange messages`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        // Wait for server to accept connection
        val connectionEvent = withTimeout(5000) {
            server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
        } as TcpServer.ConnectionEvent.ClientConnected

        val serverConnection = connectionEvent.connection

        val receivedMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                serverConnection.messageFlow.first()
            }
        }

        // Client sends message to server
        val clientMessage = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0,
            messageId = 12345L,
            payload = "Hello Server".toByteArray()
        )

        client.sendMessage(clientMessage)

        // Server receives message
        val receivedMessage = receivedMessageDeferred.await()

        assertEquals(clientMessage.type, receivedMessage.type)
        assertEquals(clientMessage.messageId, receivedMessage.messageId)
        assertArrayEquals(clientMessage.payload, receivedMessage.payload)

        val receivedByClientDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                client.messageFlow.first()
            }
        }

        // Server sends message to client
        val serverMessage = Message(
            type = MessageType.DEVICE_PAIR_RESPONSE,
            flags = 0,
            messageId = 67890L,
            payload = "Hello Client".toByteArray()
        )

        serverConnection.sendMessage(serverMessage)

        // Client receives message
        val receivedByClient = receivedByClientDeferred.await()

        assertEquals(serverMessage.type, receivedByClient.type)
        assertEquals(serverMessage.messageId, receivedByClient.messageId)
        assertArrayEquals(serverMessage.payload, receivedByClient.payload)

        client.close()
        server.stop()
    }

    @Test
    fun `server handles multiple clients`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port, maxConnections = 3)
        server.start()
        delay(100)

        val client1 = TcpClient(host = "localhost", port = port, autoReconnect = false)
        val client2 = TcpClient(host = "localhost", port = port, autoReconnect = false)

        client1.connect()
        client2.connect()
        delay(200)

        assertEquals(2, server.getAllConnections().size)

        client1.close()
        client2.close()
        server.stop()
    }

    @Test
    fun `client reconnects after disconnection`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(
            host = "localhost",
            port = port,
            autoReconnect = true,
            reconnectDelayMs = 1000,
            maxReconnectAttempts = 3
        )

        client.connect()
        delay(100)
        assertTrue(client.isConnected)

        // Simulate disconnection by stopping server
        server.stop()
        delay(200)
        assertFalse(client.isConnected)

        // Restart server
        val newServer = TcpServer(port = port)
        newServer.start()
        delay(1500) // Wait for reconnect attempt

        assertTrue(client.isConnected)

        client.close()
        newServer.stop()
    }

    @Test
    fun `connection handles large messages`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        val connectionEvent = withTimeout(5000) {
            server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
        } as TcpServer.ConnectionEvent.ClientConnected

        val serverConnection = connectionEvent.connection

        val receivedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) {
                serverConnection.messageFlow.first()
            }
        }

        // Send large message (100KB)
        val largePayload = ByteArray(100 * 1024) { (it % 256).toByte() }
        val largeMessage = Message(
            type = MessageType.FILE_TRANSFER_DATA,
            flags = 0,
            messageId = 99999L,
            payload = largePayload
        )

        client.sendMessage(largeMessage)

        val received = receivedDeferred.await()

        assertEquals(largeMessage.type, received.type)
        assertEquals(largeMessage.messageId, received.messageId)
        assertArrayEquals(largeMessage.payload, received.payload)

        client.close()
        server.stop()
    }

    @Test
    fun `heartbeat keeps connection alive`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        assertTrue(client.isConnected)

        // Wait for multiple heartbeat intervals
        delay(35000) // Wait 35 seconds (heartbeat is 30s)

        // Connection should still be alive
        assertTrue(client.isConnected)

        client.close()
        server.stop()
    }

    // Regression tests for AI_REVISION_SPEC3.md fixes

    @Test
    fun `connection rejects negative payload length`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        val connectionEvent = withTimeout(5000) {
            server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
        } as TcpServer.ConnectionEvent.ClientConnected

        val serverConnection = connectionEvent.connection

        // Send malformed frame with negative payload length
        val malformedFrame = ByteArray(20)
        malformedFrame[0] = 0x53 // Magic byte 1
        malformedFrame[1] = 0x4C // Magic byte 2
        malformedFrame[2] = 0x01 // Version
        malformedFrame[3] = MessageType.DEVICE_DISCOVERY.value.toByte()
        malformedFrame[4] = 0x00 // Flags
        // Bytes 5-7: padding (already 0)
        // Bytes 8-11: negative length (-1)
        malformedFrame[8] = 0xFF.toByte()
        malformedFrame[9] = 0xFF.toByte()
        malformedFrame[10] = 0xFF.toByte()
        malformedFrame[11] = 0xFF.toByte()
        // Bytes 12-19: message ID (already 0)

        // Write directly to socket
        serverConnection.javaClass.getDeclaredField("socket").apply {
            isAccessible = true
            val socket = get(serverConnection) as java.net.Socket
            socket.getOutputStream().write(malformedFrame)
            socket.getOutputStream().flush()
        }

        // Connection should disconnect due to invalid frame
        delay(500)
        assertFalse(client.isConnected)

        client.close()
        server.stop()
    }

    @Test
    fun `connection rejects oversized payload length`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        val connectionEvent = withTimeout(5000) {
            server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
        } as TcpServer.ConnectionEvent.ClientConnected

        val serverConnection = connectionEvent.connection

        // Send malformed frame with oversized payload length (2MB > 1MB limit)
        val malformedFrame = ByteArray(20)
        malformedFrame[0] = 0x53 // Magic byte 1
        malformedFrame[1] = 0x4C // Magic byte 2
        malformedFrame[2] = 0x01 // Version
        malformedFrame[3] = MessageType.DEVICE_DISCOVERY.value.toByte()
        malformedFrame[4] = 0x00 // Flags
        // Bytes 5-7: padding (already 0)
        // Bytes 8-11: 2MB length
        val oversizedLength = 2 * 1024 * 1024
        malformedFrame[8] = (oversizedLength shr 24).toByte()
        malformedFrame[9] = (oversizedLength shr 16).toByte()
        malformedFrame[10] = (oversizedLength shr 8).toByte()
        malformedFrame[11] = oversizedLength.toByte()
        // Bytes 12-19: message ID (already 0)

        // Write directly to socket
        serverConnection.javaClass.getDeclaredField("socket").apply {
            isAccessible = true
            val socket = get(serverConnection) as java.net.Socket
            socket.getOutputStream().write(malformedFrame)
            socket.getOutputStream().flush()
        }

        // Connection should disconnect due to invalid frame
        delay(500)
        assertFalse(client.isConnected)

        client.close()
        server.stop()
    }

    @Test
    fun `client rejects duplicate connect calls`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
        client.connect()
        delay(100)

        assertTrue(client.isConnected)

        // Attempt to connect again should fail
        try {
            client.connect()
            fail("Expected IllegalStateException for duplicate connect")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("already connected") == true)
        }

        client.close()
        server.stop()
    }

    @Test
    fun `client does not auto-reconnect after manual disconnect`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(
            host = "localhost",
            port = port,
            autoReconnect = true,
            reconnectDelayMs = 500,
            maxReconnectAttempts = 5
        )

        client.connect()
        delay(100)
        assertTrue(client.isConnected)

        // Manual disconnect
        client.disconnect()
        delay(100)
        assertFalse(client.isConnected)

        // Wait for potential reconnect attempts
        delay(2000)

        // Should still be disconnected
        assertFalse(client.isConnected)

        client.close()
        server.stop()
    }

    @Test
    fun `server does not leak monitor coroutines after disconnect`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        // Connect and disconnect multiple clients
        repeat(5) {
            val client = TcpClient(host = "localhost", port = port, autoReconnect = false)
            client.connect()
            delay(100)
            client.disconnect()
            delay(100)
        }

        // All connections should be cleaned up
        assertEquals(0, server.getAllConnections().size)

        server.stop()
    }

    @Test
    fun `client does not leak monitor coroutines after disconnect`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        val client = TcpClient(host = "localhost", port = port, autoReconnect = false)

        // Connect and disconnect multiple times
        repeat(3) {
            client.connect()
            delay(100)
            assertTrue(client.isConnected)
            client.disconnect()
            delay(100)
            assertFalse(client.isConnected)
        }

        client.close()
        server.stop()
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
