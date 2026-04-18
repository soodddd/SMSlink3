package com.smslink.notification.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.notification.NotificationFilter
import com.smslink.notification.NotificationViewModel
import com.smslink.notification.SyncStatus

/**
 * 通知设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val preferences = context.getSharedPreferences("smslink_prefs", Context.MODE_PRIVATE)
    val isListening by viewModel.isListening.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val selectedDevices by viewModel.selectedDevices.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    var dualAppSuppress by remember {
        mutableStateOf(preferences.getBoolean("dual_app_suppress", true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 监听状态
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Notification Sync Service",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isListening) "Active" else "Inactive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isListening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.startSyncService() },
                                enabled = !isListening
                            ) {
                                Text("Start")
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopSyncService() },
                                enabled = isListening
                            ) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }

            // 同步状态
            item {
                when (syncStatus) {
                    is SyncStatus.Syncing -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text("Syncing notifications...")
                            }
                        }
                    }
                    is SyncStatus.Success -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                text = "Sync successful",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    is SyncStatus.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = "Sync error: ${(syncStatus as SyncStatus.Error).message}",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }
            }

            // 权限设置
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Notification Access",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Grant notification access permission in system settings to enable notification sync.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Open Settings")
                        }
                    }
                }
            }

            // 双端应用抑制
            item {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dual-App Suppression",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Suppress notifications for apps installed on both devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dualAppSuppress,
                            onCheckedChange = { enabled ->
                                dualAppSuppress = enabled
                                preferences.edit()
                                    .putBoolean("dual_app_suppress", enabled)
                                    .apply()
                            }
                        )
                    }
                }
            }

            // 设备选择
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sync Target Devices",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(
                                onClick = {
                                    if (selectedDevices.size == connectedDevices.size) {
                                        viewModel.deselectAllDevices()
                                    } else {
                                        viewModel.selectAllDevices()
                                    }
                                }
                            ) {
                                Text(
                                    if (selectedDevices.size == connectedDevices.size) "Deselect All"
                                    else "Select All"
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Choose which devices receive notification sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (connectedDevices.isEmpty()) {
                            Text(
                                text = "No connected devices",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            connectedDevices.forEach { device ->
                                DeviceSelectionItem(
                                    deviceName = device.name,
                                    deviceType = device.type.toString(),
                                    isSelected = selectedDevices.contains(device.id),
                                    onToggle = { viewModel.toggleDeviceSelection(device.id) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            // 过滤模式
            item {
                FilterModeCard(preferences)
            }

            // 应用过滤说明
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "App Filtering",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Control which apps can sync notifications to other devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设备选择项
 */
@Composable
private fun DeviceSelectionItem(
    deviceName: String,
    deviceType: String,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = deviceType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

/**
 * 过滤模式卡片
 */
@Composable
private fun FilterModeCard(preferences: SharedPreferences) {
    var selectedMode by remember {
        mutableStateOf(
            preferences.getString(
                NotificationFilter.PREF_FILTER_MODE,
                NotificationFilter.FILTER_MODE_ALL
            ) ?: NotificationFilter.FILTER_MODE_ALL
        )
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Filter Mode",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            FilterModeOption(
                title = "Sync All",
                description = "Sync notifications from all apps",
                mode = NotificationFilter.FILTER_MODE_ALL,
                selectedMode = selectedMode,
                onSelect = {
                    selectedMode = it
                    preferences.edit()
                        .putString(NotificationFilter.PREF_FILTER_MODE, it)
                        .apply()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            FilterModeOption(
                title = "Whitelist",
                description = "Only sync notifications from selected apps",
                mode = NotificationFilter.FILTER_MODE_WHITELIST,
                selectedMode = selectedMode,
                onSelect = {
                    selectedMode = it
                    preferences.edit()
                        .putString(NotificationFilter.PREF_FILTER_MODE, it)
                        .apply()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            FilterModeOption(
                title = "Blacklist",
                description = "Sync all except selected apps",
                mode = NotificationFilter.FILTER_MODE_BLACKLIST,
                selectedMode = selectedMode,
                onSelect = {
                    selectedMode = it
                    preferences.edit()
                        .putString(NotificationFilter.PREF_FILTER_MODE, it)
                        .apply()
                }
            )
        }
    }
}

/**
 * 过滤模式选项
 */
@Composable
private fun FilterModeOption(
    title: String,
    description: String,
    mode: String,
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) },
        color = if (selectedMode == mode) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectedMode == mode) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
