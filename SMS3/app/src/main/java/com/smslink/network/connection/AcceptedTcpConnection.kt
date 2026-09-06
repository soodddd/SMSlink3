package com.smslink.network.connection

import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLSocket

class AcceptedTcpConnection(
    private val deviceId: String,
    private val socket: Socket,
    private val logger: ILogger
) : Connection {

    @Volatile
    private var connected = true

    @Volatile
    private var tlsHandshakeComplete = false

    private val inputStream = BufferedInputStream(socket.inputStream)
    private val outputStream = BufferedOutputStream(socket.outputStream)
    private val writeMutex = Mutex()

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (!socket.isConnected || socket.isClosed) {
            connected = false
            throw IOException("Socket is not connected")
        }

        // ConnectionManagerImpl accepts an SSLSocket from the TLS server
        // socket. The server handshake must complete before the application
        // frame header is read; otherwise the TLS record bytes would be
        // misinterpreted as a FrameCodec length.
        (socket as? SSLSocket)?.let { sslSocket ->
            if (!tlsHandshakeComplete) {
                sslSocket.useClientMode = false
                sslSocket.soTimeout = AUTH_READ_TIMEOUT_MS
                sslSocket.startHandshake()
                tlsHandshakeComplete = true
            }
        }

        // Keep a bounded read timeout so a stalled authentication cannot hold
        // a listener coroutine forever. receiveFlow treats this timeout as an
        // idle poll; application heartbeats keep the connection alive.
        socket.soTimeout = AUTH_READ_TIMEOUT_MS
        connected = true
    }

    override suspend fun receiveOne(): ByteArray = withContext(Dispatchers.IO) {
        if (!isConnected()) throw IOException("Not connected")
        try {
            FrameCodec.readFrame(inputStream)
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }

    override suspend fun send(data: ByteArray) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!connected) {
                throw IOException("Not connected")
            }

            try {
                FrameCodec.writeFrame(outputStream, data)
                logger.d(TAG, "Sent ${data.size} bytes to inbound peer: $deviceId")
            } catch (e: Exception) {
                connected = false
                logger.e(TAG, "Failed to send data to inbound peer: $deviceId", e)
                throw e
            }
        }
    }

    override fun receiveFlow(): Flow<ByteArray> = callbackFlow {
        try {
            // Authentication uses a bounded read timeout. Once authenticated,
            // retrying after a timeout could consume the tail of a partial
            // frame as a new length header.
            socket.soTimeout = 0
            while (isActive && connected) {
                try {
                    val data = FrameCodec.readFrame(inputStream)

                    logger.d(TAG, "Received ${data.size} bytes from inbound peer: $deviceId")
                    trySend(data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    connected = false
                    if (isExpectedClose(e)) {
                        logger.i(TAG, "Inbound TCP peer closed connection: $deviceId")
                    } else {
                        logger.e(TAG, "Inbound TCP receive failed for peer: $deviceId", e)
                    }
                    close(e)
                    break
                }
            }
            if (!isActive || !connected) close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connected = false
            if (isExpectedClose(e)) {
                logger.i(TAG, "Inbound TCP peer closed connection: $deviceId")
            } else {
                logger.e(TAG, "Inbound TCP receive failed for peer: $deviceId", e)
            }
            close(e)
        }

        awaitClose {
            this@AcceptedTcpConnection.close()
        }
    }

    override fun isConnected(): Boolean = connected && socket.isConnected && !socket.isClosed

    override fun getType(): ConnectionType = ConnectionType.WIFI

    override fun getLinkType(): LinkType = LinkType.WIFI_LAN

    override fun close() {
        connected = false
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
        logger.i(TAG, "Inbound TCP connection closed for peer: $deviceId")
    }

    companion object {
        private const val TAG = "AcceptedTcpConnection"
        private const val MAX_MESSAGE_SIZE = 100 * 1024 * 1024
        private const val AUTH_READ_TIMEOUT_MS = 10_000

        private fun isExpectedClose(error: Exception): Boolean {
            return error is SocketException ||
                (error is IOException && error.message == "End of stream")
        }
    }
}
