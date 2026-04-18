package com.smslink.file.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smslink.core.model.FileTransfer

/**
 * 文件接收确认对话框
 */
@Composable
fun FileReceiveConfirmDialog(
    transfer: FileTransfer,
    deviceName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = getFileIcon(transfer.mimeType),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Incoming File")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Do you want to receive this file?",
                    style = MaterialTheme.typography.bodyMedium
                )

                Divider()

                // 文件信息
                FileInfoRow(
                    label = "File name",
                    value = transfer.fileName
                )

                FileInfoRow(
                    label = "File size",
                    value = formatFileSize(transfer.fileSize)
                )

                FileInfoRow(
                    label = "File type",
                    value = getFileTypeDescription(transfer.mimeType)
                )

                FileInfoRow(
                    label = "From device",
                    value = deviceName
                )

                Divider()

                // 警告信息
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Only accept files from trusted devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accept")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reject")
            }
        }
    )
}

/**
 * 文件信息行
 */
@Composable
private fun FileInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 获取文件图标
 */
private fun getFileIcon(mimeType: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        mimeType.startsWith("image/") -> Icons.Default.Image
        mimeType.startsWith("video/") -> Icons.Default.VideoFile
        mimeType.startsWith("audio/") -> Icons.Default.AudioFile
        mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
        mimeType.startsWith("text/") -> Icons.Default.TextSnippet
        mimeType.contains("zip") || mimeType.contains("compressed") -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

/**
 * 获取文件类型描述
 */
private fun getFileTypeDescription(mimeType: String): String {
    return when {
        mimeType.startsWith("image/") -> "Image"
        mimeType.startsWith("video/") -> "Video"
        mimeType.startsWith("audio/") -> "Audio"
        mimeType == "application/pdf" -> "PDF Document"
        mimeType.startsWith("text/") -> "Text File"
        mimeType.contains("zip") -> "Compressed Archive"
        mimeType.contains("document") -> "Document"
        else -> mimeType
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
