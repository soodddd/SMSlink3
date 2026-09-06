package com.smslink.network.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
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
import java.util.UUID

/**
 * 蓝牙 RFCOMM 连接实现
 * 使用标准 SPP (Serial Port Profile) 协议
 */
class BluetoothConnectionImpl(
    private val deviceId: String,
    private val bluetoothAddress: String,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: ILogger
) : BluetoothConnection {

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var inputStream: BufferedInputStream? = null

    @Volatile
    private var outputStream: BufferedOutputStream? = null

    @Volatile
    private var connected = false

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            logger.i(TAG, "Connecting to Bluetooth device: $bluetoothAddress for device: $deviceId")

            // 验证蓝牙地址格式
            if (!BluetoothAdapter.checkBluetoothAddress(bluetoothAddress)) {
                throw IllegalArgumentException("Invalid Bluetooth address: $bluetoothAddress")
            }

            // 获取远程蓝牙设备
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(bluetoothAddress)

            // 检查设备是否已配对
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                logger.w(TAG, "Device not paired: $bluetoothAddress")
                throw IOException("Device not paired: $bluetoothAddress")
            }

            // 取消设备发现以提高连接速度
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }

            // 创建 RFCOMM Socket
            val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = btSocket

            try {
                // 建立连接
                btSocket.connect()

                // 创建输入输出流
                inputStream = BufferedInputStream(btSocket.inputStream)
                outputStream = BufferedOutputStream(btSocket.outputStream)

                connected = true

                logger.i(TAG, "Successfully connected to Bluetooth device: $deviceId")
            } catch (e: Exception) {
                // 连接失败，关闭 socket
                try {
                    btSocket.close()
                } catch (closeException: Exception) {
                    logger.e(TAG, "Error closing socket after connection failure", closeException)
                }
                socket = null
                throw e
            }

        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect to Bluetooth device: $deviceId", e)
            close()
            throw e
        }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(this@BluetoothConnectionImpl) {
            if (!connected) {
                throw IOException("Not connected")
            }

            try {
                val output = outputStream ?: throw IOException("Output stream is null")

                FrameCodec.writeFrame(output, data)

                logger.d(TAG, "Sent ${data.size} bytes to Bluetooth device: $deviceId")

            } catch (e: Exception) {
                logger.e(TAG, "Failed to send data to Bluetooth device: $deviceId", e)
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

            while (isActive && connected) {
                try {
                    val data = FrameCodec.readFrame(input)

                    logger.d(TAG, "Received ${data.size} bytes from Bluetooth device: $deviceId")
                    trySend(data)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(TAG, "Error receiving data from Bluetooth device: $deviceId", e)
                    connected = false
                    this@BluetoothConnectionImpl.close()
                    close(e)
                    break
                }
            }
            if (!isActive || !connected) close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Receive flow error for Bluetooth device: $deviceId", e)
            close(e)
        }

        awaitClose {
            this@BluetoothConnectionImpl.close()
            logger.d(TAG, "Receive flow closed for Bluetooth device: $deviceId")
        }
    }

    override fun isConnected(): Boolean = connected

    override fun getType(): ConnectionType = ConnectionType.BLUETOOTH

    override fun getRemoteAddress(): String = bluetoothAddress

    override fun close() {
        logger.i(TAG, "Closing Bluetooth connection to device: $deviceId")

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
            socket?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing Bluetooth socket", e)
        }

        inputStream = null
        outputStream = null
        socket = null

        logger.i(TAG, "Bluetooth connection closed for device: $deviceId")
    }

    companion object {
        private const val TAG = "BluetoothConnection"
        private const val MAX_MESSAGE_SIZE = 10 * 1024 * 1024 // 10MB (降低以防止 OOM)

        // 标准 SPP UUID (Serial Port Profile)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
