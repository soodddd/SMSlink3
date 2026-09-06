package com.smslink.file.data

import com.smslink.core.model.FileTransfer
import com.smslink.core.model.TransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(
    private val fileTransferDao: FileTransferDao
) {
    suspend fun saveTransferWithLink(transfer: FileTransfer, filePath: String?, bytesTransferred: Long, linkType: String) {
        fileTransferDao.insert(
            FileTransferEntity(
                id = transfer.id,
                fileName = transfer.fileName,
                filePath = filePath,
                fileSize = transfer.fileSize,
                mimeType = transfer.mimeType,
                deviceId = transfer.deviceId,
                direction = transfer.direction,
                state = transfer.state,
                progress = transfer.progress,
                bytesTransferred = bytesTransferred,
                timestamp = transfer.timestamp,
                linkType = linkType,
                fileHash = transfer.fileHash,
                nextSequence = transfer.nextSequence,
                retryCount = transfer.retryCount,
                lastError = transfer.lastError,
                protocolVersion = 2
            )
        )
    }

    suspend fun saveTransfer(transfer: FileTransfer, filePath: String?, bytesTransferred: Long) {
        fileTransferDao.insert(
            FileTransferEntity(
                id = transfer.id,
                fileName = transfer.fileName,
                filePath = filePath,
                fileSize = transfer.fileSize,
                mimeType = transfer.mimeType,
                deviceId = transfer.deviceId,
                direction = transfer.direction,
                state = transfer.state,
                progress = transfer.progress,
                bytesTransferred = bytesTransferred,
                timestamp = transfer.timestamp,
                fileHash = transfer.fileHash,
                nextSequence = transfer.nextSequence,
                retryCount = transfer.retryCount,
                lastError = transfer.lastError,
                protocolVersion = 2
            )
        )
    }

    suspend fun updateTransfer(
        transferId: String,
        state: TransferState,
        progress: Float,
        bytesTransferred: Long,
        errorMessage: String? = null
    ) {
        val entity = fileTransferDao.getById(transferId) ?: return
        fileTransferDao.update(
            entity.copy(
                state = state,
                progress = progress,
                bytesTransferred = bytesTransferred,
                errorMessage = errorMessage
            )
        )
    }

    suspend fun getTransferById(transferId: String): FileTransfer? = fileTransferDao.getById(transferId)?.toModel()

    fun observeTransfer(transferId: String): Flow<FileTransfer?> =
        fileTransferDao.observeById(transferId).map { it?.toModel() }

    suspend fun getTransferHistory(limit: Int): List<FileTransfer> = fileTransferDao.getAll(limit).map { it.toModel() }

    fun getActiveTransfers(): Flow<List<FileTransfer>> = fileTransferDao.getActiveTransfers().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun deleteTransfer(transferId: String) {
        fileTransferDao.delete(transferId)
    }

    suspend fun getFilePath(transferId: String): String? = fileTransferDao.getById(transferId)?.filePath

    suspend fun updateTransferRetry(transferId: String, retryCount: Int, errorMessage: String?) {
        val entity = fileTransferDao.getById(transferId) ?: return
        fileTransferDao.update(
            entity.copy(
                retryCount = retryCount,
                lastError = errorMessage
            )
        )
    }

    suspend fun updateTransferSession(
        transferId: String,
        fileHash: String?,
        nextSequence: Int
    ) {
        val entity = fileTransferDao.getById(transferId) ?: return
        fileTransferDao.update(
            entity.copy(
                fileHash = fileHash,
                nextSequence = nextSequence,
                protocolVersion = 2
            )
        )
    }

    suspend fun getBytesTransferred(transferId: String): Long = fileTransferDao.getById(transferId)?.bytesTransferred ?: 0L

    suspend fun getCompletedTransfers(): List<FileTransfer> =
        fileTransferDao.getByState(TransferState.COMPLETED).map { it.toModel() }

    suspend fun getFailedTransfers(): List<FileTransfer> =
        fileTransferDao.getByState(TransferState.FAILED).map { it.toModel() }

    suspend fun getTransfersByDevice(deviceId: String): List<FileTransfer> =
        fileTransferDao.getByDevice(deviceId).map { it.toModel() }

    suspend fun clearCompletedTransfers() {
        fileTransferDao.deleteByState(TransferState.COMPLETED)
    }

    suspend fun getTransferCount(): Int = fileTransferDao.getCount()

    private fun FileTransferEntity.toModel(): FileTransfer {
        return FileTransfer(
            id = id,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            deviceId = deviceId,
            direction = direction,
            state = state,
            progress = progress,
            timestamp = timestamp,
            bytesTransferred = bytesTransferred,
            errorMessage = errorMessage,
            filePath = filePath,
            fileHash = fileHash,
            nextSequence = nextSequence,
            retryCount = retryCount,
            lastError = lastError
        )
    }
}
