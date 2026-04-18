package com.smslink.network

import com.smslink.core.model.Connection
import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.flow.Flow

/**
 * 连接管理器接口
 * 负责设备间的网络连接管理
 */
interface IConnectionManager {
    /**
     * 建立连接
     * @param deviceId 设备ID
     * @param type 连接类型
     * @return 连接结果流
     */
    fun connect(deviceId: String, type: ConnectionType): Flow<Connection>

    /**
     * 断开连接
     * @param deviceId 设备ID
     */
    suspend fun disconnect(deviceId: String)

    /**
     * 获取连接状态
     * @param deviceId 设备ID
     * @return 连接状态流
     */
    fun getConnectionState(deviceId: String): Flow<Connection>

    /**
     * 获取所有活动连接
     * @return 连接列表流
     */
    fun getActiveConnections(): Flow<List<Connection>>

    /**
     * 发送数据
     * @param deviceId 设备ID
     * @param data 数据
     * @return 是否发送成功
     */
    suspend fun sendData(deviceId: String, data: ByteArray): Boolean

    /**
     * 接收数据流
     * @return 数据流
     */
    fun receiveData(): Flow<Pair<String, ByteArray>>
}
