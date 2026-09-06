package com.smslink.file.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState

/**
 * 文件传输实体
 * 用于持久化文件传输记录
 */
@Entity(
    tableName = "file_transfers",
    indices = [
        Index(value = ["state"]),
        Index(value = ["deviceId"]),
        Index(value = ["timestamp"])
    ]
)
data class FileTransferEntity(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val filePath: String?,
    val fileSize: Long,
    val mimeType: String,
    val deviceId: String,
    val direction: TransferDirection,
    val state: TransferState,
    val progress: Float,
    val bytesTransferred: Long,
    val timestamp: Long,
    val errorMessage: String? = null,
    val resumeSupported: Boolean = true,
    val linkType: String? = null, // WIFI_LAN, WIFI_HOTSPOT, BLUETOOTH
    val retryCount: Int = 0,
    val lastError: String? = null,
    val fileHash: String? = null,
    val nextSequence: Int = 0,
    val protocolVersion: Int = 2
)
