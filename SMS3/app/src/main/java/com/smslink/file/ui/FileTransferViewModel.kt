package com.smslink.file.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.log.ILogger
import com.smslink.core.model.FileTransfer
import com.smslink.core.permission.IPermissionManager
import com.smslink.file.IFileTransferManager
import dagger.hilt.android.lifecycle.HiltViewModel
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

    init {
        loadActiveTransfers()
        loadTransferHistory()
    }

    /**
     * 加载活动传输
     */
    private fun loadActiveTransfers() {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            if (targetDeviceId.isBlank()) {
                _events.emit(FileTransferEvent.Error("No target device selected"))
                return@launch
            }
            try {
                _uiState.update { it.copy(isLoading = true) }

                fileTransferManager.sendFile(file, targetDeviceId)
                    .catch { e ->
                        logger.e("FileTransferVM", "Send file failed: ${e.message}")
                        _events.emit(FileTransferEvent.Error(e.message ?: "Send failed"))
                    }
                    .collect { transfer ->
                        logger.i("FileTransferVM", "Transfer progress: ${transfer.progress}")
                    }

                _events.emit(FileTransferEvent.SendStarted)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 接收文件
     */
    fun receiveFile(transferId: String) {
        viewModelScope.launch {
            if (!checkStoragePermission()) {
                _events.emit(FileTransferEvent.PermissionRequired)
                return@launch
            }

            try {
                fileTransferManager.receiveFile(transferId)
                    .catch { e ->
                        logger.e("FileTransferVM", "Receive file failed: ${e.message}")
                        _events.emit(FileTransferEvent.Error(e.message ?: "Receive failed"))
                    }
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                // TODO: 瀹炵幇閲嶈瘯閫昏緫
                logger.i("FileTransferVM", "Retry transfer: $transferId")
                _events.emit(FileTransferEvent.SendStarted)
            } catch (e: Exception) {
                logger.e("FileTransferVM", "Retry transfer failed: ${e.message}")
                _events.emit(FileTransferEvent.Error(e.message ?: "Retry failed"))
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionManager.hasPermissions(
                listOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                )
            )
        } else {
            permissionManager.hasPermissions(
                listOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    /**
     * 请求存储权限
     */
    fun requestStoragePermission() {
        viewModelScope.launch {
            val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                listOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }

            permissionManager.requestPermissions(permissions).collect { results ->
                val allGranted = results.values.all { it.granted }
                if (allGranted) {
                    _events.emit(FileTransferEvent.PermissionGranted)
                } else {
                    _events.emit(FileTransferEvent.PermissionDenied)
                }
            }
        }
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

