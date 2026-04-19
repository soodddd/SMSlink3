package com.smslink.network.transport

import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ConnectionDiagnosticTest {

    private var server: TcpServer? = null
    private var client: TcpClient? = null

    @After
    fun tearDown() {
        client?.close()
        server?.stop()
        Thread.sleep(500) // Give time for cleanup
    }

    @Test
    fun `server emits ClientConnected event`() = runBlocking {
        val port = freePort()
        val server = TcpServer(port = port)
        server.start()
        delay(100)

        println("Server started on port $port")

        try {
            Socket("localhost", port).use { clientSocket ->
                println("Client connected: ${clientSocket.isConnected}")

                val event = withTimeout(5000) {
                    server.connectionFlow.first { it is TcpServer.ConnectionEvent.ClientConnected }
                }

                println("Received event: $event")
                assertTrue(event is TcpServer.ConnectionEvent.ClientConnected)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `connection can be created from socket`() = runBlocking {
        // Create a simple server socket
        val port = freePort()
        val serverSocket = ServerSocket(port)

        // Connect a client in background
        launch {
            delay(100)
            Socket("localhost", port).use { }
        }

        // Accept connection
        val clientSocket = serverSocket.accept()
        println("Client socket accepted")

        // Create Connection
        val connection = Connection(clientSocket)
        println("Connection created")

        // Check state
        val state = withTimeout(1000) {
            connection.connectionStateFlow.first()
        }
        println("Connection state: $state")
        assertTrue("Connection should be in Connected state", state is Connection.ConnectionState.Connected)

        connection.close()
        serverSocket.close()
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
