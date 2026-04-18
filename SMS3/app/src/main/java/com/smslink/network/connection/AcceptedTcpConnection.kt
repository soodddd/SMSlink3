package com.smslink.network.connection

import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException

class AcceptedTcpConnection(
    private val deviceId: String,
    private val socket: Socket,
    private val logger: ILogger
) : Connection {

    @Volatile
    private var connected = true

    private val inputStream = BufferedInputStream(socket.inputStream)
    private val outputStream = BufferedOutputStream(socket.outputStream)

    override suspend fun connect() {
        connected = socket.isConnected && !socket.isClosed
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        if (!connected) {
            throw IOException("Not connected")
        }

        try {
            outputStream.write((data.size shr 24) and 0xFF)
            outputStream.write((data.size shr 16) and 0xFF)
            outputStream.write((data.size shr 8) and 0xFF)
            outputStream.write(data.size and 0xFF)
            outputStream.write(data)
            outputStream.flush()
            logger.d(TAG, "Sent ${data.size} bytes to inbound peer: $deviceId")
        } catch (e: Exception) {
            connected = false
            logger.e(TAG, "Failed to send data to inbound peer: $deviceId", e)
            throw e
        }
    }

    override fun receiveFlow(): Flow<ByteArray> = callbackFlow {
        try {
            while (isActive && connected) {
                val lengthBytes = ByteArray(4)
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead)
                    if (read == -1) {
                        throw IOException("End of stream")
                    }
                    bytesRead += read
                }

                val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                    (lengthBytes[3].toInt() and 0xFF)

                if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                    throw IOException("Invalid message length: $length")
                }

                val data = ByteArray(length)
                bytesRead = 0
                while (bytesRead < length) {
                    val read = inputStream.read(data, bytesRead, length - bytesRead)
                    if (read == -1) {
                        throw IOException("End of stream")
                    }
                    bytesRead += read
                }

                logger.d(TAG, "Received ${data.size} bytes from inbound peer: $deviceId")
                trySend(data)
            }
        } catch (e: Exception) {
            connected = false
            if (isExpectedClose(e)) {
                logger.i(TAG, "Inbound TCP peer closed connection: $deviceId")
            } else {
                logger.e(TAG, "Inbound TCP receive failed for peer: $deviceId", e)
            }
        }

        awaitClose {
            close()
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

        private fun isExpectedClose(error: Exception): Boolean {
            return error is SocketException ||
                (error is IOException && error.message == "End of stream")
        }
    }
}
