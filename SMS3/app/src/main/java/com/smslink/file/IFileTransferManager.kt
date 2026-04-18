package com.smslink.file

import com.smslink.core.model.FileTransfer
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 文件传输管理器接口
 * 负责设备间的文件传输
 */
interface IFileTransferManager {
    /**
     * 发送文件
     * @param file 文件
     * @param targetDeviceId 目标设备ID
     * @return 文件传输流
     */
    fun sendFile(file: File, targetDeviceId: String): Flow<FileTransfer>

    /**
     * 接收文件
     * @param transferId 传输ID
     * @return 文件传输流
     */
    fun receiveFile(transferId: String): Flow<FileTransfer>

    /**
     * 取消传输
     * @param transferId 传输ID
     */
    suspend fun cancelTransfer(transferId: String)

    /**
     * 获取传输历史
     * @param limit 数量限制
     * @return 传输历史列表
     */
    suspend fun getTransferHistory(limit: Int): List<FileTransfer>

    /**
     * 获取活动传输
     * @return 活动传输列表流
     */
    fun getActiveTransfers(): Flow<List<FileTransfer>>
}
