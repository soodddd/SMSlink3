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
    val timestamp: Long
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
