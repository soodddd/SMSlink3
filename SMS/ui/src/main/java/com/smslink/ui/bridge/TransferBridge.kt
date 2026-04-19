package com.smslink.ui.bridge

import com.smslink.core.model.CallInfo
import com.smslink.core.model.CallType
import com.smslink.core.model.FileTransferInfo
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferStatus
import com.smslink.feature.call.CallManager
import com.smslink.feature.transfer.FileTransferManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferBridge @Inject constructor(
    private val fileTransferManager: FileTransferManager,
    private val callManager: CallManager
) {
    private val _availability = MutableStateFlow(FeatureAvailability.Ready)
    val availability: StateFlow<FeatureAvailability> = _availability

    /**
     * Get file transfer history.
     */
    fun getFileTransfers(): Flow<List<FileTransferUiModel>> {
        return fileTransferManager.getAllTransfers().map { transfers ->
            transfers.map { it.toUiModel() }
        }
    }

    /**
     * Get file transfers by status.
     */
    fun getFileTransfersByStatus(status: TransferStatus): Flow<List<FileTransferUiModel>> {
        return fileTransferManager.getTransfersByStatus(status).map { transfers ->
            transfers.map { it.toUiModel() }
        }
    }

    /**
     * Send a file.
     */
    suspend fun sendFile(deviceId: String, deviceName: String, file: File): Result<String> {
        return fileTransferManager.sendFile(deviceId, deviceName, file)
    }

    /**
     * Send a folder.
     */
    suspend fun sendFolder(deviceId: String, deviceName: String, folder: File): Result<String> {
        return fileTransferManager.sendFolder(deviceId, deviceName, folder)
    }

    /**
     * Cancel a transfer.
     */
    suspend fun cancelTransfer(transferId: String): Result<Unit> {
        return fileTransferManager.cancelTransfer(transferId)
    }

    /**
     * Pause a transfer.
     */
    suspend fun pauseTransfer(transferId: String): Result<Unit> {
        return fileTransferManager.pauseTransfer(transferId)
    }

    /**
     * Resume a transfer.
     */
    suspend fun resumeTransfer(transferId: String): Result<Unit> {
        return fileTransferManager.resumeTransfer(transferId)
    }

    /**
     * Retry a transfer.
     */
    suspend fun retryTransfer(transferId: String): Result<Unit> {
        return fileTransferManager.retryTransfer(transferId)
    }

    /**
     * Accept a pending incoming transfer.
     */
    suspend fun acceptIncomingTransfer(transferId: String): Result<Unit> {
        return fileTransferManager.acceptIncomingTransfer(transferId)
    }

    /**
     * Reject a pending incoming transfer.
     */
    suspend fun rejectIncomingTransfer(transferId: String, reason: String? = null): Result<Unit> {
        return fileTransferManager.rejectIncomingTransfer(transferId, reason)
    }

    /**
     * Get call history.
     */
    fun getCallHistory(): Flow<List<CallHistoryUiModel>> {
        return callManager.callHistory.map { calls ->
            calls.map { it.toUiModel() }
        }
    }

    private fun FileTransferInfo.toUiModel(): FileTransferUiModel {
        return FileTransferUiModel(
            id = id,
            fileName = fileName,
            fileSize = fileSize,
            deviceId = deviceId,
            deviceName = deviceName,
            status = status,
            progress = progress,
            transferredBytes = transferredBytes,
            speed = speed,
            timestamp = timestamp,
            isFolder = isFolder,
            fileCount = fileCount,
            errorMessage = errorMessage,
            direction = direction
        )
    }

    private fun CallInfo.toUiModel(): CallHistoryUiModel {
        return CallHistoryUiModel(
            id = id,
            contactName = contactName ?: "Unknown",
            phoneNumber = phoneNumber,
            type = type,
            duration = duration,
            deviceName = deviceName,
            timestamp = timestamp
        )
    }
}

data class FileTransferUiModel(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val deviceId: String,
    val deviceName: String,
    val status: TransferStatus,
    val progress: Int,
    val transferredBytes: Long,
    val speed: Long,
    val timestamp: Long,
    val isFolder: Boolean,
    val fileCount: Int,
    val errorMessage: String?,
    val direction: TransferDirection
)

data class CallHistoryUiModel(
    val id: String,
    val contactName: String,
    val phoneNumber: String,
    val type: CallType,
    val duration: Long,
    val deviceName: String,
    val timestamp: Long
)
