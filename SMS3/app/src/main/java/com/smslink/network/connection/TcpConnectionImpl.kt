package com.smslink.network.connection

import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import com.smslink.network.encryption.IEncryption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLSocket

/**
 * TCP 连接实现
 * 支持 TLS 加密的 TCP Socket 连接
 */
class TcpConnectionImpl(
    private val deviceId: String,
    private val host: String,
    private val port: Int,
    private val type: ConnectionType,
    private val encryption: IEncryption,
    private val logger: ILogger
) : TcpConnection {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var sslSocket: SSLSocket? = null

    @Volatile
    private var inputStream: BufferedInputStream? = null

    @Volatile
    private var outputStream: BufferedOutputStream? = null

    @Volatile
    private var connected = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            logger.i(TAG, "Connecting to $host:$port for device: $deviceId")

            // 创建普通 Socket
            val plainSocket = Socket()
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            plainSocket.soTimeout = AUTH_READ_TIMEOUT_MS
            plainSocket.keepAlive = true
            plainSocket.tcpNoDelay = true

            socket = plainSocket

            val sslContext = encryption.createSSLContext(deviceId)
            val ssl = sslContext.socketFactory.createSocket(
                plainSocket,
                host,
                port,
                true
            ) as SSLSocket

            ssl.useClientMode = true
            ssl.enabledProtocols = TLS_PROTOCOLS
            ssl.enabledCipherSuites = ssl.supportedCipherSuites.filter(::isSupportedTlsCipherSuite).toTypedArray()
            ssl.startHandshake()
            ssl.soTimeout = AUTH_READ_TIMEOUT_MS

            sslSocket = ssl
            inputStream = BufferedInputStream(ssl.inputStream)
            outputStream = BufferedOutputStream(ssl.outputStream)

            connected = true

            logger.i(TAG, "Successfully connected to device: $deviceId")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect to device: $deviceId", e)
            close()
            throw e
        }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(this@TcpConnectionImpl) {
            if (!connected) throw IOException("Not connected")

            try {
                val output = outputStream ?: throw IOException("Output stream is null")
                FrameCodec.writeFrame(output, data)
                logger.d(TAG, "Sent ${data.size} bytes to device: $deviceId")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to send data to device: $deviceId", e)
                connected = false
                throw e
            }
        }
    }

    override suspend fun receiveOne(): ByteArray = withContext(Dispatchers.IO) {
        if (!connected) throw IOException("Not connected")
        try {
            FrameCodec.readFrame(inputStream ?: throw IOException("Input stream is null"))
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }

    override fun receiveFlow(): Flow<ByteArray> = callbackFlow {
        try {
            val input = inputStream ?: throw IOException("Input stream is null")
            // Authentication uses a finite read timeout. A timeout during a
            // length-prefixed frame can leave the stream halfway through a
            // header or payload, so authenticated streaming is close driven
            // instead of retrying from an unknown frame boundary.
            sslSocket?.soTimeout = 0

            while (isActive && connected) {
                try {
                    val data = FrameCodec.readFrame(input)

                    logger.d(TAG, "Received ${data.size} bytes from device: $deviceId")
                    trySend(data)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isExpectedClose(e)) {
                        logger.i(TAG, "Peer closed connection for device: $deviceId")
                    } else {
                        logger.e(TAG, "Error receiving data from device: $deviceId", e)
                    }
                    connected = false
                    this@TcpConnectionImpl.close()
                    close(e)
                    break
                }
            }

            if (!isActive || !connected) close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isExpectedClose(e)) {
                logger.i(TAG, "Receive flow closed by peer for device: $deviceId")
            } else {
                logger.e(TAG, "Receive flow error for device: $deviceId", e)
            }
            close(e)
        }

        awaitClose {
            this@TcpConnectionImpl.close()
            logger.d(TAG, "Receive flow closed for device: $deviceId")
        }
    }

    override fun isConnected(): Boolean = connected

    override fun getType(): ConnectionType = type

    override fun getLinkType(): LinkType {
        return when (type) {
            ConnectionType.WIFI -> LinkType.WIFI_LAN
            ConnectionType.HOTSPOT -> LinkType.WIFI_HOTSPOT
            ConnectionType.BLUETOOTH -> LinkType.BLUETOOTH
        }
    }

    override fun close() {
        logger.i(TAG, "Closing connection to device: $deviceId")

        connected = false

        try {
            inputStream?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing input stream", e)
        }

        try {
            outputStream?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing output stream", e)
        }

        try {
            sslSocket?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing SSL socket", e)
        }

        try {
            socket?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing socket", e)
        }

        inputStream = null
        outputStream = null
        sslSocket = null
        socket = null

        logger.i(TAG, "Connection closed for device: $deviceId")
    }

    companion object {
        private const val TAG = "TcpConnection"
        private const val CONNECT_TIMEOUT = 5000 // 5秒
        private const val AUTH_READ_TIMEOUT_MS = 10_000
        private const val MAX_MESSAGE_SIZE = 100 * 1024 * 1024 // 100MB
        private val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

        private fun isSupportedTlsCipherSuite(cipherSuite: String): Boolean {
            return cipherSuite.startsWith("TLS_AES_") ||
                cipherSuite.startsWith("TLS_CHACHA20_") ||
                cipherSuite.contains("_ECDHE_")
        }

        private fun isExpectedClose(error: Exception): Boolean {
            return error is SocketException ||
                (error is IOException && error.message == "End of stream")
        }
    }
}
