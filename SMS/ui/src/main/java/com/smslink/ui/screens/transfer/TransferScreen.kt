package com.smslink.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferStatus
import com.smslink.ui.components.EmptyState
import com.smslink.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    uiState: TransferUiState,
    onAddTransfer: () -> Unit,
    onTransferClick: (String) -> Unit,
    onCallClick: (String) -> Unit,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("传输") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = onAddTransfer,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建传输")
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("文件传输") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("通话记录") }
                )
            }

            when (selectedTab) {
                0 -> FileTransferList(
                    transfers = uiState.fileTransfers,
                    isLoading = uiState.isLoading,
                    onTransferClick = onTransferClick,
                    onAcceptTransfer = onAcceptTransfer,
                    onRejectTransfer = onRejectTransfer
                )
                1 -> CallHistoryList(
                    calls = uiState.callHistory,
                    isLoading = uiState.isLoading,
                    onCallClick = onCallClick
                )
            }
        }
    }
}

@Composable
private fun FileTransferList(
    transfers: List<FileTransferUiModel>,
    isLoading: Boolean,
    onTransferClick: (String) -> Unit,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit
) {
    when {
        isLoading -> LoadingIndicator()
        transfers.isEmpty() -> EmptyState(message = "暂无文件传输记录")
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transfers, key = { it.id }) { transfer ->
                    FileTransferCard(
                        transfer = transfer,
                        onClick = { onTransferClick(transfer.id) },
                        onAcceptTransfer = onAcceptTransfer,
                        onRejectTransfer = onRejectTransfer
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTransferCard(
    transfer: FileTransferUiModel,
    onClick: () -> Unit,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildTransferSubtitle(transfer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TransferStatusBadge(status = transfer.status)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransferDirectionBadge(direction = transfer.direction)
                Text(
                    text = transfer.timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (transfer.progress > 0 && transfer.progress < 100) {
                LinearProgressIndicator(
                    progress = { transfer.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${transfer.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            transfer.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (transfer.direction == TransferDirection.RECEIVE && transfer.status == TransferStatus.PENDING) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = { onRejectTransfer(transfer.id) }) {
                        Text("拒绝")
                    }
                    Button(onClick = { onAcceptTransfer(transfer.id) }) {
                        Text("接受")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStatusBadge(status: TransferStatus) {
    val (label, color) = when (status) {
        TransferStatus.PENDING -> "等待" to MaterialTheme.colorScheme.tertiary
        TransferStatus.TRANSFERRING -> "传输中" to MaterialTheme.colorScheme.primary
        TransferStatus.PAUSED -> "已暂停" to MaterialTheme.colorScheme.secondary
        TransferStatus.COMPLETED -> "完成" to MaterialTheme.colorScheme.primary
        TransferStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> "已取消" to MaterialTheme.colorScheme.outline
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun TransferDirectionBadge(direction: TransferDirection) {
    val (label, color) = when (direction) {
        TransferDirection.SEND -> "发送" to MaterialTheme.colorScheme.secondary
        TransferDirection.RECEIVE -> "接收" to MaterialTheme.colorScheme.tertiary
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun buildTransferSubtitle(transfer: FileTransferUiModel): String {
    val base = buildString {
        append(transfer.fileSize)
        append(" · ")
        append(transfer.deviceName)
    }

    return if (transfer.isFolder) {
        if (transfer.fileCount > 1) {
            "$base · 文件夹 · ${transfer.fileCount} 个文件"
        } else {
            "$base · 文件夹"
        }
    } else {
        base
    }
}

@Composable
private fun CallHistoryList(
    calls: List<CallHistoryUiModel>,
    isLoading: Boolean,
    onCallClick: (String) -> Unit
) {
    when {
        isLoading -> LoadingIndicator()
        calls.isEmpty() -> EmptyState(message = "暂无通话记录")
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(calls, key = { it.id }) { call ->
                    CallHistoryCard(
                        call = call,
                        onClick = { onCallClick(call.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CallHistoryCard(
    call: CallHistoryUiModel,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = call.contactName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = call.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${call.timeText} · ${call.duration}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CallTypeBadge(type = call.type)
        }
    }
}

@Composable
private fun CallTypeBadge(type: String) {
    val (label, color) = when (type) {
        "来电" -> "来电" to MaterialTheme.colorScheme.primary
        "去电" -> "去电" to MaterialTheme.colorScheme.secondary
        "未接" -> "未接" to MaterialTheme.colorScheme.error
        else -> type to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

data class TransferUiState(
    val isLoading: Boolean = false,
    val fileTransfers: List<FileTransferUiModel> = emptyList(),
    val callHistory: List<CallHistoryUiModel> = emptyList()
)

data class FileTransferUiModel(
    val id: String,
    val fileName: String,
    val fileSize: String,
    val deviceName: String,
    val status: TransferStatus,
    val direction: TransferDirection,
    val progress: Int,
    val isFolder: Boolean,
    val fileCount: Int,
    val errorMessage: String?,
    val timeText: String
)

data class CallHistoryUiModel(
    val id: String,
    val contactName: String,
    val phoneNumber: String,
    val type: String,
    val duration: String,
    val timeText: String
)
