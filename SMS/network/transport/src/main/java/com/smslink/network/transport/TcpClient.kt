package com.smslink.network.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TcpClient(
    private val host: String,
    private val port: Int = TcpServer.DEFAULT_PORT,
    private val autoReconnect: Boolean = true,
    private val reconnectDelayMs: Long = 5000L,
    private val maxReconnectAttempts: Int = Int.MAX_VALUE
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val userInitiatedDisconnect = AtomicBoolean(false)

    private var connection: Connection? = null
    private var reconnectJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var messageForwardJob: Job? = null

    private val _connectionStateFlow = MutableSharedFlow<ClientState>(replay = 1, extraBufferCapacity = 0)
    val connectionStateFlow: SharedFlow<ClientState> = _connectionStateFlow.asSharedFlow()

    private val _messageFlow = MutableSharedFlow<com.smslink.network.protocol.Message>(replay = 0, extraBufferCapacity = 64)
    val messageFlow: SharedFlow<com.smslink.network.protocol.Message> = _messageFlow.asSharedFlow()

    val isConnected: Boolean
        get() = connection != null

    suspend fun connect() {
        check(connection == null) { "TcpClient is already connected" }
        check(isConnecting.compareAndSet(false, true)) { "TcpClient is already connecting" }

        try {
            userInitiatedDisconnect.set(false)
            _connectionStateFlow.emit(ClientState.Connecting)

            val socket = Socket(host, port)
            val newConnection = Connection(socket)

            connection = newConnection
            reconnectAttempts.set(0)
            _connectionStateFlow.emit(ClientState.Connected)
            observeConnection(newConnection)
        } catch (e: Exception) {
            _connectionStateFlow.emit(ClientState.Failed(e))
            if (autoReconnect && reconnectAttempts.get() < maxReconnectAttempts) {
                scheduleReconnect()
            }
            throw e
        } finally {
            isConnecting.set(false)
        }
    }

    private fun clearActiveConnection(closeConnection: Boolean) {
        reconnectJob?.cancel()
        connectionMonitorJob?.cancel()
        messageForwardJob?.cancel()

        val activeConnection = connection
        connection = null

        if (closeConnection) {
            activeConnection?.close()
        }
    }

    private fun observeConnection(activeConnection: Connection) {
        clearActiveConnection(closeConnection = false)
        connection = activeConnection

        messageForwardJob = scope.launch {
            activeConnection.messageFlow.collect { _messageFlow.emit(it) }
        }

        connectionMonitorJob = scope.launch {
            val state = activeConnection.connectionStateFlow.first {
                it is Connection.ConnectionState.Disconnected
            } as Connection.ConnectionState.Disconnected

            clearActiveConnection(closeConnection = false)
            _connectionStateFlow.emit(ClientState.Disconnected(state.cause))

            if (!userInitiatedDisconnect.get() &&
                autoReconnect &&
                reconnectAttempts.get() < maxReconnectAttempts
            ) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = reconnectAttempts.incrementAndGet()
            _connectionStateFlow.emit(ClientState.Reconnecting(attempt))

            delay(reconnectDelayMs)

            if (isActive && !isConnected) {
                try {
                    connect()
                } catch (e: Exception) {
                    // Will be handled by connect() method
                }
            }
        }
    }

    suspend fun sendMessage(message: com.smslink.network.protocol.Message) {
        val conn = connection ?: throw IOException("Not connected")
        conn.sendMessage(message)
    }

    fun disconnect() {
        userInitiatedDisconnect.set(true)
        clearActiveConnection(closeConnection = true)
        scope.launch {
            _connectionStateFlow.emit(ClientState.Disconnected(IOException("Disconnected by user")))
        }
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    sealed class ClientState {
        object Connecting : ClientState()
        object Connected : ClientState()
        data class Reconnecting(val attempt: Int) : ClientState()
        data class Disconnected(val cause: Exception) : ClientState()
        data class Failed(val cause: Exception) : ClientState()
    }
}
