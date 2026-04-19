package com.smslink.feature.transfer

import com.smslink.core.database.FileTransferDao
import com.smslink.core.database.FileTransferEntity
import com.smslink.core.model.FileTransferInfo
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件传输仓库
 * 负责文件传输记录的持久化
 */
@Singleton
class TransferRepository @Inject constructor(
    private val fileTransferDao: FileTransferDao
) {

    /**
     * 获取所有文件传输记录
     */
    fun getAllTransfers(): Flow<List<FileTransferInfo>> {
        return fileTransferDao.getAllTransfers().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 根据状态获取文件传输记录
     */
    fun getTransfersByStatus(status: TransferStatus): Flow<List<FileTransferInfo>> {
        return fileTransferDao.getTransfersByStatus(status.name).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 根据设备 ID 获取文件传输记录
     */
    fun getTransfersByDevice(deviceId: String): Flow<List<FileTransferInfo>> {
        return fileTransferDao.getTransfersByDevice(deviceId).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * 根据 ID 获取文件传输记录
     */
    suspend fun getTransferById(transferId: String): FileTransferInfo? {
        return fileTransferDao.getById(transferId)?.toModel()
    }

    /**
     * 插入文件传输记录
     */
    suspend fun insertTransfer(
        transfer: FileTransferInfo,
        direction: TransferDirection = TransferDirection.SEND
    ) {
        fileTransferDao.insert(transfer.toEntity(direction))
    }

    /**
     * 更新文件传输记录
     */
    suspend fun updateTransfer(
        transfer: FileTransferInfo,
        direction: TransferDirection = transfer.direction
    ) {
        fileTransferDao.update(transfer.toEntity(direction))
    }

    /**
     * 更新传输进度
     */
    suspend fun updateProgress(transferId: String, progress: Int, transferredBytes: Long, speed: Long) {
        fileTransferDao.updateProgress(transferId, progress, transferredBytes, speed)
    }

    /**
     * 更新传输状态
     */
    suspend fun updateStatus(transferId: String, status: TransferStatus, errorMessage: String? = null) {
        fileTransferDao.updateStatus(transferId, status.name, errorMessage)
    }

    /**
     * 删除文件传输记录
     */
    suspend fun deleteTransfer(transferId: String) {
        fileTransferDao.delete(transferId)
    }

    /**
     * 清空所有文件传输记录
     */
    suspend fun deleteAllTransfers() {
        fileTransferDao.deleteAll()
    }

    /**
     * Entity 转 Model
     */
    private fun FileTransferEntity.toModel(): FileTransferInfo {
        return FileTransferInfo(
            id = id,
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize,
            mimeType = mimeType,
            deviceId = deviceId,
            deviceName = deviceName,
            status = TransferStatus.valueOf(status),
            progress = progress,
            transferredBytes = transferredBytes,
            speed = speed,
            timestamp = timestamp,
            isFolder = isFolder,
            fileCount = fileCount,
            errorMessage = errorMessage,
            direction = TransferDirection.valueOf(direction)
        )
    }

    /**
     * Model 转 Entity
     */
    private fun FileTransferInfo.toEntity(directionOverride: TransferDirection? = null): FileTransferEntity {
        val resolvedDirection = directionOverride ?: direction
        return FileTransferEntity(
            id = id,
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize,
            mimeType = mimeType,
            deviceId = deviceId,
            deviceName = deviceName,
            status = status.name,
            progress = progress,
            transferredBytes = transferredBytes,
            speed = speed,
            timestamp = timestamp,
            isFolder = isFolder,
            fileCount = fileCount,
            errorMessage = errorMessage,
            direction = resolvedDirection.name
        )
    }
}
