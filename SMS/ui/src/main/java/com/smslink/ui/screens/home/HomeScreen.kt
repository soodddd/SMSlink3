package com.smslink.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smslink.ui.components.*

/**
 * 首页 - 设备管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAddDevice: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS-Link") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddDevice,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加设备")
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 连接状态卡片
            ConnectionStatusCard(
                connectionState = uiState.connectionState,
                currentRole = uiState.currentRole,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            // 设备列表
            when {
                uiState.isLoading -> {
                    LoadingIndicator()
                }
                uiState.pairedDevices.isEmpty() -> {
                    EmptyState(
                        message = "暂无已配对设备\n点击右下角按钮添加设备",
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.pairedDevices, key = { it.deviceId }) { device ->
                            DeviceCard(
                                deviceName = device.deviceName,
                                deviceType = device.deviceType,
                                isConnected = device.isConnected,
                                lastSeen = device.lastSeenText,
                                onClick = { onDeviceClick(device.deviceId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 连接状态卡片
 */
@Composable
private fun ConnectionStatusCard(
    connectionState: String,
    currentRole: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                Text(
                    text = "连接状态",
                    style = MaterialTheme.typography.titleMedium
                )
                ConnectionStatusIndicator(
                    isConnected = connectionState == "已连接"
                )
            }

            Text(
                text = connectionState,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "当前角色: $currentRole",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * UI 状态
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val connectionState: String = "未连接",
    val currentRole: String = "未配对",
    val pairedDevices: List<DeviceUiModel> = emptyList(),
    val error: String? = null
)

/**
 * 设备 UI 模型
 */
data class DeviceUiModel(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val isConnected: Boolean,
    val lastSeenText: String
)
