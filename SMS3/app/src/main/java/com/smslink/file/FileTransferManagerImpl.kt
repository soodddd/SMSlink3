package com.smslink.file

import android.content.Context
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import com.smslink.core.model.FileTransfer
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState
import com.smslink.file.data.FileRepository
import com.smslink.network.IConnectionManager
import com.smslink.network.connection.LinkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件传输管理器实现
 */
@Singleton
class FileTransferManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: IConnectionManager,
    private val fileRepository: FileRepository,
    private val logger: ILogger
) : IFileTransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransfers = mutableMapOf<String, Job>()

    init {
        scope.launch {
            connectionManager.receiveData().collect { (deviceId, data) ->
                handleReceivedData(deviceId, data)
            }
        }
    }

    companion object {
        private const val CHUNK_SIZE = 8192 // 8KB chunks
        private const val PROTOCOL_FILE_SEND = 0x01.toByte()
        private const val PROTOCOL_FILE_CHUNK = 0x02.toByte()
        private const val PROTOCOL_FILE_COMPLETE = 0x03.toByte()
        private const val PROTOCOL_FILE_ERROR = 0x04.toByte()
        private const val PROTOCOL_FILE_CANCEL = 0x05.toByte()
        private const val PROTOCOL_FILE_RESUME = 0x06.toByte()
        private const val PROTOCOL_FILE_RESUME_ACK = 0x07.toByte()
        private const val PROTOCOL_FILE_REQUEST = 0x08.toByte()
        private const val PROTOCOL_FILE_ACCEPT = 0x09.toByte()
        private const val PROTOCOL_FILE_REJECT = 0x0A.toByte()

        // 文件大小阈值
        private const val LARGE_FILE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val MEDIUM_FILE_SIZE = 1 * 1024 * 1024L // 1MB

        // 重试配置
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    /**
     * 批量发送文件
     */
    suspend fun sendFiles(files: List<File>, targetDeviceId: String): Flow<List<FileTransfer>> = flow {
        val transfers = mutableListOf<FileTransfer>()

        for (file in files) {
            sendFile(file, targetDeviceId).collect { transfer ->
                val index = transfers.indexOfFirst { it.id == transfer.id }
                if (index >= 0) {
                    transfers[index] = transfer
                } else {
                    transfers.add(transfer)
                }
                emit(transfers.toList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 发送文件
     */
    override fun sendFile(file: File, targetDeviceId: String): Flow<FileTransfer> = flow {
        val transferId = UUID.randomUUID().toString()
        if (!file.exists() || !file.canRead()) {
            val safePath = runCatching { file.path }.getOrDefault("<unknown>")
            logger.e("FileTransfer", "File not found or not readable: $safePath")
            throw IllegalArgumentException("File not found or not readable")
        }

        val fileName = resolveFileName(file)

        // 选择最佳链路
        val linkType = selectBestLink(file.length(), targetDeviceId)

        val transfer = FileTransfer(
            id = transferId,
            fileName = fileName,
            fileSize = file.length(),
            mimeType = getMimeType(file),
            deviceId = targetDeviceId,
            direction = TransferDirection.UPLOAD,
            state = TransferState.PENDING,
            progress = 0f,
            timestamp = System.currentTimeMillis()
        )

        // 保存初始记录
        fileRepository.saveTransferWithLink(transfer, file.absolutePath, 0L, linkType)
        emit(fileRepository.getTransferById(transferId) ?: transfer)

        // 启动传输任务
        val job = scope.launch {
            try {
                sendFileInternal(transferId, file, targetDeviceId, linkType)
            } catch (e: Exception) {
                logger.e("FileTransfer", "Send file failed: ${e.message}")
                fileRepository.updateTransfer(
                    transferId,
                    TransferState.FAILED,
                    0f,
                    0L,
                    e.message
                )
            }
        }

        activeTransfers[transferId] = job

        // 监听传输进度
        fileRepository.getActiveTransfers()
            .map { transfers -> transfers.find { it.id == transferId } }
            .filterNotNull()
            .collect { emit(it) }

    }.flowOn(Dispatchers.IO)

    /**
     * 接收文件
     */
    override fun receiveFile(transferId: String): Flow<FileTransfer> = flow {
        val transfer = fileRepository.getTransferById(transferId)
        if (transfer == null) {
            logger.e("FileTransfer", "Transfer not found: $transferId")
            throw IllegalArgumentException("Transfer not found")
        }

        if (transfer.direction == TransferDirection.DOWNLOAD && transfer.state == TransferState.PENDING) {
            val acceptPacket = buildFileAcceptPacket(transferId)
            connectionManager.sendData(transfer.deviceId, acceptPacket)
            fileRepository.updateTransfer(
                transferId,
                TransferState.TRANSFERRING,
                0f,
                0L
            )
            logger.i("FileTransfer", "Incoming file accepted: $transferId")
        }

        emit(transfer)

        // 监听传输进度
        fileRepository.getActiveTransfers()
            .map { transfers -> transfers.find { it.id == transferId } }
            .filterNotNull()
            .collect { emit(it) }

    }.flowOn(Dispatchers.IO)

    /**
     * 取消传输
     */
    override suspend fun cancelTransfer(transferId: String) {
        withContext(Dispatchers.IO) {
            val transfer = fileRepository.getTransferById(transferId)

            if (transfer?.direction == TransferDirection.DOWNLOAD && transfer.state == TransferState.PENDING) {
                val rejectPacket = buildFileRejectPacket(transferId)
                connectionManager.sendData(transfer.deviceId, rejectPacket)
                logger.i("FileTransfer", "Incoming file rejected: $transferId")
            } else if (transfer != null) {
                val cancelPacket = buildFileCancelPacket(transferId)
                connectionManager.sendData(transfer.deviceId, cancelPacket)
            }

            activeTransfers[transferId]?.cancel()
            activeTransfers.remove(transferId)

            fileRepository.updateTransfer(
                transferId,
                TransferState.CANCELLED,
                0f,
                0L
            )

            logger.i("FileTransfer", "Transfer cancelled: $transferId")
        }
    }

    /**
     * 获取传输历史
     */
    override suspend fun getTransferHistory(limit: Int): List<FileTransfer> {
        return withContext(Dispatchers.IO) {
            fileRepository.getTransferHistory(limit)
        }
    }

    /**
     * 获取活动传输
     */
    override fun getActiveTransfers(): Flow<List<FileTransfer>> {
        return fileRepository.getActiveTransfers()
    }

    /**
     * 内部发送文件实现（支持断点续传和重试）
     */
    private suspend fun sendFileInternal(
        transferId: String,
        file: File,
        deviceId: String,
        linkType: String
    ) {
        withContext(Dispatchers.IO) {
            val safeFileName = resolveFileName(file)
            var retryCount = 0
            var lastException: Exception? = null

            while (retryCount <= MAX_RETRY_COUNT) {
                try {
                    ensureConnection(deviceId, linkType)

                    // 更新状态为传输中
                    fileRepository.updateTransfer(
                        transferId,
                        TransferState.TRANSFERRING,
                        0f,
                        0L
                    )

                    // 获取断点续传位置
                    val startPosition = fileRepository.getBytesTransferred(transferId)

                    // 发送文件请求（接收确认）
                    if (startPosition == 0L) {
                        val requestPacket = buildFileRequestPacket(transferId, file)
                        connectionManager.sendData(deviceId, requestPacket)

                        // 等待接收方确认（简化实现，实际应该等待响应）
                        delay(1000)
                    }

                    // 如果是断点续传，发送续传请求
                    if (startPosition > 0L) {
                        val resumePacket = buildResumePacket(transferId, startPosition)
                        connectionManager.sendData(deviceId, resumePacket)
                        delay(500)
                        logger.i("FileTransfer", "Resuming from position: $startPosition")
                    } else {
                        // 发送文件元数据
                        val metadata = buildFileMetadata(transferId, file)
                        connectionManager.sendData(deviceId, metadata)
                    }

                    FileInputStream(file).use { input ->
                        input.skip(startPosition)

                        val buffer = ByteArray(CHUNK_SIZE)
                        var bytesTransferred = startPosition
                        val totalSize = file.length()

                        while (isActive) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break

                            // 构建数据包
                            val packet = buildChunkPacket(transferId, buffer, bytesRead)
                            val success = connectionManager.sendData(deviceId, packet)

                            if (!success) {
                                throw IOException("Failed to send data chunk")
                            }

                            bytesTransferred += bytesRead
                            val progress = bytesTransferred.toFloat() / totalSize

                            // 更新进度
                            fileRepository.updateTransfer(
                                transferId,
                                TransferState.TRANSFERRING,
                                progress,
                                bytesTransferred
                            )

                            delay(10) // 避免过快发送
                        }

                        // 发送完成信号
                        val completePacket = buildCompletePacket(transferId)
                        connectionManager.sendData(deviceId, completePacket)

                        // 更新为完成状态
                        fileRepository.updateTransfer(
                            transferId,
                            TransferState.COMPLETED,
                            1f,
                            totalSize
                        )

                        logger.i("FileTransfer", "File sent successfully: $safeFileName")
                    }

                    activeTransfers.remove(transferId)
                    return@withContext // 成功，退出

                } catch (e: Exception) {
                    lastException = e
                    retryCount++

                    logger.e("FileTransfer", "Send failed (attempt $retryCount): ${e.message}")

                    if (retryCount <= MAX_RETRY_COUNT) {
                        // 保存错误信息和重试次数
                        fileRepository.updateTransferRetry(transferId, retryCount, e.message)
                        delay(RETRY_DELAY_MS)
                    }
                }
            }

            // 所有重试都失败
            fileRepository.updateTransfer(
                transferId,
                TransferState.FAILED,
                0f,
                0L,
                "Failed after $MAX_RETRY_COUNT retries: ${lastException?.message}"
            )
            activeTransfers.remove(transferId)
        }
    }

    private suspend fun ensureConnection(deviceId: String, linkType: String) {
        val normalizedLinkType = linkType.uppercase()
        val connectionType = when {
            isEmulatorEnvironment() -> ConnectionType.WIFI
            normalizedLinkType.contains("WIFI_HOTSPOT") -> ConnectionType.HOTSPOT
            normalizedLinkType.contains("WIFI_LAN") -> ConnectionType.WIFI
            normalizedLinkType.contains("BLUETOOTH") -> ConnectionType.BLUETOOTH
            else -> ConnectionType.WIFI
        }

        val activeConnections = connectionManager.getActiveConnections().first()
        val existing = activeConnections.firstOrNull { it.deviceId == deviceId }
        if (existing != null && existing.state == ConnectionState.CONNECTED) {
            return
        }

        val connectJob = scope.launch {
            connectionManager.connect(deviceId, connectionType).collect()
        }

        val connected = withTimeoutOrNull(15_000L) {
            connectionManager.getActiveConnections()
                .map { connections ->
                    connections.firstOrNull {
                        it.deviceId == deviceId && it.state == ConnectionState.CONNECTED
                    }
                }
                .filterNotNull()
                .first()
        }
        connectJob.cancel()

        if (connected == null) {
            throw IllegalStateException("Unable to establish connection for $deviceId")
        }
    }

    private fun isEmulatorEnvironment(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.orEmpty().lowercase()
        val model = android.os.Build.MODEL.orEmpty().lowercase()
        return fingerprint.contains("generic") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator")
    }

    /**
     * 选择最佳链路
     */
    private suspend fun selectBestLink(fileSize: Long, deviceId: String): String {
        val connections = runCatching { connectionManager.getActiveConnections().first() }.getOrDefault(emptyList())
        val deviceConnection = connections.find { it.deviceId == deviceId }

        return when {
            // 大文件优先使用 WiFi
            fileSize > LARGE_FILE_SIZE -> {
                when {
                    deviceConnection?.type == ConnectionType.WIFI -> "WIFI_LAN"
                    deviceConnection?.type == ConnectionType.HOTSPOT -> "WIFI_HOTSPOT"
                    else -> {
                        logger.w("FileTransfer", "Large file but no WiFi connection available")
                        "BLUETOOTH"
                    }
                }
            }
            // 中等文件使用 WiFi 或热点
            fileSize > MEDIUM_FILE_SIZE -> {
                when (deviceConnection?.type) {
                    ConnectionType.WIFI -> "WIFI_LAN"
                    ConnectionType.HOTSPOT -> "WIFI_HOTSPOT"
                    ConnectionType.BLUETOOTH -> "BLUETOOTH"
                    else -> "WIFI_LAN"
                }
            }
            // 小文件任意链路
            else -> {
                when (deviceConnection?.type) {
                    ConnectionType.WIFI -> "WIFI_LAN"
                    ConnectionType.HOTSPOT -> "WIFI_HOTSPOT"
                    ConnectionType.BLUETOOTH -> "BLUETOOTH"
                    else -> "BLUETOOTH"
                }
            }
        }
    }

    private fun resolveFileName(file: File): String {
        return file.name.ifBlank {
            file.absolutePath.substringAfterLast('/').substringAfterLast('\\')
        }
    }

    /**
     * 处理接收到的数据
     */
    fun handleReceivedData(deviceId: String, data: ByteArray) {
        scope.launch {
            try {
                when (data[0]) {
                    PROTOCOL_FILE_REQUEST -> handleFileRequest(deviceId, data)
                    PROTOCOL_FILE_ACCEPT -> handleFileAccept(data)
                    PROTOCOL_FILE_REJECT -> handleFileReject(data)
                    PROTOCOL_FILE_SEND -> handleFileMetadata(deviceId, data)
                    PROTOCOL_FILE_CHUNK -> handleFileChunk(data)
                    PROTOCOL_FILE_COMPLETE -> handleFileComplete(data)
                    PROTOCOL_FILE_ERROR -> handleFileError(data)
                    PROTOCOL_FILE_CANCEL -> handleFileCancel(data)
                    PROTOCOL_FILE_RESUME -> handleFileResume(deviceId, data)
                    PROTOCOL_FILE_RESUME_ACK -> handleFileResumeAck(data)
                }
            } catch (e: Exception) {
                logger.e("FileTransfer", "Handle received data failed: ${e.message}")
            }
        }
    }

    /**
     * 处理文件请求（接收确认）
     */
    private suspend fun handleFileRequest(deviceId: String, data: ByteArray) {
        // 解析: [protocol][transferId][fileNameLength][fileName][fileSize][mimeTypeLength][mimeType]
        var offset = 1

        val transferIdBytes = data.copyOfRange(offset, offset + 36)
        val transferId = String(transferIdBytes)
        offset += 36

        val fileNameLength = data[offset].toInt()
        offset += 1

        val fileName = String(data.copyOfRange(offset, offset + fileNameLength))
        offset += fileNameLength

        val fileSize = data.copyOfRange(offset, offset + 8).toLong()
        offset += 8

        val mimeTypeLength = data[offset].toInt()
        offset += 1

        val mimeType = String(data.copyOfRange(offset, offset + mimeTypeLength))

        // 创建待确认的传输记录
        val transfer = FileTransfer(
            id = transferId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            deviceId = deviceId,
            direction = TransferDirection.DOWNLOAD,
            state = TransferState.PENDING,
            progress = 0f,
            timestamp = System.currentTimeMillis()
        )

        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        downloadDir.mkdirs()
        val filePath = File(downloadDir, fileName).absolutePath

        fileRepository.saveTransfer(transfer, filePath, 0L)

        logger.i("FileTransfer", "File request received: $fileName, waiting for user confirmation")

        // 等待前端调用 receiveFile() 显式确认后再继续
    }

    /**
     * 处理文件接受
     */
    private suspend fun handleFileAccept(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))
        logger.i("FileTransfer", "File accepted by receiver: $transferId")
    }

    /**
     * 处理文件拒绝
     */
    private suspend fun handleFileReject(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))

        fileRepository.updateTransfer(
            transferId,
            TransferState.CANCELLED,
            0f,
            0L,
            "Rejected by receiver"
        )

        activeTransfers[transferId]?.cancel()
        activeTransfers.remove(transferId)

        logger.i("FileTransfer", "File rejected by receiver: $transferId")
    }

    /**
     * 处理断点续传请求
     */
    private suspend fun handleFileResume(deviceId: String, data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))
        val position = data.copyOfRange(37, 45).toLong()

        logger.i("FileTransfer", "Resume request for $transferId at position $position")

        // 发送确认
        val ackPacket = buildResumeAckPacket(transferId, position)
        connectionManager.sendData(deviceId, ackPacket)
    }

    /**
     * 处理断点续传确认
     */
    private suspend fun handleFileResumeAck(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))
        val position = data.copyOfRange(37, 45).toLong()

        logger.i("FileTransfer", "Resume acknowledged for $transferId at position $position")
    }

    /**
     * 处理文件元数据
     */
    private suspend fun handleFileMetadata(deviceId: String, data: ByteArray) {
        // 解析元数据: [protocol][transferId][fileNameLength][fileName][fileSize][mimeTypeLength][mimeType]
        var offset = 1

        val transferIdBytes = data.copyOfRange(offset, offset + 36)
        val transferId = String(transferIdBytes)
        offset += 36

        val fileNameLength = data[offset].toInt()
        offset += 1

        val fileName = String(data.copyOfRange(offset, offset + fileNameLength))
        offset += fileNameLength

        val fileSize = data.copyOfRange(offset, offset + 8).toLong()
        offset += 8

        val mimeTypeLength = data[offset].toInt()
        offset += 1

        val mimeType = String(data.copyOfRange(offset, offset + mimeTypeLength))

        // 创建接收传输记录
        val transfer = FileTransfer(
            id = transferId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            deviceId = deviceId,
            direction = TransferDirection.DOWNLOAD,
            state = TransferState.PENDING,
            progress = 0f,
            timestamp = System.currentTimeMillis()
        )

        // 准备保存路径
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        downloadDir.mkdirs()
        val filePath = File(downloadDir, fileName).absolutePath

        fileRepository.saveTransfer(transfer, filePath, 0L)

        logger.i("FileTransfer", "Receiving file: $fileName")
    }

    /**
     * 处理文件数据块
     */
    private suspend fun handleFileChunk(data: ByteArray) {
        // 解析: [protocol][transferId][chunkData]
        val transferId = String(data.copyOfRange(1, 37))
        val chunkData = data.copyOfRange(37, data.size)

        val filePath = fileRepository.getFilePath(transferId) ?: return
        val file = File(filePath)

        FileOutputStream(file, true).use { output ->
            output.write(chunkData)
        }

        val bytesTransferred = fileRepository.getBytesTransferred(transferId) + chunkData.size
        val transfer = fileRepository.getTransferById(transferId) ?: return
        val progress = bytesTransferred.toFloat() / transfer.fileSize

        fileRepository.updateTransfer(
            transferId,
            TransferState.TRANSFERRING,
            progress,
            bytesTransferred
        )
    }

    /**
     * 处理文件传输完成
     */
    private suspend fun handleFileComplete(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))
        val transfer = fileRepository.getTransferById(transferId) ?: return

        fileRepository.updateTransfer(
            transferId,
            TransferState.COMPLETED,
            1f,
            transfer.fileSize
        )

        logger.i("FileTransfer", "File received successfully: ${transfer.fileName}")
    }

    /**
     * 处理传输错误
     */
    private suspend fun handleFileError(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))
        val errorMessage = String(data.copyOfRange(37, data.size))

        fileRepository.updateTransfer(
            transferId,
            TransferState.FAILED,
            0f,
            0L,
            errorMessage
        )
    }

    /**
     * 处理传输取消
     */
    private suspend fun handleFileCancel(data: ByteArray) {
        val transferId = String(data.copyOfRange(1, 37))

        fileRepository.updateTransfer(
            transferId,
            TransferState.CANCELLED,
            0f,
            0L
        )
    }

    /**
     * 构建文件请求包
     */
    private fun buildFileRequestPacket(transferId: String, file: File): ByteArray {
        val fileName = file.name.toByteArray()
        val mimeType = getMimeType(file).toByteArray()
        val fileSize = file.length()

        return byteArrayOf(PROTOCOL_FILE_REQUEST) +
                transferId.toByteArray() +
                fileName.size.toByte() +
                fileName +
                fileSize.toByteArray() +
                mimeType.size.toByte() +
                mimeType
    }

    /**
     * 构建文件接受包
     */
    private fun buildFileAcceptPacket(transferId: String): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_ACCEPT) + transferId.toByteArray()
    }

    /**
     * 构建文件拒绝包
     */
    private fun buildFileRejectPacket(transferId: String): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_REJECT) + transferId.toByteArray()
    }

    /**
     * 构建文件取消包
     */
    private fun buildFileCancelPacket(transferId: String): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_CANCEL) + transferId.toByteArray()
    }

    /**
     * 构建断点续传包
     */
    private fun buildResumePacket(transferId: String, position: Long): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_RESUME) +
                transferId.toByteArray() +
                position.toByteArray()
    }

    /**
     * 构建断点续传确认包
     */
    private fun buildResumeAckPacket(transferId: String, position: Long): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_RESUME_ACK) +
                transferId.toByteArray() +
                position.toByteArray()
    }

    /**
     * 构建文件元数据包
     */
    private fun buildFileMetadata(transferId: String, file: File): ByteArray {
        val fileName = file.name.toByteArray()
        val mimeType = getMimeType(file).toByteArray()
        val fileSize = file.length()

        return byteArrayOf(PROTOCOL_FILE_SEND) +
                transferId.toByteArray() +
                fileName.size.toByte() +
                fileName +
                fileSize.toByteArray() +
                mimeType.size.toByte() +
                mimeType
    }

    /**
     * 构建数据块包
     */
    private fun buildChunkPacket(transferId: String, data: ByteArray, size: Int): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_CHUNK) +
                transferId.toByteArray() +
                data.copyOfRange(0, size)
    }

    /**
     * 构建完成包
     */
    private fun buildCompletePacket(transferId: String): ByteArray {
        return byteArrayOf(PROTOCOL_FILE_COMPLETE) + transferId.toByteArray()
    }

    /**
     * 获取 MIME 类型
     */
    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * ByteArray 转 Long
     */
    private fun ByteArray.toLong(): Long {
        var result = 0L
        for (i in indices) {
            result = result or ((this[i].toLong() and 0xFF) shl (8 * i))
        }
        return result
    }

    /**
     * Long 转 ByteArray
     */
    private fun Long.toByteArray(): ByteArray {
        val result = ByteArray(8)
        for (i in 0..7) {
            result[i] = (this shr (8 * i) and 0xFF).toByte()
        }
        return result
    }
}
