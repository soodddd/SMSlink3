package com.smslink.ui.connect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.smslink.call.ui.CallHistoryScreen
import com.smslink.file.ui.FileTransferScreen
import com.smslink.sms.ui.SmsListScreen

/**
 * 互联页面
 * 包含短信、文件、通话三个分段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen() {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("短信", "文件", "通话")

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = { Text("互联") }
        )

        // 分段控制器
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        // 内容区域
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTabIndex) {
                0 -> SmsListScreen(
                    onMessageClick = { /* TODO: 导航到短信详情 */ },
                    onComposeClick = { /* TODO: 打开新建短信 */ }
                )
                1 -> {
                    // FileTransferScreen 需要 deviceId 参数
                    // 这里使用一个临时的空字符串，实际应该从设备选择器获取
                    FileTransferScreen(
                        deviceId = "" // TODO: 从设备选择器获取实际的 deviceId
                    )
                }
                2 -> CallHistoryScreen()
            }
        }
    }
}
