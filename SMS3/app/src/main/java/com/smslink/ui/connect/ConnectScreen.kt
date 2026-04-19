package com.smslink.ui.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.call.ui.CallHistoryScreen
import com.smslink.device.DeviceViewModel
import com.smslink.file.ui.FileTransferScreen
import com.smslink.sms.ui.SmsListScreen

/**
 * 互联页面
 * 包含短信、文件、通话三个分段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    val pairedDevices by deviceViewModel.pairedDevices.collectAsState()
    val connectedDevices by deviceViewModel.connectedDevices.collectAsState()
    val resolvedDeviceId = connectedDevices.firstOrNull()?.id
        ?: pairedDevices.firstOrNull()?.id
        ?: ""

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("短信", "文件", "通话")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("互联") }
        )

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

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTabIndex) {
                0 -> SmsListScreen(
                    onMessageClick = { },
                    onComposeClick = { }
                )
                1 -> FileTransferScreen(deviceId = resolvedDeviceId)
                2 -> CallHistoryScreen()
            }
        }
    }
}
