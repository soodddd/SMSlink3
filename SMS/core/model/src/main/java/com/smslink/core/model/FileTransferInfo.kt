package com.smslink.core.model

/**
 * 文件传输信息模型
 */
data class FileTransferInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long, // 字节
    val mimeType: String?,
    val deviceId: String,
    val deviceName: String,
    val status: TransferStatus,
    val progress: Int = 0, // 0-100
    val transferredBytes: Long = 0,
    val speed: Long = 0, // 字节/秒
    val timestamp: Long,
    val isFolder: Boolean = false,
    val fileCount: Int = 1, // 文件夹中的文件数量
    val errorMessage: String? = null,
    val direction: TransferDirection = TransferDirection.SEND
)

/**
 * 传输状态
 */
enum class TransferStatus {
    PENDING,      // 等待中
    TRANSFERRING, // 传输中
    PAUSED,       // 已暂停
    COMPLETED,    // 已完成
    FAILED,       // 失败
    CANCELLED     // 已取消
}

/**
 * 传输方向
 */
enum class TransferDirection {
    SEND,    // 发送
    RECEIVE  // 接收
}
