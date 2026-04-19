package com.smslink.network.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TcpServer(
    private val port: Int = DEFAULT_PORT,
    private val maxConnections: Int = 10
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val connections = ConcurrentHashMap<String, Connection>()
    private val connectionMonitorJobs = ConcurrentHashMap<String, Job>()

    private val _connectionFlow = MutableSharedFlow<ConnectionEvent>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val connectionFlow: SharedFlow<ConnectionEvent> = _connectionFlow.asSharedFlow()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            try {
                serverSocket = ServerSocket(port)
                startAccepting()
            } catch (e: Exception) {
                isRunning.set(false)
                throw IOException("Failed to start server on port $port", e)
            }
        }
    }

    private fun startAccepting() {
        acceptJob = scope.launch {
            val socket = serverSocket ?: return@launch

            while (isActive && isRunning.get()) {
                try {
                    val clientSocket = socket.accept()
                    handleNewConnection(clientSocket)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        // Log error but continue accepting
                    }
                }
            }
        }
    }

    private fun handleNewConnection(socket: Socket) {
        if (connections.size >= maxConnections) {
            socket.close()
            return
        }

        val connectionId = "${socket.inetAddress.hostAddress}:${socket.port}"
        val connection = Connection(socket)

        connections[connectionId] = connection

        connectionMonitorJobs[connectionId] = scope.launch {
            _connectionFlow.emit(ConnectionEvent.ClientConnected(connectionId, connection))

            val state = connection.connectionStateFlow.first {
                it is Connection.ConnectionState.Disconnected
            } as Connection.ConnectionState.Disconnected

            connections.remove(connectionId)
            connectionMonitorJobs.remove(connectionId)
            _connectionFlow.emit(ConnectionEvent.ClientDisconnected(connectionId, state.cause))
        }
    }

    fun getConnection(connectionId: String): Connection? {
        return connections[connectionId]
    }

    fun getAllConnections(): List<Connection> {
        return connections.values.toList()
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            acceptJob?.cancel()
            connectionMonitorJobs.values.forEach(Job::cancel)
            connectionMonitorJobs.clear()
            connections.values.forEach(Connection::close)
            connections.clear()
            serverSocket?.close()
            serverSocket = null
        }
    }

    sealed class ConnectionEvent {
        data class ClientConnected(val connectionId: String, val connection: Connection) : ConnectionEvent()
        data class ClientDisconnected(val connectionId: String, val cause: Exception) : ConnectionEvent()
    }

    companion object {
        const val DEFAULT_PORT = 8888
    }
}
