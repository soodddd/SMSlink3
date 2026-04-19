package com.smslink.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文件传输实体
 */
@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String?,
    val deviceId: String,
    val deviceName: String,
    val status: String, // TransferStatus.name
    val progress: Int,
    val transferredBytes: Long,
    val speed: Long,
    val timestamp: Long,
    val isFolder: Boolean,
    val fileCount: Int,
    val errorMessage: String?,
    val direction: String // TransferDirection.name
)
