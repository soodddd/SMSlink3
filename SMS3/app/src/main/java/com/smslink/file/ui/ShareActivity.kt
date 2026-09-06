package com.smslink.file.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
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
import com.smslink.device.IDeviceManager
import com.smslink.device.observeLiveConnections
import com.smslink.core.log.ILogger
import com.smslink.core.model.TransferState
import com.smslink.file.IFileTransferManager
import com.smslink.file.FilePacketCodec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
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

    @Inject
    lateinit var deviceManager: IDeviceManager

    private var temporaryFiles: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取分享的文件
        val sharedFiles = getSharedFiles(intent)
        temporaryFiles = sharedFiles

        if (sharedFiles.isEmpty()) {
            logger.e("ShareActivity", "No files to share")
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ShareScreen(
                    files = sharedFiles,
                    deviceManager = deviceManager,
                    onDeviceSelected = { deviceId ->
                        sendFiles(sharedFiles, deviceId)
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        // A file that was never handed to the transfer manager is safe to
        // remove. Failed transfers are removed from this cleanup list and
        // deliberately kept in filesDir so the history Retry action can use
        // the original bytes after this activity closes.
        temporaryFiles.forEach { file -> runCatching { file.delete() } }
        super.onDestroy()
    }

    /**
     * 获取分享的文件
     */
    private fun getSharedFiles(intent: Intent): List<File> {
        val files = mutableListOf<File>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                    getFileFromUri(uri)?.let { files.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach { uri ->
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
        var tempFile: File? = null
        return try {
            val declaredSize = contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { descriptor -> descriptor.length.takeIf { it >= 0L } }
            if (declaredSize != null && declaredSize > FilePacketCodec.MAX_FILE_SIZE) {
                throw IllegalArgumentException("Shared file exceeds the transfer limit")
            }

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val minimumFreeBytes = 8L * 1024 * 1024
            val stagingBudget = (availableBytes - minimumFreeBytes).coerceAtLeast(0L)
            if (declaredSize != null && declaredSize > stagingBudget) {
                throw IllegalStateException("Not enough storage for the shared file")
            }

            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = sanitizeFileName(getFileName(uri))
            val stagingDirectory = File(filesDir, "share-staging").apply { mkdirs() }
            val outputFile = File(stagingDirectory, "share-${UUID.randomUUID()}-$fileName")
            tempFile = outputFile

            inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > FilePacketCodec.MAX_FILE_SIZE || copied > stagingBudget) {
                            throw IllegalStateException("Shared file exceeds the staging limit")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            outputFile
        } catch (e: Exception) {
            tempFile?.let { runCatching { it.delete() } }
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

    private fun sanitizeFileName(value: String): String = value
        .replace('/', '_')
        .replace('\\', '_')
        .replace("..", "_")
        .replace('\u0000'.toString(), "")
        .trim()
        .take(255)
        .ifBlank { "shared_file" }

    /**
     * 发送文件
     */
    private fun sendFiles(files: List<File>, deviceId: String) {
        lifecycleScope.launch {
            files.forEach { file ->
                var terminalState: TransferState? = null
                try {
                    fileTransferManager.sendFile(file, deviceId).collect { transfer ->
                        terminalState = transfer.state
                        logger.i("ShareActivity", "Transfer progress: ${transfer.progress}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("ShareActivity", "Failed to send ${file.name}: ${e.message}")
                } finally {
                    if (terminalState == TransferState.COMPLETED) {
                        runCatching { file.delete() }
                    }
                    // Keep failed/cancelled source files for the durable
                    // transfer-history retry action. Files still present in
                    // temporaryFiles are only the pre-send cancellation case.
                    temporaryFiles = temporaryFiles.filterNot { it.absolutePath == file.absolutePath }
                }
            }
            finish()
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
    deviceManager: IDeviceManager,
    onDeviceSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    val devices by produceState(initialValue = emptyList<ShareDevice>(), deviceManager) {
        deviceManager.observeLiveConnections().collect { connected ->
            value = connected
                .filter { it.isPaired }
                .map { device ->
                    ShareDevice(
                        id = device.id,
                        name = device.name,
                        connected = true
                    )
                }
                .distinctBy { it.id }
        }
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

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有已连接的已配对设备",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
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
}

/**
 * 设备项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceItem(
    device: ShareDevice,
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
data class ShareDevice(
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
