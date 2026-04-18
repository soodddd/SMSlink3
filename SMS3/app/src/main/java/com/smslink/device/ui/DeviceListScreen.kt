package com.smslink.device.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.device.DeviceUiState
import com.smslink.device.DeviceViewModel
import com.smslink.device.DiscoveredDevice

/**
 * 设备列表页面
 * 显示已配对设备和发现的设备
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceViewModel = hiltViewModel(),
    debugPairCode: String? = null,
    onDeviceClick: (Device) -> Unit = {}
) {
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val localDevice by viewModel.localDevice.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()

    var showQRDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var pendingDebugPairCode by remember { mutableStateOf<String?>(null) }
    var consumedDebugPairCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshPendingRequests()
    }

    LaunchedEffect(debugPairCode, discoveredDevices) {
        if (!debugPairCode.isNullOrBlank() && discoveredDevices.isNotEmpty() && debugPairCode != consumedDebugPairCode) {
            val targetDeviceId = extractDebugPairDeviceId(debugPairCode)
            showPairDialog = targetDeviceId
                ?.let { deviceId -> discoveredDevices.firstOrNull { it.device.id == deviceId } }
                ?: discoveredDevices.first()
            pendingDebugPairCode = debugPairCode
            consumedDebugPairCode = debugPairCode
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备管理") },
                actions = {
                    // 二维码配对按钮
                    TextButton(onClick = { showQRDialog = true }) {
                        Icon(Icons.Default.QrCode, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("二维码")
                    }
                    IconButton(
                        onClick = {
                            if (uiState is DeviceUiState.Discovering) {
                                viewModel.stopDiscovery()
                            } else {
                                viewModel.startDiscovery()
                            }
                        }
                    ) {
                        Icon(
                            if (uiState is DeviceUiState.Discovering)
                                Icons.Default.Stop
                            else
                                Icons.Default.Search,
                            contentDescription = "搜索设备"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 状态提示
            item {
                when (uiState) {
                    is DeviceUiState.Discovering -> {
                        InfoCard("正在搜索设备...", MaterialTheme.colorScheme.primary)
                    }
                    is DeviceUiState.Pairing -> {
                        InfoCard("正在配对...", MaterialTheme.colorScheme.secondary)
                    }
                    is DeviceUiState.PairSuccess -> {
                        InfoCard(
                            (uiState as DeviceUiState.PairSuccess).message,
                            MaterialTheme.colorScheme.tertiary
                        )
                    }
                    is DeviceUiState.PairError -> {
                        InfoCard(
                            (uiState as DeviceUiState.PairError).message,
                            MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }

            // 待处理的配对请求
            if (pendingRequests.isNotEmpty()) {
                item {
                    Text(
                        "配对请求",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(pendingRequests) { request ->
                    PairRequestCard(
                        request = request,
                        onAccept = { viewModel.acceptPairRequest(request.requestId) },
                        onReject = { viewModel.rejectPairRequest(request.requestId) }
                    )
                }
            }

            // 已配对设备
            item {
                Text(
                    "已配对设备 (${pairedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                Text(
                    "本机设备",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                DeviceCard(
                    device = localDevice,
                    isConnected = true,
                    onClick = { },
                    onRemove = null,
                    onRoleChange = { role -> viewModel.setDeviceRole(localDevice.id, role) }
                )
            }

            if (pairedDevices.isEmpty()) {
                item {
                    EmptyStateCard("暂无已配对设备")
                }
            } else {
                items(pairedDevices) { device ->
                    DeviceCard(
                        device = device,
                        isConnected = connectedDevices.any { it.id == device.id },
                        onClick = { onDeviceClick(device) },
                        onRemove = { viewModel.removeDevice(device.id) },
                        onRoleChange = { role -> viewModel.setDeviceRole(device.id, role) }
                    )
                }
            }

            // 发现的设备
            if (uiState is DeviceUiState.Discovering) {
                item {
                    Text(
                        "发现的设备 (${discoveredDevices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (discoveredDevices.isEmpty()) {
                    item {
                        EmptyStateCard("未发现设备")
                    }
                } else {
                    items(discoveredDevices) { discoveredDevice ->
                        DiscoveredDeviceCard(
                            discoveredDevice = discoveredDevice,
                            onPair = { showPairDialog = discoveredDevice }
                        )
                    }
                }
            }
        }
    }

    // 配对二维码对话框
    if (showQRDialog) {
        QRCodeDialog(
            viewModel = viewModel,
            onDismiss = { showQRDialog = false }
        )
    }

    // 配对对话框
    showPairDialog?.let { device ->
        PairingDialog(
            device = device,
            initialQrCode = pendingDebugPairCode,
            onConfirm = { qrCode ->
                viewModel.pairDevice(device.device.id, qrCode)
                showPairDialog = null
                pendingDebugPairCode = null
            },
            onDismiss = {
                showPairDialog = null
                pendingDebugPairCode = null
            }
        )
    }
}

private fun extractDebugPairDeviceId(debugPairCode: String): String? {
    val deviceIdPattern = Regex(""""deviceId"\s*:\s*"([^"]+)"""")
    return deviceIdPattern.find(debugPairCode)?.groupValues?.getOrNull(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: Device,
    isConnected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onRoleChange: ((DeviceRole) -> Unit)? = null
) {
    var roleMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (device.type) {
                        DeviceType.ANDROID -> Icons.Default.PhoneAndroid
                        DeviceType.PHONE -> Icons.Default.PhoneAndroid
                        DeviceType.TABLET -> Icons.Default.Tablet
                        DeviceType.FOLDABLE -> Icons.Default.PhoneAndroid
                    },
                    contentDescription = null,
                    tint = if (isConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "连接状态:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isConnected) "已连接" else "未连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "角色: ${roleLabel(device.role)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (onRoleChange != null) {
                Box {
                    IconButton(onClick = { roleMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "角色菜单")
                    }
                    DropdownMenu(
                        expanded = roleMenuExpanded,
                        onDismissRequest = { roleMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("设为主设备") },
                            onClick = {
                                roleMenuExpanded = false
                                onRoleChange(DeviceRole.MAIN)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("设为副设备") },
                            onClick = {
                                roleMenuExpanded = false
                                onRoleChange(DeviceRole.SECONDARY)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("设为蜂窝源设备") },
                            onClick = {
                                roleMenuExpanded = false
                                onRoleChange(DeviceRole.CELLULAR_SOURCE)
                            }
                        )
                    }
                }
            }

            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "移除设备")
                }
            }
        }
    }
}

private fun roleLabel(role: DeviceRole): String {
    return when (role) {
        DeviceRole.MAIN -> "主设备"
        DeviceRole.SECONDARY -> "副设备"
        DeviceRole.CELLULAR_SOURCE -> "蜂窝源设备"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveredDeviceCard(
    discoveredDevice: DiscoveredDevice,
    onPair: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onPair
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (discoveredDevice.device.type) {
                        DeviceType.ANDROID -> Icons.Default.PhoneAndroid
                        DeviceType.PHONE -> Icons.Default.PhoneAndroid
                        DeviceType.TABLET -> Icons.Default.Tablet
                        DeviceType.FOLDABLE -> Icons.Default.PhoneAndroid
                    },
                    contentDescription = null
                )

                Column {
                    Text(
                        text = discoveredDevice.device.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = discoveredDevice.ipAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(onClick = onPair) {
                Text("配对")
            }
        }
    }
}

@Composable
fun PairRequestCard(
    request: com.smslink.device.PairRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${request.deviceName} 请求配对",
                style = MaterialTheme.typography.bodyLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onReject) {
                    Text("拒绝")
                }
                Button(onClick = onAccept) {
                    Text("接受")
                }
            }
        }
    }
}

@Composable
fun InfoCard(message: String, color: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = color
        )
    }
}

@Composable
fun EmptyStateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
