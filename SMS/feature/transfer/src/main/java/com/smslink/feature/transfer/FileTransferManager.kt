package com.smslink.feature.transfer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.smslink.core.model.FileTransferInfo
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferStatus
import com.smslink.core.preferences.AppPreferences
import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File transfer manager.
 * Handles outbound transfers and inbound file writes.
 */
@Singleton
class FileTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferRepository: TransferRepository,
    private val appPreferences: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onSendMessage: ((Message) -> Unit)? = null

    private val activeTransfers = ConcurrentHashMap<String, TransferTask>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransferSession>()
    private val pendingOutgoingDecisions = ConcurrentHashMap<String, CompletableDeferred<TransferDecision>>()

    companion object {
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_FILE_SIZE = 20L * 1024 * 1024 * 1024
        private const val TAG = "FileTransferManager"
        private const val INCOMING_ROOT_DIR = "SMS-Link"
    }

    fun getAllTransfers(): Flow<List<FileTransferInfo>> = transferRepository.getAllTransfers()

    fun getTransfersByStatus(status: TransferStatus): Flow<List<FileTransferInfo>> =
        transferRepository.getTransfersByStatus(status)

    suspend fun sendFile(deviceId: String, deviceName: String, file: File): Result<String> {
        return runCatching {
            require(file.isFile) { "Not a file" }
            require(file.length() <= MAX_FILE_SIZE) { "File size exceeds maximum limit of 20GB" }

            val transferId = UUID.randomUUID().toString()
            val transferInfo = FileTransferInfo(
                id = transferId,
                fileName = file.name,
                filePath = file.absolutePath,
                fileSize = file.length(),
                mimeType = getMimeType(file),
                deviceId = deviceId,
                deviceName = deviceName,
                status = TransferStatus.PENDING,
                progress = 0,
                transferredBytes = 0,
                speed = 0,
                timestamp = System.currentTimeMillis(),
                isFolder = false,
                fileCount = 1,
                direction = TransferDirection.SEND
            )

            transferRepository.insertTransfer(transferInfo, TransferDirection.SEND)
            registerPendingDecision(transferId)
            sendTransferRequest(
                transferId = transferId,
                relativePath = file.name,
                fileSize = file.length(),
                totalSize = file.length(),
                isFolder = false,
                fileCount = 1
            )

            val decision = awaitTransferDecision(transferId) ?: run {
                pendingOutgoingDecisions.remove(transferId)
                throw IllegalStateException("Timed out waiting for transfer acceptance")
            }
            if (!decision.accepted) {
                transferRepository.updateStatus(transferId, TransferStatus.CANCELLED, decision.reason)
                error(decision.reason ?: "Transfer rejected by receiver")
            }

            startFileTransfer(transferId, file)
            transferId
        }
    }

    suspend fun sendFolder(deviceId: String, deviceName: String, folder: File): Result<String> {
        return runCatching {
            require(folder.isDirectory) { "Not a directory" }

            val files = folder.walkTopDown().filter { it.isFile }.toList()
            val totalSize = files.sumOf { it.length() }
            require(totalSize <= MAX_FILE_SIZE) { "Folder size exceeds maximum limit of 20GB" }

            val transferId = UUID.randomUUID().toString()
            val transferInfo = FileTransferInfo(
                id = transferId,
                fileName = folder.name,
                filePath = folder.absolutePath,
                fileSize = totalSize,
                mimeType = null,
                deviceId = deviceId,
                deviceName = deviceName,
                status = TransferStatus.PENDING,
                progress = 0,
                transferredBytes = 0,
                speed = 0,
                timestamp = System.currentTimeMillis(),
                isFolder = true,
                fileCount = files.size,
                direction = TransferDirection.SEND
            )

            transferRepository.insertTransfer(transferInfo, TransferDirection.SEND)
            registerPendingDecision(transferId)
            sendTransferRequest(
                transferId = transferId,
                relativePath = folder.name,
                fileSize = totalSize,
                totalSize = totalSize,
                isFolder = true,
                fileCount = files.size
            )

            val decision = awaitTransferDecision(transferId) ?: run {
                pendingOutgoingDecisions.remove(transferId)
                throw IllegalStateException("Timed out waiting for transfer acceptance")
            }
            if (!decision.accepted) {
                transferRepository.updateStatus(transferId, TransferStatus.CANCELLED, decision.reason)
                error(decision.reason ?: "Transfer rejected by receiver")
            }

            startFolderTransfer(transferId, folder, files)
            transferId
        }
    }

    suspend fun cancelTransfer(transferId: String): Result<Unit> {
        return runCatching {
            activeTransfers[transferId]?.cancel()
            activeTransfers.remove(transferId)
            pendingOutgoingDecisions.remove(transferId)?.complete(
                TransferDecision(accepted = false, reason = "cancelled locally")
            )
            transferRepository.updateStatus(transferId, TransferStatus.CANCELLED)
            sendTransferCancel(transferId, "cancelled locally")
        }
    }

    suspend fun pauseTransfer(transferId: String): Result<Unit> {
        return runCatching {
            activeTransfers[transferId]?.pause()
            transferRepository.updateStatus(transferId, TransferStatus.PAUSED)
        }
    }

    suspend fun resumeTransfer(transferId: String): Result<Unit> {
        return runCatching {
            val transfer = transferRepository.getTransferById(transferId)
                ?: error("Transfer not found")

            require(transfer.status == TransferStatus.PAUSED) { "Transfer is not paused" }
            require(!transfer.isFolder) { "Folder resume is not supported yet" }

            val file = File(transfer.filePath)
            require(file.exists()) { "File not found" }

            startFileTransfer(transferId, file, transfer.transferredBytes)
        }
    }

    suspend fun retryTransfer(transferId: String): Result<Unit> {
        return runCatching {
            val transfer = transferRepository.getTransferById(transferId)
                ?: error("Transfer not found")

            require(transfer.status == TransferStatus.FAILED) { "Transfer is not failed" }
            require(!transfer.isFolder) { "Folder retry is not supported yet" }

            val file = File(transfer.filePath)
            require(file.exists()) { "File not found" }

            transferRepository.updateProgress(transferId, 0, 0, 0)
            transferRepository.updateStatus(transferId, TransferStatus.PENDING)
            startFileTransfer(transferId, file)
        }
    }

    suspend fun prepareIncomingTransfer(
        transferId: String,
        deviceId: String,
        deviceName: String,
        relativePath: String,
        fileSize: Long,
        totalSize: Long? = null,
        isFolder: Boolean = false,
        fileCount: Int = 1
    ): Result<Unit> {
        return runCatching {
            val rootTarget = resolveIncomingPath(relativePath)
            val session = incomingTransfers.compute(transferId) { _, existing ->
                val preservedBytes = existing?.transferredBytes ?: 0L
                val preservedTotalSize = maxOf(existing?.totalSize ?: 0L, totalSize ?: fileSize)
                val startedAt = existing?.startedAt ?: System.currentTimeMillis()
                existing?.closeCurrentFile()
                IncomingTransferSession(
                    transferId = transferId,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    requestPath = relativePath,
                    currentRelativePath = relativePath,
                    currentFileSize = fileSize,
                    totalSize = preservedTotalSize,
                    fileCount = fileCount,
                    isFolder = isFolder,
                    startedAt = startedAt
                ).apply {
                    transferredBytes = preservedBytes
                }
            } ?: error("Failed to create incoming transfer session")

            val progress = if (session.totalSize > 0) {
                ((session.transferredBytes * 100) / session.totalSize).toInt().coerceIn(0, 100)
            } else {
                0
            }
            val transferInfo = FileTransferInfo(
                id = transferId,
                fileName = relativePath,
                filePath = rootTarget.absolutePath,
                fileSize = fileSize,
                mimeType = if (isFolder) null else getMimeType(rootTarget),
                deviceId = deviceId,
                deviceName = deviceName,
                status = TransferStatus.PENDING,
                progress = progress,
                transferredBytes = session.transferredBytes,
                speed = 0,
                timestamp = System.currentTimeMillis(),
                isFolder = isFolder,
                fileCount = session.fileCount,
                direction = TransferDirection.RECEIVE
            )
            transferRepository.insertTransfer(transferInfo, TransferDirection.RECEIVE)

            val autoAccept = runCatching { appPreferences.fileAutoAccept.first() }.getOrDefault(true)
            if (autoAccept) {
                acceptIncomingTransfer(transferId).getOrThrow()
            }
        }
    }

    suspend fun receiveFileChunk(
        transferId: String,
        relativePath: String,
        chunk: ByteArray,
        offset: Long,
        fileSize: Long
    ) {
        if (chunk.isEmpty()) return
        val session = incomingTransfers[transferId] ?: return
        if (!session.isAccepted) return
        val file = session.ensureFileOpen(relativePath, fileSize) ?: return
        file.seek(offset.coerceAtLeast(0L))
        file.write(chunk)

        session.currentFileBytes = maxOf(session.currentFileBytes, offset + chunk.size)
        session.transferredBytes += chunk.size

        val now = System.currentTimeMillis()
        val elapsedMs = (now - session.startedAt).coerceAtLeast(1L)
        val speed = session.transferredBytes * 1000 / elapsedMs
        val progress = if (session.totalSize > 0) {
            ((session.transferredBytes * 100) / session.totalSize).toInt().coerceIn(0, 100)
        } else {
            0
        }
        transferRepository.updateProgress(transferId, progress, session.transferredBytes, speed)

        if (session.currentFileBytes >= session.currentFileSize) {
            session.closeCurrentFile()
        }

        if (progress >= 100 && session.totalSize > 0) {
            transferRepository.updateStatus(transferId, TransferStatus.TRANSFERRING)
        }
    }

    suspend fun acceptIncomingTransfer(transferId: String): Result<Unit> {
        return runCatching {
            val session = incomingTransfers[transferId] ?: error("Transfer not found")
            session.isAccepted = true
            if (!session.isFolder) {
                session.openCurrentFile()
            }
            transferRepository.updateStatus(transferId, TransferStatus.TRANSFERRING)
            sendIncomingTransferDecision(
                transferId = transferId,
                accepted = true,
                relativePath = session.requestPath,
                reason = null
            )
        }
    }

    suspend fun rejectIncomingTransfer(transferId: String, reason: String? = null): Result<Unit> {
        return runCatching {
            val session = incomingTransfers.remove(transferId)
            session?.closeCurrentFile()
            transferRepository.updateStatus(transferId, TransferStatus.CANCELLED, reason)
            sendIncomingTransferDecision(
                transferId = transferId,
                accepted = false,
                relativePath = session?.requestPath ?: "",
                reason = reason ?: "rejected by user"
            )
        }
    }

    suspend fun completeIncomingTransfer(transferId: String): Result<Unit> {
        return runCatching {
            val session = incomingTransfers.remove(transferId)
            session?.closeCurrentFile()

            val transferredBytes = session?.transferredBytes ?: 0L
            transferRepository.updateProgress(transferId, 100, transferredBytes, 0)
            transferRepository.updateStatus(transferId, TransferStatus.COMPLETED)
        }
    }

    suspend fun cancelIncomingTransfer(transferId: String, errorMessage: String? = null): Result<Unit> {
        return runCatching {
            val session = incomingTransfers.remove(transferId)
            session?.closeCurrentFile()
            transferRepository.updateStatus(transferId, TransferStatus.CANCELLED, errorMessage)
        }
    }

    private fun startFileTransfer(transferId: String, file: File, startOffset: Long = 0) {
        scope.launch {
            val task = TransferTask(transferId, file, startOffset)
            activeTransfers[transferId] = task

            try {
                transferRepository.updateStatus(transferId, TransferStatus.TRANSFERRING)

                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime
                var transferredBytes = startOffset

                FileInputStream(file).use { input ->
                    if (startOffset > 0) {
                        var skipped = 0L
                        while (skipped < startOffset) {
                            val step = input.skip(startOffset - skipped)
                            if (step <= 0) {
                                break
                            }
                            skipped += step
                        }
                        require(skipped == startOffset) { "Unable to skip to resume offset" }
                    }

                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    var fileOffset = startOffset

                    while (input.read(buffer).also { bytesRead = it } != -1 && !task.isCancelled) {
                        while (task.isPaused && !task.isCancelled) {
                            delay(100)
                        }

                        if (task.isCancelled) {
                            break
                        }

                        val chunk = if (bytesRead < CHUNK_SIZE) buffer.copyOf(bytesRead) else buffer
                        sendFileChunk(transferId, file.name, chunk, fileOffset, file.length())
                        fileOffset += bytesRead
                        transferredBytes += bytesRead

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 1000) {
                            val progress = ((transferredBytes * 100) / file.length()).toInt()
                            val speed = ((transferredBytes - startOffset) * 1000) / (currentTime - startTime)
                            transferRepository.updateProgress(transferId, progress, transferredBytes, speed)
                            lastUpdateTime = currentTime
                        }
                    }
                }

                if (task.isCancelled) {
                    transferRepository.updateStatus(transferId, TransferStatus.CANCELLED)
                    sendTransferCancel(transferId, "cancelled locally")
                } else {
                    transferRepository.updateProgress(transferId, 100, file.length(), 0)
                    transferRepository.updateStatus(transferId, TransferStatus.COMPLETED)
                    sendTransferComplete(transferId)
                }
            } catch (e: Exception) {
                transferRepository.updateStatus(transferId, TransferStatus.FAILED, e.message)
                sendTransferCancel(transferId, e.message ?: "transfer failed")
            } finally {
                activeTransfers.remove(transferId)
            }
        }
    }

    private fun startFolderTransfer(transferId: String, folder: File, files: List<File>) {
        scope.launch {
            val task = TransferTask(transferId, folder, 0)
            activeTransfers[transferId] = task

            try {
                transferRepository.updateStatus(transferId, TransferStatus.TRANSFERRING)

                val totalSize = files.sumOf { it.length() }
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime
                var transferredBytes = 0L

                for (file in files) {
                    if (task.isCancelled) {
                        break
                    }

                    val relativePath = file.relativeTo(folder).path
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var bytesRead: Int
                        var fileOffset = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1 && !task.isCancelled) {
                            while (task.isPaused && !task.isCancelled) {
                                delay(100)
                            }

                            if (task.isCancelled) {
                                break
                            }

                            val chunk = if (bytesRead < CHUNK_SIZE) buffer.copyOf(bytesRead) else buffer
                            sendFileChunk(transferId, relativePath, chunk, fileOffset, file.length())
                            fileOffset += bytesRead
                            transferredBytes += bytesRead

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 1000) {
                                val progress = ((transferredBytes * 100) / totalSize).toInt()
                                val speed = (transferredBytes * 1000) / (currentTime - startTime)
                                transferRepository.updateProgress(transferId, progress, transferredBytes, speed)
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                }

                if (task.isCancelled) {
                    transferRepository.updateStatus(transferId, TransferStatus.CANCELLED)
                    sendTransferCancel(transferId, "cancelled locally")
                } else {
                    transferRepository.updateProgress(transferId, 100, totalSize, 0)
                    transferRepository.updateStatus(transferId, TransferStatus.COMPLETED)
                    sendTransferComplete(transferId)
                }
            } catch (e: Exception) {
                transferRepository.updateStatus(transferId, TransferStatus.FAILED, e.message)
                sendTransferCancel(transferId, e.message ?: "transfer failed")
            } finally {
                activeTransfers.remove(transferId)
            }
        }
    }

    private fun sendTransferRequest(
        transferId: String,
        relativePath: String,
        fileSize: Long,
        totalSize: Long,
        isFolder: Boolean,
        fileCount: Int
    ) {
        val encodedPath = Uri.encode(relativePath.replace('\\', '/').trimStart('/'), "/")
        val payload = "$transferId|$encodedPath|$fileSize|$totalSize|${if (isFolder) 1 else 0}|$fileCount".toByteArray()
        val message = Message(
            type = MessageType.FILE_TRANSFER_REQUEST,
            messageId = System.currentTimeMillis(),
            payload = payload
        )
        onSendMessage?.invoke(message)
    }

    private fun sendFileChunk(transferId: String, relativePath: String, chunk: ByteArray, offset: Long, fileSize: Long) {
        val encodedPath = Uri.encode(relativePath.replace('\\', '/').trimStart('/'), "/")
        val header = "$transferId|$encodedPath|$offset|$fileSize|".toByteArray()
        val payload = header + chunk

        val message = Message(
            type = MessageType.FILE_TRANSFER_DATA,
            messageId = System.currentTimeMillis(),
            payload = payload
        )
        onSendMessage?.invoke(message)
    }

    private fun sendTransferComplete(transferId: String) {
        val message = Message(
            type = MessageType.FILE_TRANSFER_COMPLETE,
            messageId = System.currentTimeMillis(),
            payload = transferId.toByteArray()
        )
        onSendMessage?.invoke(message)
    }

    private fun sendTransferCancel(transferId: String, reason: String) {
        val payload = "$transferId|${Uri.encode(reason)}".toByteArray()
        val message = Message(
            type = MessageType.FILE_TRANSFER_CANCEL,
            messageId = System.currentTimeMillis(),
            payload = payload
        )
        onSendMessage?.invoke(message)
    }

    private fun sendIncomingTransferDecision(
        transferId: String,
        accepted: Boolean,
        relativePath: String,
        reason: String?
    ) {
        val payload = if (accepted) {
            "$transferId|${Uri.encode(relativePath.replace('\\', '/').trimStart('/'), "/")}".toByteArray()
        } else {
            "$transferId|${Uri.encode(reason.orEmpty())}".toByteArray()
        }
        val message = Message(
            type = if (accepted) MessageType.FILE_TRANSFER_ACCEPT else MessageType.FILE_TRANSFER_REJECT,
            messageId = System.currentTimeMillis(),
            payload = payload
        )
        onSendMessage?.invoke(message)
    }

    private suspend fun resolveIncomingPath(relativePath: String): File {
        val root = resolveIncomingRootDirectory()
        val normalizedPath = relativePath.replace('\\', '/').trimStart('/')
        val target = File(root, normalizedPath)
        val rootCanonical = root.canonicalFile
        val targetCanonical = target.canonicalFile

        val rootPath = rootCanonical.path.trimEnd(File.separatorChar) + File.separatorChar
        require(
            targetCanonical.path == rootCanonical.path ||
                targetCanonical.path.startsWith(rootPath)
        ) { "Invalid incoming path" }
        targetCanonical.parentFile?.mkdirs()
        return targetCanonical
    }

    private suspend fun resolveIncomingFile(relativePath: String): File {
        return resolveIncomingPath(relativePath)
    }

    private suspend fun resolveIncomingRootDirectory(): File {
        val preferred = File(appPreferences.fileSavePath.first())
        val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            File(it, INCOMING_ROOT_DIR)
        } ?: File(context.filesDir, INCOMING_ROOT_DIR)

        val candidate = if (isWritableDirectory(preferred)) preferred else fallback
        candidate.mkdirs()
        return candidate
    }

    private fun isWritableDirectory(directory: File): Boolean {
        return try {
            (directory.exists() || directory.mkdirs()) && directory.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun RandomAccessFile.closeQuietly() {
        runCatching { close() }
    }

    private fun registerPendingDecision(transferId: String) {
        val deferred = CompletableDeferred<TransferDecision>()
        pendingOutgoingDecisions[transferId] = deferred
        deferred.invokeOnCompletion {
            pendingOutgoingDecisions.remove(transferId, deferred)
        }
    }

    private suspend fun awaitTransferDecision(transferId: String, timeoutMs: Long = 15_000L): TransferDecision? {
        val deferred = pendingOutgoingDecisions[transferId] ?: return null
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    fun handleTransferAccepted(transferId: String) {
        pendingOutgoingDecisions.remove(transferId)?.complete(TransferDecision(accepted = true))
    }

    fun handleTransferRejected(transferId: String, reason: String? = null) {
        pendingOutgoingDecisions.remove(transferId)?.complete(TransferDecision(accepted = false, reason = reason))
    }

    private class TransferTask(
        val transferId: String,
        val file: File,
        val startOffset: Long
    ) {
        var isCancelled = false
            private set
        var isPaused = false
            private set

        fun cancel() {
            isCancelled = true
        }

        fun pause() {
            isPaused = true
        }

        fun resume() {
            isPaused = false
        }
    }

    private data class TransferDecision(
        val accepted: Boolean,
        val reason: String? = null
    )

    private inner class IncomingTransferSession(
        val transferId: String,
        val deviceId: String,
        val deviceName: String,
        val requestPath: String,
        var currentRelativePath: String,
        var currentFileSize: Long,
        var totalSize: Long,
        val fileCount: Int,
        val isFolder: Boolean,
        val startedAt: Long
    ) {
        var currentFile: RandomAccessFile? = null
        var currentFileBytes: Long = 0L
        var transferredBytes: Long = 0L
        var isAccepted: Boolean = false

        suspend fun openCurrentFile() {
            val targetPath = if (isFolder) {
                if (currentRelativePath == requestPath) requestPath else "$requestPath/${currentRelativePath.trimStart('/')}"
            } else {
                requestPath
            }
            val targetFile = resolveIncomingFile(targetPath)
            closeCurrentFile()
            currentFile = RandomAccessFile(targetFile, "rw").apply {
                if (currentFileBytes == 0L) {
                    setLength(0)
                }
                seek(currentFileBytes)
            }
        }

        suspend fun ensureFileOpen(relativePath: String, expectedSize: Long): RandomAccessFile? {
            val normalized = relativePath.replace('\\', '/').trimStart('/')
            if (normalized.isBlank()) return null

            val targetRelativePath = if (isFolder) {
                if (normalized == requestPath.trimStart('/')) requestPath else "$requestPath/$normalized"
            } else {
                requestPath
            }

            if (currentFile == null || currentRelativePath != normalized || currentFileSize != expectedSize) {
                closeCurrentFile()
                currentRelativePath = normalized
                currentFileSize = expectedSize
                currentFileBytes = 0L
                val targetFile = resolveIncomingFile(targetRelativePath)
                currentFile = RandomAccessFile(targetFile, "rw").apply {
                    setLength(0)
                    seek(0)
                }
            }

            return currentFile
        }

        fun closeCurrentFile() {
            currentFile?.closeQuietly()
            currentFile = null
        }
    }
}
