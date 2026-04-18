package com.smslink.network

import com.smslink.core.model.Message

/**
 * 网络管理器接口
 * 提供消息发送功能的简化接口
 */
interface INetworkManager {
    /**
     * 发送消息到指定设备
     * @param message 消息
     * @param targetDeviceId 目标设备ID
     */
    suspend fun sendMessage(message: Message, targetDeviceId: String)
}
