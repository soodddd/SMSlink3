package com.smslink.ui.screens.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smslink.ui.components.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 通知历史页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    uiState: NotificationsUiState,
    onSearch: (String) -> Unit,
    onFilterDevice: (String?) -> Unit,
    onNotificationClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知历史") },
                actions = {
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索栏
            if (showSearchBar) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        onSearch(it)
                    },
                    placeholder = { Text("搜索通知...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    singleLine = true
                )
            }

            // 设备筛选
            if (uiState.availableDevices.isNotEmpty()) {
                DeviceFilterChips(
                    devices = uiState.availableDevices,
                    selectedDevice = uiState.selectedDeviceFilter,
                    onDeviceSelected = onFilterDevice,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 通知列表
            when {
                uiState.isLoading -> {
                    LoadingIndicator()
                }
                uiState.notifications.isEmpty() -> {
                    EmptyState(
                        message = if (searchQuery.isNotEmpty()) "未找到匹配的通知" else "暂无通知历史",
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.notifications, key = { it.id }) { notification ->
                            NotificationCard(
                                notification = notification,
                                onClick = { onNotificationClick(notification.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceFilterChips(
    devices: List<String>,
    selectedDevice: String?,
    onDeviceSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedDevice == null,
            onClick = { onDeviceSelected(null) },
            label = { Text("全部") }
        )
        devices.forEach { device ->
            FilterChip(
                selected = selectedDevice == device,
                onClick = { onDeviceSelected(device) },
                label = { Text(device) }
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notification: NotificationUiModel,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.appName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = notification.timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = notification.title,
                style = MaterialTheme.typography.bodyLarge
            )

            if (notification.text.isNotEmpty()) {
                Text(
                    text = notification.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Text(
                text = "来自: ${notification.deviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 通知 UI 状态
 */
data class NotificationsUiState(
    val isLoading: Boolean = false,
    val notifications: List<NotificationUiModel> = emptyList(),
    val availableDevices: List<String> = emptyList(),
    val selectedDeviceFilter: String? = null,
    val searchQuery: String = ""
)

/**
 * 通知 UI 模型
 */
data class NotificationUiModel(
    val id: String,
    val appName: String,
    val title: String,
    val text: String,
    val deviceName: String,
    val timestamp: Long,
    val timeText: String
)
