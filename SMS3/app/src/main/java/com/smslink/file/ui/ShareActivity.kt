package com.smslink.file.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.smslink.core.log.ILogger
import com.smslink.file.IFileTransferManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 分享活动
 * 处理从其他应用分享文件到 SMS-link
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {

    @Inject
    lateinit var fileTransferManager: IFileTransferManager

    @Inject
    lateinit var logger: ILogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取分享的文件
        val sharedFiles = getSharedFiles(intent)

        if (sharedFiles.isEmpty()) {
            logger.e("ShareActivity", "No files to share")
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ShareScreen(
                    files = sharedFiles,
                    onDeviceSelected = { deviceId ->
                        sendFiles(sharedFiles, deviceId)
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    /**
     * 获取分享的文件
     */
    private fun getSharedFiles(intent: Intent): List<File> {
        val files = mutableListOf<File>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                    getFileFromUri(uri)?.let { files.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                    getFileFromUri(uri)?.let { files.add(it) }
                }
            }
        }

        return files
    }

    /**
     * 从 URI 获取文件
     */
    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileName(uri)
            val tempFile = File(cacheDir, fileName)

            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            tempFile
        } catch (e: Exception) {
            logger.e("ShareActivity", "Failed to get file from URI: ${e.message}")
            null
        }
    }

    /**
     * 获取文件名
     */
    private fun getFileName(uri: Uri): String {
        var fileName = "shared_file"

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }

        return fileName
    }

    /**
     * 发送文件
     */
    private fun sendFiles(files: List<File>, deviceId: String) {
        lifecycleScope.launch {
            try {
                files.forEach { file ->
                    fileTransferManager.sendFile(file, deviceId).collect { transfer ->
                        logger.i("ShareActivity", "Transfer progress: ${transfer.progress}")
                    }
                }
                finish()
            } catch (e: Exception) {
                logger.e("ShareActivity", "Failed to send files: ${e.message}")
                finish()
            }
        }
    }
}

/**
 * 分享界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    files: List<File>,
    onDeviceSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    // 模拟设备列表（实际应该从设备管理器获取）
    val devices = remember {
        listOf(
            Device("device1", "My Phone", true),
            Device("device2", "My Tablet", true),
            Device("device3", "My Laptop", false)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to Device") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 文件信息
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Files to share",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    files.forEach { file ->
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Total size: ${formatFileSize(files.sumOf { it.length() })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 设备列表
            Text(
                text = "Select device",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceItem(
                        device = device,
                        onClick = { onDeviceSelected(device.id) }
                    )
                }
            }
        }
    }
}

/**
 * 设备项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceItem(
    device: Device,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = device.connected,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Smartphone,
                contentDescription = null,
                tint = if (device.connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (device.connected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 设备数据类
 */
data class Device(
    val id: String,
    val name: String,
    val connected: Boolean
)

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
