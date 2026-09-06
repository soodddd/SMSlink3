package com.smslink.network.connection

import android.bluetooth.BluetoothSocket
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

/** Server-side RFCOMM connection accepted by ConnectionManagerImpl. */
class AcceptedBluetoothConnection(
    private val deviceId: String,
    private val socket: BluetoothSocket,
    private val logger: ILogger
) : Connection {
    @Volatile
    private var connected = true

    private val inputStream = BufferedInputStream(socket.inputStream)
    private val outputStream = BufferedOutputStream(socket.outputStream)
    private val writeMutex = Mutex()

    override suspend fun connect() {
        connected = socket.isConnected
    }

    override suspend fun send(data: ByteArray) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isConnected()) throw IOException("Not connected")
            try {
                FrameCodec.writeFrame(outputStream, data)
                logger.d(TAG, "Sent ${data.size} bytes to inbound Bluetooth peer: $deviceId")
            } catch (e: Exception) {
                connected = false
                throw e
            }
        }
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

    override fun receiveFlow(): Flow<ByteArray> = callbackFlow {
        try {
            while (isActive && isConnected()) {
                trySend(FrameCodec.readFrame(inputStream))
            }
            if (!isActive || !connected) close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connected = false
            logger.w(TAG, "Inbound Bluetooth receive ended for $deviceId: ${e.message}")
            close(e)
        }
        awaitClose { this@AcceptedBluetoothConnection.close() }
    }

    override fun isConnected(): Boolean = connected && socket.isConnected

    override fun getType(): ConnectionType = ConnectionType.BLUETOOTH

    override fun getLinkType(): LinkType = LinkType.BLUETOOTH

    override fun close() {
        connected = false
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "AcceptedBluetoothConnection"
    }
}
