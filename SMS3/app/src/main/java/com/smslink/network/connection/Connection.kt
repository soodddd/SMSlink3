package com.smslink.network.connection

import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * 统一连接接口
 * 支持 TCP 和蓝牙连接
 */
interface Connection : Closeable {
    /**
     * 建立连接
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
     * 获取链路类型
     */
    fun getLinkType(): LinkType
}
