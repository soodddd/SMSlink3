package com.smslink.core.model

/**
 * 文件传输
 */
data class FileTransfer(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val deviceId: String,
    val direction: TransferDirection,
    val state: TransferState,
    val progress: Float,
    val timestamp: Long,
    val bytesTransferred: Long = 0L,
    val errorMessage: String? = null,
    val filePath: String? = null,
    val fileHash: String? = null,
    val nextSequence: Int = 0,
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * 传输方向
 */
enum class TransferDirection {
    UPLOAD,     // 上传
    DOWNLOAD    // 下载
}

/**
 * 传输状态
 */
enum class TransferState {
    PENDING,    // 等待中
    TRANSFERRING, // 传输中
    COMPLETED,  // 已完成
    FAILED,     // 失败
    CANCELLED   // 已取消
}
