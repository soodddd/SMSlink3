package com.smslink.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit = {},
    onNavigateToDiagnostic: () -> Unit = {}
) {
    val context = LocalContext.current
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isDarkTheme by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf("中文") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // 权限中心
            item {
                SettingsSection(title = "权限与安全")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "权限中心",
                    subtitle = "管理应用权限",
                    onClick = onNavigateToPermissions
                )
                Divider()
            }

            // 连接设置
            item {
                SettingsSection(title = "连接")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Link,
                    title = "连接策略",
                    subtitle = "配置设备连接方式",
                    onClick = { /* TODO */ }
                )
                Divider()
            }

            // 同步设置
            item {
                SettingsSection(title = "同步")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = "通知同步策略",
                    subtitle = "配置通知同步规则",
                    onClick = onNavigateToNotificationSettings
                )
                Divider()
            }

            // 显示设置
            item {
                SettingsSection(title = "显示")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = "主题",
                    subtitle = if (isDarkTheme) "深色模式" else "浅色模式",
                    onClick = { showThemeDialog = true }
                )
                Divider()
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "语言",
                    subtitle = currentLanguage,
                    onClick = { showLanguageDialog = true }
                )
                Divider()
            }

            // 高级设置
            item {
                SettingsSection(title = "高级")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = "诊断日志",
                    subtitle = "查看应用日志",
                    onClick = onNavigateToDiagnostic
                )
                Divider()
            }

            // 关于
            item {
                SettingsSection(title = "关于")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "关于 SMS-link",
                    subtitle = "版本 1.0.0",
                    onClick = { /* TODO */ }
                )
                Divider()
            }
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column {
                    RadioButtonItem(
                        text = "浅色模式",
                        selected = !isDarkTheme,
                        onClick = {
                            isDarkTheme = false
                            showThemeDialog = false
                        }
                    )
                    RadioButtonItem(
                        text = "深色模式",
                        selected = isDarkTheme,
                        onClick = {
                            isDarkTheme = true
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 语言选择对话框
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("选择语言") },
            text = {
                Column {
                    RadioButtonItem(
                        text = "中文",
                        selected = currentLanguage == "中文",
                        onClick = {
                            currentLanguage = "中文"
                            showLanguageDialog = false
                        }
                    )
                    RadioButtonItem(
                        text = "English",
                        selected = currentLanguage == "English",
                        onClick = {
                            currentLanguage = "English"
                            showLanguageDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 设置分组标题
 */
@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * 设置项
 */
@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
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

/**
 * 单选按钮项
 */
@Composable
private fun RadioButtonItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
    }
}
