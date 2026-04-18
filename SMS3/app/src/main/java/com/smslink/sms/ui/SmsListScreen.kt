package com.smslink.sms.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.core.model.Device
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import com.smslink.sms.SmsUiState
import com.smslink.sms.SmsViewModel
import com.smslink.sms.SyncState
import com.smslink.sms.ViewMode
import java.text.SimpleDateFormat
import java.util.*

/**
 * 短信列表页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsListScreen(
    viewModel: SmsViewModel = hiltViewModel(),
    onMessageClick: (Message) -> Unit = {},
    onComposeClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    var showDeviceSelector by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("短信")
                        Text(
                            text = "Messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 同步状态指示器
                    when (syncState) {
                        is SyncState.Syncing -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        else -> {}
                    }

                    // 设备选择器
                    if (connectedDevices.isNotEmpty()) {
                        IconButton(onClick = { showDeviceSelector = true }) {
                            Icon(Icons.Default.Smartphone, contentDescription = "Select Device")
                        }
                    }

                    // 视图模式切换
                    TextButton(onClick = {
                        viewModel.setViewMode(
                            if (viewMode == ViewMode.CONVERSATIONS) ViewMode.MESSAGES else ViewMode.CONVERSATIONS
                        )
                    }) {
                        Icon(
                            if (viewMode == ViewMode.CONVERSATIONS) Icons.Default.ViewList else Icons.Default.Forum,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (viewMode == ViewMode.CONVERSATIONS) "列表" else "会话")
                    }

                    // 刷新按钮
                    IconButton(onClick = { viewModel.syncFromSystem() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onComposeClick) {
                Icon(Icons.Default.Add, contentDescription = "New Message")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 设备信息栏
            if (selectedDevice != null) {
                DeviceInfoBar(
                    device = selectedDevice!!,
                    onDismiss = { viewModel.selectDevice(null) }
                )
            }

            when (uiState) {
                is SmsUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SmsUiState.Empty -> {
                    EmptyState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SmsUiState.PermissionRequired -> {
                    PermissionRequiredState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SmsUiState.Error -> {
                    ErrorState(
                        message = (uiState as SmsUiState.Error).message,
                        onRetry = { viewModel.loadMessages() },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SmsUiState.Success -> {
                    MessageList(
                        messages = messages,
                        onMessageClick = onMessageClick,
                        onMarkAsRead = { viewModel.markAsRead(it) }
                    )
                }
            }
        }

        // 设备选择对话框
        if (showDeviceSelector) {
            DeviceSelectorDialog(
                devices = connectedDevices,
                selectedDevice = selectedDevice,
                onDeviceSelected = { device ->
                    viewModel.selectDevice(device)
                    showDeviceSelector = false
                },
                onDismiss = { showDeviceSelector = false }
            )
        }
    }
}

/**
 * 设备信息栏
 */
@Composable
fun DeviceInfoBar(
    device: Device,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sending from: ${device.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Clear")
            }
        }
    }
}

/**
 * 设备选择对话框
 */
@Composable
fun DeviceSelectorDialog(
    devices: List<Device>,
    selectedDevice: Device?,
    onDeviceSelected: (Device?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Device") },
        text = {
            Column {
                // 本地设备选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(null) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedDevice == null,
                        onClick = { onDeviceSelected(null) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("This device")
                }

                // 其他设备
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDevice?.id == device.id,
                            onClick = { onDeviceSelected(device) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(device.name)
                            Text(
                                text = device.type.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
 * 消息列表
 */
@Composable
private fun MessageList(
    messages: List<Message>,
    onMessageClick: (Message) -> Unit,
    onMarkAsRead: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageItem(
                message = message,
                onClick = {
                    onMessageClick(message)
                    if (!message.read) {
                        onMarkAsRead(message.id)
                    }
                }
            )
            Divider()
        }
    }
}

/**
 * 消息项
 */
@Composable
private fun MessageItem(
    message: Message,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (message.read) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = message.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (message.read) {
                        androidx.compose.ui.text.font.FontWeight.Normal
                    } else {
                        androidx.compose.ui.text.font.FontWeight.Bold
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                MessageTypeIndicator(type = message.type)
            }
        }
    }
}

/**
 * 消息类型指示器
 */
@Composable
private fun MessageTypeIndicator(type: MessageType) {
    val text = when (type) {
        MessageType.INBOX -> "Received"
        MessageType.SENT -> "Sent"
        MessageType.DRAFT -> "Draft"
        MessageType.OUTBOX -> "Sending"
        MessageType.FAILED -> "Failed"
    }

    val color = when (type) {
        MessageType.FAILED -> MaterialTheme.colorScheme.error
        MessageType.SENT -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/**
 * 空状态
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No messages",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your messages will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 权限需求状态
 */
@Composable
private fun PermissionRequiredState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Permission Required",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SMS permissions are required to view and send messages",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 错误状态
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/**
 * 格式化时间戳
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
