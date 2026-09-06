package com.smslink.call.ui

import android.os.Build
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
import com.smslink.call.CallViewModel
import com.smslink.call.model.CallAction
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallLog
import com.smslink.core.model.CallStateType
import java.text.SimpleDateFormat
import java.util.*

/**
 * 通话记录界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    viewModel: CallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentCallState by viewModel.currentCallState.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsState()

    var showDefaultPhoneAppDialog by remember { mutableStateOf(false) }
    var showDeviceSelector by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startListening()
        viewModel.checkDefaultPhoneApp()
    }

    // 处理默认电话应用请求
    LaunchedEffect(uiState.defaultPhoneAppIntent) {
        uiState.defaultPhoneAppIntent?.let { intent ->
            context.startActivity(intent)
        }
    }

    // 显示默认电话应用引导对话框
    if (showDefaultPhoneAppDialog) {
        DefaultPhoneAppDialog(
            onDismiss = { showDefaultPhoneAppDialog = false },
            onConfirm = {
                viewModel.requestDefaultPhoneApp()
                showDefaultPhoneAppDialog = false
            },
            rationale = viewModel.getDefaultPhoneAppRationale()
        )
    }

    // 设备选择器
    if (showDeviceSelector) {
        DeviceSelectorDialog(
            devices = connectedDevices,
            selectedDeviceId = selectedDeviceId,
            onDeviceSelected = { deviceId ->
                viewModel.selectDevice(deviceId)
                showDeviceSelector = false
            },
            onDismiss = { showDeviceSelector = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通话记录") },
                actions = {
                    // 设备选择器
                    if (connectedDevices.isNotEmpty()) {
                        IconButton(onClick = { showDeviceSelector = true }) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = "选择设备"
                            )
                        }
                    }

                    // 默认电话应用状态
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !uiState.isDefaultPhoneApp) {
                        IconButton(onClick = { showDefaultPhoneAppDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "设置默认电话应用",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    IconButton(onClick = { viewModel.syncCallHistory() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "同步通话记录"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.callLogs.isEmpty() -> {
                    EmptyCallHistoryView(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    CallHistoryList(
                        callLogs = uiState.callLogs,
                        onCallClick = { viewModel.makeCall(it.phoneNumber) },
                        onDeleteClick = { viewModel.deleteCallLog(it) }
                    )
                }
            }

            // 当前通话状态显示
            currentCallState?.let { callState ->
                CurrentCallCard(
                    callState = callState,
                    isDefaultPhoneApp = uiState.isDefaultPhoneApp,
                    selectedDeviceId = selectedDeviceId,
                    onAnswerClick = { viewModel.answerCall(callState.callId) },
                    onEndClick = { viewModel.endCall(callState.callId) },
                    onMuteClick = { viewModel.muteCall(callState.callId) },
                    onUnmuteClick = { viewModel.unmuteCall(callState.callId) },
                    onHoldClick = { viewModel.holdCall(callState.callId) },
                    onResumeClick = { viewModel.resumeCall(callState.callId) },
                    onRemoteAnswer = { viewModel.sendRemoteControl(callState, CallAction.ANSWER) },
                    onRemoteEnd = { viewModel.sendRemoteControl(callState, CallAction.END) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }

            // 同步进度
            if (uiState.isSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }

            // 错误提示
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("关闭")
                        }
                    }
                ) {
                    Text(error)
                }
            }

            // 同步消息
            uiState.syncMessage?.let { message ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearSyncMessage() }) {
                            Text("关闭")
                        }
                    }
                ) {
                    Text(message)
                }
            }

            // 远程控制消息
            uiState.remoteControlMessage?.let { message ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearRemoteControlMessage() }) {
                            Text("关闭")
                        }
                    }
                ) {
                    Text(message)
                }
            }

            // 远程控制进度
            if (uiState.isRemoteControlling) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun CallHistoryList(
    callLogs: List<CallLog>,
    onCallClick: (CallLog) -> Unit,
    onDeleteClick: (CallLog) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(callLogs, key = { it.id }) { callLog ->
            CallLogItem(
                callLog = callLog,
                onCallClick = { onCallClick(callLog) },
                onDeleteClick = { onDeleteClick(callLog) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallLogItem(
    callLog: CallLog,
    onCallClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onCallClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (callLog.type) {
                        CallDirection.INCOMING -> Icons.Default.CallReceived
                        CallDirection.OUTGOING -> Icons.Default.CallMade
                        CallDirection.MISSED -> Icons.Default.CallMissed
                    },
                    contentDescription = null,
                    tint = when (callLog.type) {
                        CallDirection.INCOMING -> MaterialTheme.colorScheme.primary
                        CallDirection.OUTGOING -> MaterialTheme.colorScheme.secondary
                        CallDirection.MISSED -> MaterialTheme.colorScheme.error
                    }
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = callLog.contactName ?: callLog.phoneNumber,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (callLog.contactName != null) {
                        Text(
                            text = callLog.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatTimestamp(callLog.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (callLog.duration > 0) {
                            Text(
                                text = "• ${formatDuration(callLog.duration)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除"
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除通话记录") },
            text = { Text("确定要删除这条通话记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick()
                        showDeleteDialog = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun CurrentCallCard(
    callState: com.smslink.core.model.CallState,
    isDefaultPhoneApp: Boolean,
    selectedDeviceId: String?,
    onAnswerClick: () -> Unit,
    onEndClick: () -> Unit,
    onMuteClick: () -> Unit,
    onUnmuteClick: () -> Unit,
    onHoldClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRemoteAnswer: () -> Unit,
    onRemoteEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMuted by remember { mutableStateOf(false) }
    var isOnHold by remember { mutableStateOf(false) }
    var showRemoteControls by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 通话状态标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (callState.state) {
                        CallStateType.RINGING -> "来电中..."
                        CallStateType.OFFHOOK -> if (isOnHold) "通话保持中" else "通话中"
                        else -> "通话状态"
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                if (selectedDeviceId != null) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "远程控制",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 联系人信息
            Text(
                text = callState.contactName ?: callState.phoneNumber,
                style = MaterialTheme.typography.headlineSmall
            )

            if (callState.contactName != null) {
                Text(
                    text = callState.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 本地控制按钮
            if (isDefaultPhoneApp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 主要操作
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (callState.state == CallStateType.RINGING &&
                            callState.direction == com.smslink.core.model.CallDirection.INCOMING
                        ) {
                            FilledTonalButton(
                                onClick = onAnswerClick,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("接听")
                            }
                        }

                        FilledTonalButton(
                            onClick = onEndClick,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("挂断")
                        }
                    }

                    // 次要操作（通话中）
                    if (callState.state == CallStateType.OFFHOOK) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (isMuted) onUnmuteClick() else onMuteClick()
                                    isMuted = !isMuted
                                }
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isMuted) "取消静音" else "静音",
                                    tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (isOnHold) onResumeClick() else onHoldClick()
                                    isOnHold = !isOnHold
                                }
                            ) {
                                Icon(
                                    imageVector = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isOnHold) "恢复" else "保持",
                                    tint = if (isOnHold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 远程控制按钮
            if (selectedDeviceId != null) {
                Divider()

                TextButton(onClick = { showRemoteControls = !showRemoteControls }) {
                    Text(if (showRemoteControls) "隐藏远程控制" else "显示远程控制")
                    Icon(
                        imageVector = if (showRemoteControls) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }

                if (showRemoteControls) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (callState.state == CallStateType.RINGING &&
                            callState.direction == com.smslink.core.model.CallDirection.INCOMING
                        ) {
                            OutlinedButton(onClick = onRemoteAnswer) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("远程接听")
                            }
                        }

                        OutlinedButton(onClick = onRemoteEnd) {
                            Icon(Icons.Default.CallEnd, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("远程挂断")
                        }
                    }
                }
            }

            // 权限提示
            if (!isDefaultPhoneApp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Text(
                    text = "需要设置为默认电话应用才能控制通话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyCallHistoryView(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Phone,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "暂无通话记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "点击右上角刷新按钮同步通话记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        diff < 604800_000 -> "${diff / 86400_000} 天前"
        else -> {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3600}时${(seconds % 3600) / 60}分"
    }
}
