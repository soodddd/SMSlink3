package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.model.CallType
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferStatus
import com.smslink.ui.bridge.TransferBridge
import com.smslink.ui.screens.transfer.CallHistoryUiModel
import com.smslink.ui.screens.transfer.FileTransferUiModel
import com.smslink.ui.screens.transfer.TransferUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferBridge: TransferBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    init {
        loadFileTransfers()
        loadCallHistory()
    }

    private fun loadFileTransfers() {
        viewModelScope.launch {
            transferBridge.getFileTransfers()
                .catch {
                    _uiState.update { state -> state.copy(isLoading = false) }
                }
                .collect { transfers ->
                    val transferModels = transfers.map { transfer ->
                        FileTransferUiModel(
                            id = transfer.id,
                            fileName = transfer.fileName,
                            fileSize = formatFileSize(transfer.fileSize),
                            deviceName = transfer.deviceName,
                            status = transfer.status,
                            direction = transfer.direction,
                            progress = transfer.progress,
                            isFolder = transfer.isFolder,
                            fileCount = transfer.fileCount,
                            errorMessage = transfer.errorMessage,
                            timeText = formatTimestamp(transfer.timestamp)
                        )
                    }

                    _uiState.update { state ->
                        state.copy(
                            fileTransfers = transferModels,
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun loadCallHistory() {
        viewModelScope.launch {
            transferBridge.getCallHistory()
                .catch {
                    _uiState.update { state -> state.copy(isLoading = false) }
                }
                .collect { calls ->
                    val callModels = calls.map { call ->
                        CallHistoryUiModel(
                            id = call.id,
                            contactName = call.contactName,
                            phoneNumber = call.phoneNumber,
                            type = formatCallType(call.type),
                            duration = formatDuration(call.duration),
                            timeText = formatTimestamp(call.timestamp)
                        )
                    }

                    _uiState.update { state ->
                        state.copy(
                            callHistory = callModels,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun acceptIncomingTransfer(transferId: String) {
        viewModelScope.launch {
            transferBridge.acceptIncomingTransfer(transferId)
        }
    }

    fun rejectIncomingTransfer(transferId: String) {
        viewModelScope.launch {
            transferBridge.rejectIncomingTransfer(transferId)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun formatCallType(type: CallType): String {
        return when (type) {
            CallType.INCOMING -> "来电"
            CallType.OUTGOING -> "去电"
            CallType.MISSED -> "未接"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return if (minutes > 0) {
            "${minutes}分${secs}秒"
        } else {
            "${secs}秒"
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000}小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
