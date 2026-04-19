package com.smslink.ui.screens.pairing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smslink.ui.components.*

/**
 * 配对流程页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onBack: () -> Unit,
    onGenerateCode: () -> Unit,
    onEnterCode: (String) -> Unit,
    onDeviceSelected: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showEnterCodeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (uiState.step) {
                PairingStep.CHOOSE_METHOD -> {
                    ChooseMethodStep(
                        onGenerateCode = onGenerateCode,
                        onEnterCode = { showEnterCodeDialog = true }
                    )
                }
                PairingStep.WAITING_FOR_CODE -> {
                    WaitingForCodeStep(
                        code = uiState.pairingCode ?: "",
                        onCancel = onCancel
                    )
                }
                PairingStep.DISCOVERING -> {
                    DiscoveringStep(
                        devices = uiState.discoveredDevices,
                        onDeviceSelected = onDeviceSelected
                    )
                }
                PairingStep.PAIRING -> {
                    PairingStep(deviceName = uiState.selectedDeviceName ?: "")
                }
                PairingStep.SUCCESS -> {
                    SuccessStep(
                        deviceName = uiState.selectedDeviceName ?: "",
                        onDone = onBack
                    )
                }
                PairingStep.FAILED -> {
                    FailedStep(
                        error = uiState.error ?: "未知错误",
                        onRetry = onGenerateCode,
                        onCancel = onBack
                    )
                }
            }
        }
    }

    if (showEnterCodeDialog) {
        EnterPairingCodeDialog(
            onCodeEntered = {
                showEnterCodeDialog = false
                onEnterCode(it)
            },
            onDismiss = { showEnterCodeDialog = false }
        )
    }
}

@Composable
private fun ChooseMethodStep(
    onGenerateCode: () -> Unit,
    onEnterCode: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "选择配对方式",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Text(
            text = "请选择如何与另一台设备配对",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onGenerateCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("生成配对码", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(
            onClick = onEnterCode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("输入配对码", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WaitingForCodeStep(
    code: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "配对码",
            style = MaterialTheme.typography.headlineMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = "请在另一台设备上输入此配对码",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "5分钟后过期",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun DiscoveringStep(
    devices: List<DiscoveredDeviceUiModel>,
    onDeviceSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "发现的设备",
            style = MaterialTheme.typography.headlineMedium
        )

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在搜索设备...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        deviceName = device.deviceName,
                        deviceType = device.deviceType,
                        isConnected = false,
                        lastSeen = "刚刚发现",
                        onClick = { onDeviceSelected(device.deviceId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingStep(deviceName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Text(
            text = "正在配对",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "正在与 $deviceName 建立连接...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SuccessStep(
    deviceName: String,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.displayLarge,
            color = com.smslink.ui.theme.Success
        )
        Text(
            text = "配对成功",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "已成功与 $deviceName 配对",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("完成")
        }
    }
}

@Composable
private fun FailedStep(
    error: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "✗",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = "配对失败",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("重试")
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消")
        }
    }
}

/**
 * 配对步骤
 */
enum class PairingStep {
    CHOOSE_METHOD,
    WAITING_FOR_CODE,
    DISCOVERING,
    PAIRING,
    SUCCESS,
    FAILED
}

/**
 * 配对 UI 状态
 */
data class PairingUiState(
    val step: PairingStep = PairingStep.CHOOSE_METHOD,
    val pairingCode: String? = null,
    val discoveredDevices: List<DiscoveredDeviceUiModel> = emptyList(),
    val selectedDeviceName: String? = null,
    val error: String? = null
)

/**
 * 发现的设备 UI 模型
 */
data class DiscoveredDeviceUiModel(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String
)
