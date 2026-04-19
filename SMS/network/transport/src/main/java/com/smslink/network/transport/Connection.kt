package com.smslink.network.transport

import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageCodec
import com.smslink.network.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Connection(
    private val socket: Socket,
    private val heartbeatIntervalMs: Long = 30_000L,
    private val heartbeatTimeoutMs: Long = 60_000L
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val isConnected = AtomicBoolean(true)
    private val lastHeartbeatTime = AtomicLong(System.currentTimeMillis())

    private val outputStream: OutputStream = socket.getOutputStream()
    private val writeMutex = Mutex()

    private val _messageFlow = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 64)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()

    private val _connectionStateFlow = MutableSharedFlow<ConnectionState>(replay = 1, extraBufferCapacity = 0)
    val connectionStateFlow: SharedFlow<ConnectionState> = _connectionStateFlow.asSharedFlow()

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    val remoteAddress: String
        get() = socket.inetAddress.hostAddress ?: "unknown"

    val remotePort: Int
        get() = socket.port

    init {
        _connectionStateFlow.tryEmit(ConnectionState.Connected)
        startReceiving()
        startHeartbeat()
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            try {
                val inputStream = socket.getInputStream()
                val headerBuffer = ByteArray(Message.HEADER_SIZE)

                while (isActive && isConnected.get()) {
                    // Read header
                    var bytesRead = 0
                    while (bytesRead < Message.HEADER_SIZE) {
                        val count = inputStream.read(headerBuffer, bytesRead, Message.HEADER_SIZE - bytesRead)
                        if (count == -1) {
                            throw IOException("Connection closed by remote")
                        }
                        bytesRead += count
                    }

                    // Parse payload length from header (offset 8 after Magic(2) + Version(1) + Type(1) + Flags(1) + Padding(3))
                    val payloadLength = ByteBuffer.wrap(headerBuffer, 8, Int.SIZE_BYTES)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int

                    // Validate payload length before allocation
                    if (payloadLength < 0 || payloadLength > Message.MAX_PAYLOAD_SIZE) {
                        throw IOException("Invalid payload length: $payloadLength")
                    }

                    // Read complete message
                    val messageData = ByteArray(Message.HEADER_SIZE + payloadLength)
                    System.arraycopy(headerBuffer, 0, messageData, 0, Message.HEADER_SIZE)

                    if (payloadLength > 0) {
                        bytesRead = 0
                        while (bytesRead < payloadLength) {
                            val count = inputStream.read(
                                messageData,
                                Message.HEADER_SIZE + bytesRead,
                                payloadLength - bytesRead
                            )
                            if (count == -1) {
                                throw IOException("Connection closed while reading payload")
                            }
                            bytesRead += count
                        }
                    }

                    // Decode message and fail fast on invalid frames
                    val message = MessageCodec.decode(messageData)
                        .getOrElse { throw IOException("Invalid message frame", it) }

                    // Update heartbeat time for any valid incoming message
                    lastHeartbeatTime.set(System.currentTimeMillis())

                    // Emit non-heartbeat messages
                    if (message.type != MessageType.DEVICE_HEARTBEAT) {
                        _messageFlow.emit(message)
                    }
                }
            } catch (e: Exception) {
                if (isConnected.get()) {
                    handleDisconnection(e)
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(heartbeatIntervalMs)

                // Check if we received heartbeat recently
                val timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime.get()
                if (timeSinceLastHeartbeat > heartbeatTimeoutMs) {
                    handleDisconnection(IOException("Heartbeat timeout"))
                    break
                }

                // Send heartbeat
                try {
                    val heartbeat = Message(
                        type = MessageType.DEVICE_HEARTBEAT,
                        flags = 0,
                        messageId = System.currentTimeMillis(),
                        payload = ByteArray(0)
                    )
                    sendMessage(heartbeat)
                } catch (e: Exception) {
                    handleDisconnection(e)
                    break
                }
            }
        }
    }

    suspend fun sendMessage(message: Message) {
        if (!isConnected.get()) {
            throw IOException("Connection is closed")
        }

        try {
            val encoded = MessageCodec.encode(message)
            writeMutex.withLock {
                outputStream.write(encoded)
                outputStream.flush()
            }
        } catch (e: Exception) {
            handleDisconnection(e)
            throw e
        }
    }

    private fun handleDisconnection(cause: Exception) {
        if (isConnected.compareAndSet(true, false)) {
            _connectionStateFlow.tryEmit(ConnectionState.Disconnected(cause))
            close()
        }
    }

    fun close() {
        isConnected.set(false)
        receiveJob?.cancel()
        heartbeatJob?.cancel()

        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }

        scope.cancel()
    }

    sealed class ConnectionState {
        object Connected : ConnectionState()
        data class Disconnected(val cause: Exception) : ConnectionState()
    }
}
