package com.smslink.network.connection

import com.smslink.core.model.ConnectionType
import kotlinx.coroutines.flow.Flow

/**
 * TCP connection contract.
 */
interface TcpConnection : Connection {
    override suspend fun connect()
    override suspend fun send(data: ByteArray)
    override fun receiveFlow(): Flow<ByteArray>
    override fun isConnected(): Boolean
    override fun getType(): ConnectionType
    override fun getLinkType(): LinkType
}
