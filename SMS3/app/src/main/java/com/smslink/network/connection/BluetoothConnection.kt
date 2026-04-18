package com.smslink.network.connection

import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * 蓝牙连接接口
 * 使用 RFCOMM 协议进行蓝牙通信
 */
interface BluetoothConnection : Closeable {
    /**
     * 建立蓝牙连接
     */
    suspend fun connect()

    /**
     * 发送数据
     */
    suspend fun send(data: ByteArray)

    /**
     * 接收数据流
     */
    fun receiveFlow(): Flow<ByteArray>

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean

    /**
     * 获取连接类型
     */
    fun getType(): ConnectionType

    /**
     * 获取远程设备地址
     */
    fun getRemoteAddress(): String
}
