package com.smslink.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onRoleSwitchClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onFileTransferSettingsClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // 设备角色
            item {
                SettingsSection(title = "设备")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.PhoneAndroid,
                    title = "设备角色",
                    subtitle = uiState.currentRole,
                    onClick = onRoleSwitchClick
                )
            }

            // 通知设置
            item {
                SettingsSection(title = "通知")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "通知设置",
                    subtitle = "管理通知同步选项",
                    onClick = onNotificationSettingsClick
                )
            }

            // 文件传输设置
            item {
                SettingsSection(title = "文件传输")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Folder,
                    title = "文件传输设置",
                    subtitle = "保存位置、自动接收等",
                    onClick = onFileTransferSettingsClick
                )
            }

            // 权限管理
            item {
                SettingsSection(title = "权限")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "权限管理",
                    subtitle = "查看和管理应用权限",
                    onClick = onPermissionsClick
                )
            }

            // 外观
            item {
                SettingsSection(title = "外观")
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.DarkMode,
                    title = "深色模式",
                    checked = uiState.isDarkTheme,
                    onCheckedChange = onThemeChange
                )
            }

            // 关于
            item {
                SettingsSection(title = "关于")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "关于 SMS-Link",
                    subtitle = "版本 ${uiState.appVersion}",
                    onClick = onAboutClick
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * 设置 UI 状态
 */
data class SettingsUiState(
    val currentRole: String = "未配对",
    val isDarkTheme: Boolean = false,
    val appVersion: String = "1.0.0"
)
