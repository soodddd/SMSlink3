package com.smslink.file.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.core.model.FileTransfer
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState
import com.smslink.device.DeviceViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件传输界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(
    deviceId: String,
    deviceViewModel: DeviceViewModel = hiltViewModel(),
    viewModel: FileTransferViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pairedDevices by deviceViewModel.pairedDevices.collectAsState()
    val connectedDevices by deviceViewModel.connectedDevices.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val resolvedTargetDeviceId =
        connectedDevices.firstOrNull()?.id?.takeIf { it.isNotBlank() }
            ?: pairedDevices.firstOrNull()?.id?.takeIf { it.isNotBlank() }
            ?: deviceId.takeIf { it.isNotBlank() }
            ?: ""
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val file = copyUriToCacheFile(context, uri)
        if (file == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Failed to read selected file")
            }
            return@rememberLauncherForActivityResult
        }

        viewModel.sendFile(file, resolvedTargetDeviceId)
    }

    // 接收确认对话框
    var pendingReceiveTransfer by remember { mutableStateOf<FileTransfer?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FileTransferEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                FileTransferEvent.SendStarted -> {
                    snackbarHostState.showSnackbar("File transfer started")
                }
                FileTransferEvent.TransferCancelled -> {
                    snackbarHostState.showSnackbar("Transfer cancelled")
                }
                FileTransferEvent.PermissionRequired -> {
                    viewModel.requestStoragePermission()
                }
                FileTransferEvent.PermissionDenied -> {
                    snackbarHostState.showSnackbar("Storage permission denied")
                }
                FileTransferEvent.ShowFilePicker -> {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(uiState.activeTransfers) {
        val pendingIncoming = uiState.activeTransfers.firstOrNull {
            it.direction == TransferDirection.DOWNLOAD && it.state == TransferState.PENDING
        }

        if (pendingIncoming != null && pendingReceiveTransfer?.id != pendingIncoming.id) {
            pendingReceiveTransfer = pendingIncoming
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件传输")
                        Text(
                            text = "File Transfer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.loadTransferHistory() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
            ) {
                Icon(Icons.Default.Add, "Send File")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 活动传输
            if (uiState.activeTransfers.isNotEmpty()) {
                Text(
                    text = "Active Transfers",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                uiState.activeTransfers.forEach { transfer ->
                    ActiveTransferItem(
                        transfer = transfer,
                        onCancel = { viewModel.cancelTransfer(transfer.id) },
                        onRetry = { viewModel.retryTransfer(transfer.id) }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // 传输历史
            Text(
                text = "Transfer History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.transferHistory) { transfer ->
                    TransferHistoryItem(
                        transfer = transfer,
                        onClick = { viewModel.showTransferDetails(transfer) }
                    )
                }
            }
        }

        // 传输详情对话框
        uiState.selectedTransfer?.let { transfer ->
            TransferDetailsDialog(
                transfer = transfer,
                onDismiss = { viewModel.closeTransferDetails() }
            )
        }

        // 接收确认对话框
        pendingReceiveTransfer?.let { transfer ->
            FileReceiveConfirmDialog(
                transfer = transfer,
                deviceName = deviceId,
                onAccept = {
                    viewModel.acceptFileTransfer(transfer.id)
                    pendingReceiveTransfer = null
                },
                onReject = {
                    viewModel.rejectFileTransfer(transfer.id)
                    pendingReceiveTransfer = null
                }
            )
        }
    }
}

/**
 * 活动传输项
 */
@Composable
fun ActiveTransferItem(
    transfer: FileTransfer,
    onCancel: () -> Unit,
    onRetry: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(transfer.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    if (transfer.state == TransferState.FAILED) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, "Retry")
                        }
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = transfer.progress,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(transfer.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 传输历史项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistoryItem(
    transfer: FileTransfer,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (transfer.direction) {
                    TransferDirection.UPLOAD -> Icons.Default.Upload
                    TransferDirection.DOWNLOAD -> Icons.Default.Download
                },
                contentDescription = null,
                tint = when (transfer.state) {
                    TransferState.COMPLETED -> MaterialTheme.colorScheme.primary
                    TransferState.FAILED -> MaterialTheme.colorScheme.error
                    TransferState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(transfer.fileSize)} • ${formatTimestamp(transfer.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            TransferStateChip(state = transfer.state)
        }
    }
}

/**
 * 传输状态标签
 */
@Composable
fun TransferStateChip(state: TransferState) {
    val (text, color) = when (state) {
        TransferState.PENDING -> "Pending" to MaterialTheme.colorScheme.secondary
        TransferState.TRANSFERRING -> "Transferring" to MaterialTheme.colorScheme.primary
        TransferState.COMPLETED -> "Completed" to MaterialTheme.colorScheme.primary
        TransferState.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        TransferState.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 传输详情对话框
 */
@Composable
fun TransferDetailsDialog(
    transfer: FileTransfer,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Details") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("File Name", transfer.fileName)
                DetailRow("File Size", formatFileSize(transfer.fileSize))
                DetailRow("Type", transfer.mimeType)
                DetailRow("Direction", transfer.direction.name)
                DetailRow("State", transfer.state.name)
                DetailRow("Progress", "${(transfer.progress * 100).toInt()}%")
                DetailRow("Device ID", transfer.deviceId)
                DetailRow("Time", formatTimestamp(transfer.timestamp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * 详情行
 */
@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun copyUriToCacheFile(context: Context, uri: Uri): java.io.File? {
    return try {
        val fileName = getDisplayName(context, uri)
        val tempFile = java.io.File(context.cacheDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        tempFile
    } catch (_: Exception) {
        null
    }
}

private fun getDisplayName(context: Context, uri: Uri): String {
    var fileName = "shared_file"

    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }

    return fileName
}
