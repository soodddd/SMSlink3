package com.smslink.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smslink.core.model.Device

/**
 * 默认电话应用对话框
 */
@Composable
fun DefaultPhoneAppDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    rationale: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置默认电话应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rationale)
                Text(
                    text = "设置为默认电话应用后，SMS-link 可以：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("• 接听和挂断电话", style = MaterialTheme.typography.bodySmall)
                Text("• 控制通话状态（静音、保持）", style = MaterialTheme.typography.bodySmall)
                Text("• 远程控制其他设备的通话", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 设备选择器对话框
 */
@Composable
fun DeviceSelectorDialog(
    devices: List<Device>,
    selectedDeviceId: String?,
    onDeviceSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择设备") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 本地设备选项
                item {
                    DeviceSelectionItem(
                        deviceName = "本地设备",
                        isSelected = selectedDeviceId == null,
                        onClick = {
                            onDeviceSelected(null)
                        }
                    )
                }

                // 远程设备列表
                items(devices) { device ->
                    DeviceSelectionItem(
                        deviceName = device.name,
                        isSelected = selectedDeviceId == device.id,
                        onClick = {
                            onDeviceSelected(device.id)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 设备选择项
 */
@Composable
private fun DeviceSelectionItem(
    deviceName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodyLarge
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
