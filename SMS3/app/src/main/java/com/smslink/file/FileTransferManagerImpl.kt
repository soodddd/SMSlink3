package com.smslink.file

import android.content.Context
import android.os.Environment
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionState
import com.smslink.core.model.ConnectionType
import com.smslink.core.model.FileTransfer
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState
import com.smslink.file.data.FileRepository
import com.smslink.network.IConnectionManager
import com.smslink.network.connection.ConnectionPolicyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File transfer state machine.
 *
 * A transfer is request -> explicit accept/reject -> numbered chunks -> hash
 * verified completion. Every packet is acknowledged by the receiver, so a
 * successful socket write is never mistaken for a successful file transfer.
 */
@Singleton
class FileTransferManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: IConnectionManager,
    private val fileRepository: FileRepository,
    private val logger: ILogger,
    private val connectionPolicyStore: ConnectionPolicyStore
) : IFileTransferManager {

    /** Compatibility constructor retained for the original JVM tests. */
    constructor(
        context: Context,
        connectionManager: IConnectionManager,
        fileRepository: FileRepository,
        logger: ILogger
    ) : this(
        context,
        connectionManager,
        fileRepository,
        logger,
        ConnectionPolicyStore.forTests()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val acceptWaiters = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val completionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val chunkAckWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val incomingSessions = ConcurrentHashMap<String, IncomingSession>()
    /** Preserve wire order: chunk 1 must never overtake chunk 0 after a launch. */
    private val incomingPacketMutex = Mutex()

    init {
        scope.launch {
            connectionManager.receiveData().collect { (deviceId, data) ->
                // Process the shared receive stream in collector order. A
                // launch-per-packet fan-out lets chunk N+1 acquire the mutex
                // before chunk N has even reached it, turning valid traffic
                // into an apparent out-of-order transfer.
                handleReceivedDataInternal(deviceId, data)
            }
        }
    }

    override fun sendFile(file: File, targetDeviceId: String): Flow<FileTransfer> =
        sendFileFlow(file, targetDeviceId, null)

    private fun sendFileFlow(
        file: File,
        targetDeviceId: String,
        resumeTransfer: FileTransfer?
    ): Flow<FileTransfer> = flow {
        require(targetDeviceId.isNotBlank()) { "Target device is required" }
        require(file.isFile && file.exists() && file.canRead()) { "File not found or not readable" }
        require(file.length() <= FilePacketCodec.MAX_FILE_SIZE) { "File is too large" }

        val transferId = resumeTransfer?.id ?: UUID.randomUUID().toString()
        val fileName = sanitizeFileName(resumeTransfer?.fileName ?: file.name)
        require(fileName.isNotBlank()) { "Invalid file name" }
        val fileSize = file.length()
        val linkType = selectLinkType(targetDeviceId)
        val mimeType = getMimeType(file)
        val persistedBytes = resumeTransfer?.bytesTransferred?.coerceIn(0L, fileSize) ?: 0L
        val pending = if (resumeTransfer == null) {
            FileTransfer(
                id = transferId,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                deviceId = targetDeviceId,
                direction = TransferDirection.UPLOAD,
                state = TransferState.PENDING,
                progress = 0f,
                timestamp = System.currentTimeMillis(),
                bytesTransferred = 0L,
                filePath = file.absolutePath,
                // Hashing is deliberately deferred until after the first
                // state is emitted. Picking a file should immediately create
                // a visible pending record, while the potentially expensive
                // read happens only when the caller keeps collecting.
                fileHash = null
            )
        } else {
            resumeTransfer.copy(
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                deviceId = targetDeviceId,
                direction = TransferDirection.UPLOAD,
                state = TransferState.PENDING,
                progress = if (fileSize == 0L) 0f else persistedBytes.toFloat() / fileSize,
                bytesTransferred = persistedBytes,
                filePath = file.absolutePath,
                errorMessage = null,
                lastError = null
            )
        }

        if (resumeTransfer == null) {
            fileRepository.saveTransferWithLink(pending, file.absolutePath, 0L, linkType)
        } else {
            fileRepository.updateTransfer(
                transferId,
                TransferState.PENDING,
                pending.progress,
                persistedBytes,
                null
            )
            fileRepository.updateTransferRetry(transferId, resumeTransfer.retryCount, null)
        }
        // Read the persisted checkpoint after creating/reopening the record.
        // For a new transfer it must be zero; for a retry this is the durable
        // value and is preferred over a stale in-memory snapshot.
        val repositoryCheckpoint = try {
            fileRepository.getBytesTransferred(transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            persistedBytes
        }
        val checkpoint = if (resumeTransfer == null) {
            0L
        } else {
            repositoryCheckpoint.coerceIn(0L, fileSize)
        }
        val pendingState = if (resumeTransfer == null) pending else pending.copy(
            bytesTransferred = checkpoint,
            progress = if (fileSize == 0L) 0f else checkpoint.toFloat() / fileSize
        )
        emit(fileRepository.getTransferById(transferId) ?: pendingState)

        // A collector can intentionally take only the initial state. Do not
        // create a waiter or start network work until that state was consumed.
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[transferId] = job
        val acceptWaiter = CompletableDeferred<Int>()
        val completeWaiter = CompletableDeferred<Boolean>()
        acceptWaiters[transferId] = acceptWaiter
        completionWaiters[transferId] = completeWaiter

        try {
            val fileHash = sha256File(file)
            if (file.length() != fileSize) {
                throw IllegalStateException("File changed while preparing transfer")
            }
            fileRepository.updateTransferSession(
                transferId,
                fileHash,
                resumeTransfer?.nextSequence?.coerceAtLeast(0) ?: 0
            )

            ensureConnection(targetDeviceId)
            val request = FilePacketCodec.request(
                transferId = transferId,
                fileName = fileName,
                mimeType = pending.mimeType,
                fileSize = fileSize,
                fileHash = fileHash
            )
            sendPacketWithRetry(targetDeviceId, request)

            val startSignal = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                select<StartSignal> {
                    acceptWaiter.onAwait { sequence -> StartSignal.Accepted(sequence) }
                    completeWaiter.onAwait { completed ->
                        if (completed) StartSignal.AlreadyCompleted
                        else throw IllegalStateException("File transfer was rejected")
                    }
                }
            } ?: throw IllegalStateException("File transfer was rejected or timed out")

            if (startSignal === StartSignal.AlreadyCompleted) {
                fileRepository.updateTransfer(transferId, TransferState.COMPLETED, 1f, fileSize)
                fileRepository.updateTransferSession(
                    transferId,
                    fileHash,
                    ((fileSize + CHUNK_SIZE - 1L) / CHUNK_SIZE).toInt()
                )
                emitCurrent(transferId)?.let { emit(it) }
                return@flow
            }

            val startSequence = (startSignal as StartSignal.Accepted).sequence
            if (startSequence < 0) throw IllegalStateException("Invalid transfer resume position")

            val startBytes = startSequence.toLong() * CHUNK_SIZE
            if (startBytes > fileSize) throw IllegalStateException("Invalid transfer resume position")
            fileRepository.updateTransfer(
                transferId,
                TransferState.TRANSFERRING,
                if (fileSize == 0L) 0f else startBytes.toFloat() / fileSize,
                startBytes
            )
            emitCurrent(transferId)?.let { emit(it) }

            FileInputStream(file).use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                skipFully(input, startBytes)
                var sequence = startSequence
                var bytesSent = startBytes
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    val chunk = buffer.copyOf(count)
                    val packet = FilePacketCodec.chunk(transferId, sequence, fileSize, chunk)
                    val ackKey = chunkAckKey(transferId, sequence)
                    val ackWaiter = CompletableDeferred<Unit>()
                    chunkAckWaiters[ackKey] = ackWaiter
                    try {
                        sendPacketWithRetry(targetDeviceId, packet)
                        withTimeoutOrNull(CHUNK_ACK_TIMEOUT_MS) { ackWaiter.await() }
                            ?: throw IllegalStateException("Chunk acknowledgement timed out")
                    } finally {
                        chunkAckWaiters.remove(ackKey)
                    }

                    bytesSent += count
                    sequence++
                    fileRepository.updateTransfer(
                        transferId,
                        TransferState.TRANSFERRING,
                        if (fileSize == 0L) 0f else bytesSent.toFloat() / fileSize,
                        bytesSent
                    )
                    fileRepository.updateTransferSession(transferId, fileHash, sequence)
                    emitCurrent(transferId)?.let { emit(it) }
                }
            }

            val completePacket = FilePacketCodec.complete(transferId, fileSize, fileHash)
            sendPacketWithRetry(targetDeviceId, completePacket)
            val completed = withTimeoutOrNull(COMPLETE_ACK_TIMEOUT_MS) { completeWaiter.await() } == true
            if (!completed) throw IllegalStateException("Completion acknowledgement timed out")

            fileRepository.updateTransfer(transferId, TransferState.COMPLETED, 1f, fileSize)
            fileRepository.updateTransferSession(
                transferId,
                fileHash,
                ((fileSize + CHUNK_SIZE - 1L) / CHUNK_SIZE).toInt()
            )
            emitCurrent(transferId)?.let { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "File send failed for $transferId", e)
            val failureMessage = e.message ?: "File transfer failed"
            val persisted = try {
                fileRepository.getTransferById(transferId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            val failedBytes = persisted?.bytesTransferred
                ?: resumeTransfer?.bytesTransferred?.coerceIn(0L, fileSize)
                ?: 0L
            val failedProgress = if (fileSize == 0L) 0f else failedBytes.toFloat() / fileSize
            fileRepository.updateTransferRetry(
                transferId,
                (resumeTransfer?.retryCount ?: 0) + 1,
                failureMessage
            )
            fileRepository.updateTransfer(
                transferId,
                TransferState.FAILED,
                failedProgress,
                failedBytes,
                failureMessage
            )
            emitCurrent(transferId)?.let { emit(it) }
        } finally {
            activeJobs.remove(transferId)
            acceptWaiters.remove(transferId)
            completionWaiters.remove(transferId)
        }
    }.flowOn(Dispatchers.IO)

    override fun receiveFile(transferId: String): Flow<FileTransfer> = flow {
        val transfer = fileRepository.getTransferById(transferId)
            ?: throw IllegalArgumentException("Transfer not found")
        if (transfer.direction != TransferDirection.DOWNLOAD) {
            throw IllegalArgumentException("Transfer is not an incoming file")
        }

        var current = transfer
        if (current.state == TransferState.PENDING) {
            // Move the durable state first. The peer is allowed to send its
            // first chunk as soon as it receives ACCEPT, so accepting after
            // the state update avoids a lost/racing chunk.
            val pending = current
            fileRepository.updateTransfer(
                transferId,
                TransferState.TRANSFERRING,
                pending.progress,
                pending.bytesTransferred,
                null
            )
            val sent = try {
                connectionManager.sendData(
                    current.deviceId,
                    FilePacketCodec.accept(transferId, current.nextSequence.coerceAtLeast(0))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Could not send accept packet for $transferId: ${e.message}")
                false
            }
            if (sent) {
                current = fileRepository.getTransferById(transferId) ?: current
            } else {
                fileRepository.updateTransfer(
                    transferId,
                    TransferState.PENDING,
                    pending.progress,
                    pending.bytesTransferred,
                    "Could not send accept packet"
                )
                current = fileRepository.getTransferById(transferId) ?: pending
                logger.w(TAG, "Could not send accept packet for $transferId")
            }
        }
        emit(current)

        // Observe until a terminal record is emitted. A receiver that is not
        // online simply stays at its persisted state and can be resumed later.
        fileRepository.observeTransfer(transferId)
            .transformWhile { next ->
                if (next == null) return@transformWhile false
                emit(next)
                next.state !in TERMINAL_STATES
            }
            .collect()
    }.flowOn(Dispatchers.IO)

    override suspend fun cancelTransfer(transferId: String) {
        val transfer = fileRepository.getTransferById(transferId)
        if (transfer == null) return
        if (transfer.state == TransferState.COMPLETED || transfer.state == TransferState.CANCELLED) {
            logger.d(TAG, "Ignoring cancellation for terminal transfer: $transferId")
            return
        }
        run {
            val packet = runCatching {
                if (transfer.direction == TransferDirection.DOWNLOAD &&
                    transfer.state == TransferState.PENDING
                ) {
                    FilePacketCodec.reject(transferId, "Rejected by receiver")
                } else {
                    FilePacketCodec.cancel(transferId)
                }
            }.getOrNull()
            if (packet != null) {
                try {
                    connectionManager.sendData(transfer.deviceId, packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "Could not send cancellation for $transferId: ${e.message}")
                }
            }
            if (transfer.direction == TransferDirection.DOWNLOAD &&
                transfer.state != TransferState.COMPLETED
            ) {
                transfer.filePath?.let { path -> runCatching { File(path).delete() } }
            }
        }

        activeJobs.remove(transferId)?.cancel()
        acceptWaiters.remove(transferId)?.complete(-1)
        completionWaiters.remove(transferId)?.complete(false)
        incomingSessions.remove(transferId)
        fileRepository.updateTransfer(transferId, TransferState.CANCELLED, 0f, 0L)
        logger.i(TAG, "Transfer cancelled: $transferId")
    }

    override suspend fun getTransferHistory(limit: Int): List<FileTransfer> =
        fileRepository.getTransferHistory(limit.coerceIn(1, MAX_HISTORY_LIMIT))

    override fun getActiveTransfers(): Flow<List<FileTransfer>> =
        fileRepository.getActiveTransfers()

    override fun retryTransfer(transferId: String): Flow<FileTransfer> = flow {
        val previous = fileRepository.getTransferById(transferId)
            ?: throw IllegalArgumentException("Transfer not found")
        val path = previous.filePath
            ?: throw IllegalArgumentException("Original file is no longer available")
        val file = File(path)
        if (previous.direction != TransferDirection.UPLOAD ||
            !file.isFile || !file.exists() || !file.canRead()
        ) {
            throw IllegalArgumentException("Original file is no longer available")
        }
        // A durable checkpoint is safe only for the exact source bytes that
        // produced it.  If the user edited/replaced the source file after a
        // failure, start a fresh transfer ID instead of asking the receiver
        // to append new bytes to an old partial file.
        val canResume = previous.fileHash?.let { storedHash ->
            runCatching { sha256File(file).equals(storedHash, ignoreCase = true) }
                .getOrDefault(false)
        } == true
        emitAll(
            sendFileFlow(
                file = file,
                targetDeviceId = previous.deviceId,
                resumeTransfer = previous.takeIf { canResume }
            )
        )
    }.flowOn(Dispatchers.IO)

    /** Batch helper used by share flows. */
    suspend fun sendFiles(files: List<File>, targetDeviceId: String): Flow<List<FileTransfer>> = flow {
        val latest = LinkedHashMap<String, FileTransfer>()
        for (file in files) {
            sendFile(file, targetDeviceId).collect { transfer ->
                latest[transfer.id] = transfer
                emit(latest.values.toList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Called by ConnectionManager for every authenticated raw data packet. */
    fun handleReceivedData(deviceId: String, data: ByteArray) {
        scope.launch { handleReceivedDataInternal(deviceId, data) }
    }

    private suspend fun handleReceivedDataInternal(deviceId: String, data: ByteArray) {
        incomingPacketMutex.withLock {
            val packet = FilePacketCodec.decode(data)
            if (packet == null) {
                logger.w(TAG, "Ignoring malformed file packet from $deviceId")
                return@withLock
            }
            try {
                when (packet.type) {
                    FilePacketCodec.PacketType.REQUEST -> handleRequest(deviceId, packet)
                    FilePacketCodec.PacketType.ACCEPT -> handleAccept(deviceId, packet)
                    FilePacketCodec.PacketType.REJECT -> handleReject(deviceId, packet)
                    FilePacketCodec.PacketType.CHUNK -> handleChunk(deviceId, packet)
                    FilePacketCodec.PacketType.COMPLETE -> handleComplete(deviceId, packet)
                    FilePacketCodec.PacketType.CANCEL -> handleCancel(deviceId, packet)
                    FilePacketCodec.PacketType.ERROR -> handleError(deviceId, packet)
                    FilePacketCodec.PacketType.ACK -> handleAck(deviceId, packet)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Failed to handle file packet ${packet.type}", e)
                try {
                    connectionManager.sendData(
                        deviceId,
                        FilePacketCodec.error(packet.transferId, e.message ?: "Invalid file packet")
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // The link may already be gone; the original packet
                    // error is still recorded above.
                }
            }
        }
    }

    private suspend fun handleAccept(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId || transfer.direction != TransferDirection.UPLOAD) {
            logger.w(TAG, "Ignoring accept for transfer owned by another device: ${packet.transferId}")
            return
        }
        val maxSequence = ((transfer.fileSize + CHUNK_SIZE - 1L) / CHUNK_SIZE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (packet.sequence !in 0..maxSequence) {
            logger.w(TAG, "Ignoring invalid accept sequence ${packet.sequence}")
            return
        }
        acceptWaiters[packet.transferId]?.complete(packet.sequence)
    }

    private suspend fun handleRequest(deviceId: String, packet: FilePacketCodec.Packet) {
        val metadata = FilePacketCodec.decodeRequestMetadata(packet)
        val hash = packet.hash
        val fileSize = packet.totalSize
        if (metadata == null || hash == null || !isSha256(hash) || fileSize < 0) {
            throw IllegalArgumentException("Invalid file request")
        }

        val safeName = sanitizeFileName(metadata.fileName)
        if (safeName.isBlank()) throw IllegalArgumentException("Invalid file name")
        val existing = fileRepository.getTransferById(packet.transferId)
        if (existing != null) {
            if (existing.deviceId != deviceId || existing.direction != TransferDirection.DOWNLOAD) {
                throw SecurityException("Transfer id collision")
            }
            if (existing.fileSize != fileSize || !existing.fileHash.equals(hash, ignoreCase = true)) {
                throw SecurityException("Transfer metadata changed")
            }
            when (existing.state) {
                TransferState.TRANSFERRING -> {
                    val resumed = reconcileIncomingCheckpoint(existing)
                    connectionManager.sendData(
                        deviceId,
                        FilePacketCodec.accept(packet.transferId, resumed.nextSequence.coerceAtLeast(0))
                    )
                }
                TransferState.COMPLETED -> {
                    // The sender may have missed the final ACK and retried
                    // REQUEST. Replaying completion ACK is idempotent.
                    connectionManager.sendData(deviceId, FilePacketCodec.ack(packet.transferId))
                }
                TransferState.FAILED, TransferState.CANCELLED -> {
                    // A fresh request with the same ID starts a new receiver
                    // session. Do not leave stale partial bytes as a resume
                    // checkpoint after a terminal failure/cancellation.
                    existing.filePath?.let { path -> runCatching { File(path).delete() } }
                    fileRepository.updateTransfer(
                        packet.transferId,
                        TransferState.PENDING,
                        0f,
                        0L,
                        null
                    )
                    fileRepository.updateTransferSession(packet.transferId, hash, 0)
                    incomingSessions[packet.transferId] = IncomingSession(nextSequence = 0)
                }
                else -> Unit
            }
            return
        }

        val output = createDownloadFile(packet.transferId, safeName)
        val transfer = FileTransfer(
            id = packet.transferId,
            fileName = safeName,
            fileSize = fileSize,
            mimeType = metadata.mimeType.ifBlank { "application/octet-stream" },
            deviceId = deviceId,
            direction = TransferDirection.DOWNLOAD,
            state = TransferState.PENDING,
            progress = 0f,
            timestamp = System.currentTimeMillis(),
            filePath = output.absolutePath,
            fileHash = hash
        )
        if (!output.exists()) output.createNewFile()
        fileRepository.saveTransfer(transfer, output.absolutePath, 0L)
        fileRepository.updateTransferSession(packet.transferId, hash, 0)
        incomingSessions[packet.transferId] = IncomingSession(nextSequence = 0)
        logger.i(TAG, "Incoming file request queued: $safeName ($fileSize bytes)")
    }

    /**
     * Reconcile the durable Room checkpoint with the actual destination file
     * before accepting a resumed upload. A process can die between the file
     * write and either Room update; accepting the larger value would create a
     * hole or append bytes at the wrong offset.
     */
    private suspend fun reconcileIncomingCheckpoint(transfer: FileTransfer): FileTransfer {
        val expectedBytes = transfer.bytesTransferred.coerceIn(0L, transfer.fileSize)
        val path = transfer.filePath ?: return transfer.copy(
            bytesTransferred = 0L,
            nextSequence = 0,
            progress = 0f
        )
        val file = File(path)
        val fileMatchesCheckpoint = file.exists() && file.length() == expectedBytes
        val durableBytes = if (fileMatchesCheckpoint) expectedBytes else 0L
        if (!fileMatchesCheckpoint) {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(0L) }
            logger.w(TAG, "Resetting mismatched incoming checkpoint: ${transfer.id}")
        }
        val durableSequence = if (durableBytes == 0L) {
            0
        } else {
            ((durableBytes + CHUNK_SIZE - 1L) / CHUNK_SIZE)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
        val progress = if (transfer.fileSize == 0L) 0f
        else durableBytes.toFloat() / transfer.fileSize
        if (transfer.bytesTransferred != durableBytes ||
            transfer.nextSequence != durableSequence ||
            transfer.progress != progress
        ) {
            fileRepository.updateTransfer(
                transfer.id,
                TransferState.TRANSFERRING,
                progress,
                durableBytes
            )
        }
        if (transfer.nextSequence != durableSequence) {
            fileRepository.updateTransferSession(
                transfer.id,
                transfer.fileHash,
                durableSequence
            )
        }
        return transfer.copy(
            bytesTransferred = durableBytes,
            nextSequence = durableSequence,
            progress = progress
        )
    }

    private suspend fun handleReject(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId || transfer.direction != TransferDirection.UPLOAD) {
            logger.w(TAG, "Ignoring reject for transfer owned by another device: ${packet.transferId}")
            return
        }
        if (transfer.state == TransferState.COMPLETED) return
        acceptWaiters.remove(packet.transferId)?.complete(-1)
        completionWaiters.remove(packet.transferId)?.complete(false)
        fileRepository.updateTransfer(
            packet.transferId,
            TransferState.CANCELLED,
            0f,
            0L,
            FilePacketCodec.payloadText(packet).ifBlank { "Rejected by receiver" }
        )
    }

    private suspend fun handleChunk(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId) {
            logger.w(TAG, "Ignoring chunk from wrong device for transfer: ${packet.transferId}")
            return
        }
        if (transfer.direction != TransferDirection.DOWNLOAD ||
            transfer.state != TransferState.TRANSFERRING
        ) {
            try {
                connectionManager.sendData(
                    deviceId,
                    FilePacketCodec.error(packet.transferId, "File was not accepted")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The peer may already have disconnected.
            }
            return
        }
        if (packet.sequence < 0 ||
            packet.totalSize != transfer.fileSize ||
            packet.payload.isEmpty() ||
            packet.payload.size > CHUNK_SIZE
        ) {
            throw IllegalArgumentException("Chunk size metadata mismatch")
        }

        val session = incomingSessions.computeIfAbsent(packet.transferId) {
            IncomingSession(transfer.nextSequence.toLong().coerceAtLeast(0L))
        }
        session.mutex.withLock {
            val current = fileRepository.getTransferById(packet.transferId)
                ?: throw IllegalStateException("Transfer disappeared")
            if (current.deviceId != deviceId ||
                current.direction != TransferDirection.DOWNLOAD ||
                current.state != TransferState.TRANSFERRING ||
                current.fileSize != packet.totalSize
            ) {
                throw IllegalStateException("File transfer is no longer active")
            }
            // bytesTransferred and nextSequence are persisted in separate
            // Room updates. After a process death, derive the sequence from
            // the durable byte count so a crash between those updates cannot
            // append at the wrong offset during resume.
            val durableSequence = if (current.bytesTransferred <= 0L) {
                0L
            } else {
                (current.bytesTransferred + CHUNK_SIZE - 1L) / CHUNK_SIZE
            }
            if (session.nextSequence != durableSequence) {
                session.nextSequence = durableSequence
            }
            if (packet.sequence < session.nextSequence) {
                // A duplicate is harmless; acknowledge it so the sender can
                // finish a retry without appending the bytes twice.
                return@withLock
            }
            if (packet.sequence.toLong() != session.nextSequence) {
                throw IllegalArgumentException("Out-of-order file chunk")
            }

            val path = current.filePath ?: throw IllegalStateException("Missing destination path")
            val destination = File(path)
            if (!destination.exists() || destination.length() != current.bytesTransferred) {
                throw IllegalStateException("Destination checkpoint does not match persisted bytes")
            }
            val nextBytes = current.bytesTransferred + packet.payload.size
            if (nextBytes > current.fileSize) throw IllegalArgumentException("File exceeds declared size")
            RandomAccessFile(path, "rw").use { file ->
                file.seek(current.bytesTransferred)
                file.write(packet.payload)
            }
            session.nextSequence = packet.sequence.toLong() + 1L
            fileRepository.updateTransfer(
                packet.transferId,
                TransferState.TRANSFERRING,
                if (current.fileSize == 0L) 0f else nextBytes.toFloat() / current.fileSize,
                nextBytes
            )
            fileRepository.updateTransferSession(packet.transferId, current.fileHash, session.nextSequence.toInt())
        }
        // Send outside the session mutex: a slow transport must not block the
        // next packet from being validated, while duplicate packets still get
        // an idempotent ACK.
        connectionManager.sendData(deviceId, FilePacketCodec.ack(packet.transferId, packet.sequence))
    }

    private suspend fun handleComplete(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId) {
            logger.w(TAG, "Ignoring completion from wrong device for transfer: ${packet.transferId}")
            return
        }
        val expectedHash = transfer.fileHash
        val path = transfer.filePath
        val valid = runCatching {
            if (transfer.fileSize == 0L && path != null) {
                File(path).parentFile?.mkdirs()
                if (!File(path).exists()) File(path).createNewFile()
            }
            transfer.direction == TransferDirection.DOWNLOAD &&
                transfer.bytesTransferred == transfer.fileSize &&
                packet.sequence == -1 &&
                packet.totalSize == transfer.fileSize &&
                packet.hash != null &&
                expectedHash != null &&
                packet.hash.equals(expectedHash, ignoreCase = true) &&
                path != null &&
                sha256File(File(path)).equals(packet.hash, ignoreCase = true)
        }.getOrDefault(false)

        if (!valid) {
            // Do not retain a session after a terminal verification failure.
            // Otherwise a later chunk can keep a stale mutex/state object alive
            // until process death, and a retry may observe obsolete sequencing.
            incomingSessions.remove(packet.transferId)
            fileRepository.updateTransfer(
                packet.transferId,
                TransferState.FAILED,
                transfer.progress,
                transfer.bytesTransferred,
                "File hash or length verification failed"
            )
            connectionManager.sendData(deviceId, FilePacketCodec.error(packet.transferId, "File verification failed"))
            return
        }

        fileRepository.updateTransfer(packet.transferId, TransferState.COMPLETED, 1f, transfer.fileSize)
        incomingSessions.remove(packet.transferId)
        connectionManager.sendData(deviceId, FilePacketCodec.ack(packet.transferId))
        logger.i(TAG, "Incoming file verified: ${transfer.fileName}")
    }

    private suspend fun handleCancel(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId) {
            logger.w(TAG, "Ignoring cancel from wrong device for transfer: ${packet.transferId}")
            return
        }
        if (transfer.state == TransferState.COMPLETED) return
        acceptWaiters.remove(packet.transferId)?.complete(-1)
        completionWaiters.remove(packet.transferId)?.complete(false)
        incomingSessions.remove(packet.transferId)
        if (transfer.direction == TransferDirection.DOWNLOAD) {
            transfer.filePath?.let { path -> runCatching { File(path).delete() } }
        }
        fileRepository.updateTransfer(packet.transferId, TransferState.CANCELLED, 0f, 0L)
    }

    private suspend fun handleError(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId) {
            logger.w(TAG, "Ignoring error from wrong device for transfer: ${packet.transferId}")
            return
        }
        if (transfer.state == TransferState.COMPLETED) return
        acceptWaiters.remove(packet.transferId)?.complete(-1)
        completionWaiters.remove(packet.transferId)?.complete(false)
        incomingSessions.remove(packet.transferId)
        fileRepository.updateTransfer(
            packet.transferId,
            TransferState.FAILED,
            transfer.progress,
            transfer.bytesTransferred,
            FilePacketCodec.payloadText(packet).ifBlank { "Peer reported a file error" }
        )
    }

    private suspend fun handleAck(deviceId: String, packet: FilePacketCodec.Packet) {
        val transfer = fileRepository.getTransferById(packet.transferId) ?: return
        if (transfer.deviceId != deviceId || transfer.direction != TransferDirection.UPLOAD) {
            logger.w(TAG, "Ignoring ACK for transfer owned by another device: ${packet.transferId}")
            return
        }
        if (packet.sequence >= 0) {
            chunkAckWaiters[chunkAckKey(packet.transferId, packet.sequence)]?.complete(Unit)
        } else {
            completionWaiters[packet.transferId]?.complete(true)
        }
    }

    private suspend fun sendPacketWithRetry(deviceId: String, packet: ByteArray) {
        var lastError: Throwable? = null
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            try {
                if (connectionManager.sendData(deviceId, packet)) return
                lastError = IllegalStateException("Connection rejected file packet")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt + 1 < MAX_SEND_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        throw IllegalStateException("Unable to send file packet", lastError)
    }

    private suspend fun ensureConnection(deviceId: String) {
        val existing = try {
            connectionManager.getActiveConnections().first()
                .firstOrNull { it.deviceId == deviceId && it.state == ConnectionState.CONNECTED }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (existing != null) return

        for (connectionType in connectionPolicyStore.get().orderedConnectionTypes()) {
            var connected = false
            try {
                connectionManager.connect(deviceId, connectionType).collect { state ->
                    if (state.deviceId == deviceId && state.state == ConnectionState.CONNECTED) {
                        connected = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "${connectionType.name} connection failed: ${e.message}")
            }
            if (connected) return
        }
        val afterConnect = try {
            connectionManager.getActiveConnections().first()
                .any { it.deviceId == deviceId && it.state == ConnectionState.CONNECTED }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!afterConnect) {
            throw IllegalStateException("Unable to establish connection")
        }
    }

    private suspend fun emitCurrent(transferId: String): FileTransfer? =
        fileRepository.getTransferById(transferId)

    private fun createDownloadFile(transferId: String, fileName: String): File {
        val base = runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        }.getOrNull() ?: File(System.getProperty("java.io.tmpdir"), "smslink-downloads")
        val directory = File(base, "SMSlink")
        directory.mkdirs()
        return File(directory, "$transferId-$fileName")
    }

    private fun sanitizeFileName(value: String): String = value
        .map { character ->
            when {
                character.code < 0x20 || character in ILLEGAL_FILENAME_CHARS -> '_'
                else -> character
            }
        }
        .joinToString("")
        .replace("..", "_")
        .trim()
        .trimEnd('.', ' ')
        .take(MAX_FILENAME_LENGTH)

    private fun getMimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun skipFully(input: FileInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw IllegalStateException("Unable to seek to transfer resume position")
            }
        }
    }

    private fun isSha256(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{64}"))

    private suspend fun selectLinkType(deviceId: String): String {
        val type = try {
            connectionManager.getActiveConnections().first()
                .firstOrNull { it.deviceId == deviceId && it.state == ConnectionState.CONNECTED }
                ?.type
                ?.name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return type?.let { connectionType ->
            when (connectionType) {
                ConnectionType.HOTSPOT.name -> "WIFI_HOTSPOT"
                ConnectionType.BLUETOOTH.name -> "BLUETOOTH"
                else -> "WIFI_LAN"
            }
        } ?: "WIFI_LAN"
    }

    private suspend fun ensureActive() {
        if (!currentCoroutineContext().isActive) throw CancellationException()
    }

    private fun chunkAckKey(transferId: String, sequence: Int): String = "$transferId:$sequence"

    private data class IncomingSession(
        var nextSequence: Long,
        val mutex: Mutex = Mutex()
    )

    private sealed interface StartSignal {
        data class Accepted(val sequence: Int) : StartSignal
        data object AlreadyCompleted : StartSignal
    }

    companion object {
        private const val TAG = "FileTransfer"
        private const val CHUNK_SIZE = 32 * 1024
        private const val MAX_FILENAME_LENGTH = 255
        private const val MAX_SEND_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 400L
        private const val CONFIRM_TIMEOUT_MS = 5 * 60 * 1000L
        private const val CHUNK_ACK_TIMEOUT_MS = 15_000L
        private const val COMPLETE_ACK_TIMEOUT_MS = 30_000L
        private const val MAX_HISTORY_LIMIT = 500
        private val ILLEGAL_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private val TERMINAL_STATES = setOf(
            TransferState.COMPLETED,
            TransferState.FAILED,
            TransferState.CANCELLED
        )
    }
}
