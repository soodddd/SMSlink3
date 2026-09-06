package com.smslink.ui.settings

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.BuildConfig
import com.smslink.network.connection.ConnectionPolicy

/** Settings that are persisted and consumed by the actual runtime services. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit = {},
    onNavigateToDiagnostic: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val connectionPolicy by viewModel.connectionPolicy.collectAsState()
    val darkTheme by viewModel.darkTheme.collectAsState()
    val language by viewModel.language.collectAsState()
    var showPolicyDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item { SettingsSection("权限与安全") }
            item {
                SettingsItem(
                    Icons.Default.Security,
                    "权限中心",
                    "管理短信、电话、蓝牙、相机和通知访问",
                    onNavigateToPermissions
                )
                Divider()
            }

            item { SettingsSection("连接") }
            item {
                SettingsItem(
                    Icons.Default.Link,
                    "连接策略",
                    connectionPolicy.description,
                    onClick = { showPolicyDialog = true }
                )
                Divider()
            }

            item { SettingsSection("同步") }
            item {
                SettingsItem(
                    Icons.Default.Sync,
                    "通知同步策略",
                    "配置通知同步规则",
                    onNavigateToNotificationSettings
                )
                Divider()
            }

            item { SettingsSection("显示") }
            item {
                SettingsItem(
                    Icons.Default.DarkMode,
                    "主题",
                    if (darkTheme) "深色模式" else "浅色模式",
                    onClick = { showThemeDialog = true }
                )
                Divider()
            }
            item {
                SettingsItem(
                    Icons.Default.Language,
                    "语言",
                    language,
                    onClick = { showLanguageDialog = true }
                )
                Divider()
            }

            item { SettingsSection("高级") }
            item {
                SettingsItem(
                    Icons.Default.BugReport,
                    "诊断日志",
                    "查看实时连接、同步和错误日志",
                    onNavigateToDiagnostic
                )
                Divider()
            }

            item { SettingsSection("关于") }
            item {
                SettingsItem(
                    Icons.Default.Info,
                    "关于 SMS-link",
                    "版本 ${BuildConfig.VERSION_NAME}",
                    onClick = { showAboutDialog = true }
                )
                Divider()
            }
        }
    }

    if (showPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPolicyDialog = false },
            title = { Text("连接策略") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ConnectionPolicy.values().forEach { option ->
                        RadioButtonItem(
                            text = "${option.title}\n${option.description}",
                            selected = option == connectionPolicy,
                            onClick = {
                                viewModel.setConnectionPolicy(option)
                                showPolicyDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPolicyDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column {
                    RadioButtonItem("浅色模式", !darkTheme) {
                        viewModel.setDarkTheme(false)
                        showThemeDialog = false
                        recreate(context)
                    }
                    RadioButtonItem("深色模式", darkTheme) {
                        viewModel.setDarkTheme(true)
                        showThemeDialog = false
                        recreate(context)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("语言") },
            text = {
                Column {
                    RadioButtonItem(
                        text = "中文（当前版本）",
                        selected = language == SettingsViewModel.LANGUAGE_ZH_CN,
                        onClick = {
                            viewModel.setLanguage(SettingsViewModel.LANGUAGE_ZH_CN)
                            showLanguageDialog = false
                        }
                    )
                    Text(
                        "当前版本界面仅提供中文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("SMS-link") },
            text = {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n\n" +
                        "Android 13+ 离线设备互联工具。短信、通知、文件和通话功能" +
                        "均以已配对设备和真实系统权限为前提。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("关闭") }
            }
        )
    }
}

private fun recreate(context: Context) {
    (context as? Activity)?.recreate()
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RadioButtonItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
