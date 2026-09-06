package com.smslink.file.ui

import androidx.lifecycle.ViewModel
import com.smslink.core.log.ILogger
import com.smslink.core.model.FileTransfer
import com.smslink.core.permission.IPermissionManager
import com.smslink.file.IFileTransferManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 文件传输 ViewModel
 */
@HiltViewModel
class FileTransferViewModel @Inject constructor(
    private val fileTransferManager: IFileTransferManager,
    private val permissionManager: IPermissionManager,
    private val logger: ILogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileTransferUiState())
    val uiState: StateFlow<FileTransferUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FileTransferEvent>()
    val events: SharedFlow<FileTransferEvent> = _events.asSharedFlow()

    // The manager owns actual file IO. Completed flows run immediately, while
    // suspended work is dispatched to IO; this also keeps JVM tests independent
    // of Android's Main looper.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED, block = block)
    }

    init {
        loadActiveTransfers()
        loadTransferHistory()
    }

    /**
     * 加载活动传输
     */
    private fun loadActiveTransfers() {
        launchWork {
            fileTransferManager.getActiveTransfers()
                .catch { e ->
                    logger.e("FileTransferVM", "Load active transfers failed: ${e.message}")
                }
                .collect { transfers ->
                    _uiState.update { it.copy(activeTransfers = transfers) }
                }
        }
    }

    /**
     * 加载传输历史
     */
    fun loadTransferHistory() {
        launchWork {
            try {
                val history = fileTransferManager.getTransferHistory(50)
                _uiState.update { it.copy(transferHistory = history) }
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Load transfer history failed: ${e.message}")
            }
        }
    }

    /**
     * 发送文件
     */
    fun sendFile(file: File, targetDeviceId: String) {
        launchWork {
            if (targetDeviceId.isBlank()) {
                _events.emit(FileTransferEvent.Error("No target device selected"))
                return@launchWork
            }
            try {
                _uiState.update { it.copy(isLoading = true) }
                _events.emit(FileTransferEvent.SendStarted)

                fileTransferManager.sendFile(file, targetDeviceId)
                    .collect { transfer ->
                        logger.i("FileTransferVM", "Transfer progress: ${transfer.progress}")
                    }
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Send file failed: ${e.message}")
                _events.emit(FileTransferEvent.Error(e.message ?: "Send failed"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                loadTransferHistory()
            }
        }
    }

    /**
     * 接收文件
     */
    fun receiveFile(transferId: String) {
        launchWork {
            try {
                fileTransferManager.receiveFile(transferId)
                    .collect { transfer ->
                        logger.i("FileTransferVM", "Receive progress: ${transfer.progress}")
                    }
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Receive file error: ${e.message}")
                _events.emit(FileTransferEvent.Error(e.message ?: "Receive failed"))
            }
        }
    }

    /**
     * 取消传输
     */
    fun cancelTransfer(transferId: String) {
        launchWork {
            try {
                fileTransferManager.cancelTransfer(transferId)
                _events.emit(FileTransferEvent.TransferCancelled)
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Cancel transfer failed: ${e.message}")
                _events.emit(FileTransferEvent.Error(e.message ?: "Cancel failed"))
            }
        }
    }

    /**
     * 重试传输
     */
    fun retryTransfer(transferId: String) {
        launchWork {
            try {
                _uiState.update { it.copy(isLoading = true) }
                logger.i("FileTransferVM", "Retry transfer: $transferId")
                _events.emit(FileTransferEvent.SendStarted)
                fileTransferManager.retryTransfer(transferId).collect { transfer ->
                    logger.i("FileTransferVM", "Retry progress: ${transfer.progress}")
                }
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Retry transfer failed: ${e.message}")
                _events.emit(FileTransferEvent.Error(e.message ?: "Retry failed"))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
                loadTransferHistory()
            }
        }
    }

    /**
     * 鎺ュ彈鏂囦欢浼犺緭
     */
    fun acceptFileTransfer(transferId: String) {
        receiveFile(transferId)
    }

    /**
     * 拒绝文件传输
     */
    fun rejectFileTransfer(transferId: String) {
        launchWork {
            try {
                fileTransferManager.cancelTransfer(transferId)
                logger.i("FileTransferVM", "File transfer rejected: $transferId")
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Reject transfer failed: ${e.message}")
            }
        }
    }

    /**
     * 选择文件
     */
    fun selectFile() {
        launchWork {
            _events.emit(FileTransferEvent.ShowFilePicker)
        }
    }

    /**
     * 显示传输详情
     */
    fun showTransferDetails(transfer: FileTransfer) {
        _uiState.update { it.copy(selectedTransfer = transfer) }
    }

    /**
     * 关闭传输详情
     */
    fun closeTransferDetails() {
        _uiState.update { it.copy(selectedTransfer = null) }
    }

    /**
     * 检查存储权限
     */
    private fun checkStoragePermission(): Boolean {
        // Files are written to the app-specific external files directory, so
        // Android 13+ does not require a broad storage permission.
        return true
    }

    /**
     * 请求存储权限
     */
    fun requestStoragePermission() {
        launchWork {
            // SAF/content-URI access is granted by the picker/share contract;
            // there is no dangerous storage permission to request.
            _events.emit(FileTransferEvent.PermissionGranted)
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}

/**
 * 文件传输 UI 状态
 */
data class FileTransferUiState(
    val isLoading: Boolean = false,
    val activeTransfers: List<FileTransfer> = emptyList(),
    val transferHistory: List<FileTransfer> = emptyList(),
    val selectedTransfer: FileTransfer? = null
)

/**
 * 文件传输事件
 */
sealed class FileTransferEvent {
    object ShowFilePicker : FileTransferEvent()
    object SendStarted : FileTransferEvent()
    object TransferCancelled : FileTransferEvent()
    object PermissionRequired : FileTransferEvent()
    object PermissionGranted : FileTransferEvent()
    object PermissionDenied : FileTransferEvent()
    data class Error(val message: String) : FileTransferEvent()
}

