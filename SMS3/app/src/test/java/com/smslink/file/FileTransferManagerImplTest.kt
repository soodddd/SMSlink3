package com.smslink.file

import com.smslink.core.model.FileTransfer
import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState
import com.smslink.file.data.FileRepository
import com.smslink.network.IConnectionManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 文件传输管理器测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerImplTest {

    private lateinit var fileTransferManager: FileTransferManagerImpl
    private lateinit var connectionManager: IConnectionManager
    private lateinit var fileRepository: FileRepository
    private lateinit var mockFile: File

    @Before
    fun setup() {
        connectionManager = mockk(relaxed = true)
        fileRepository = mockk(relaxed = true)

        // Mock context
        val context = mockk<android.content.Context>(relaxed = true)
        val logger = mockk<com.smslink.core.log.ILogger>(relaxed = true)

        fileTransferManager = FileTransferManagerImpl(
            context,
            connectionManager,
            fileRepository,
            logger
        )

        // Mock file
        mockFile = mockk(relaxed = true)
        every { mockFile.exists() } returns true
        every { mockFile.canRead() } returns true
        every { mockFile.name } returns "test.txt"
        every { mockFile.length() } returns 1024L
        every { mockFile.absolutePath } returns "/test/test.txt"
        every { mockFile.extension } returns "txt"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `sendFile should create transfer record`() = runTest {
        // Given
        val deviceId = "device123"
        val realFile = File(System.getProperty("java.io.tmpdir"), "test.txt").apply {
            if (exists()) delete()
            createNewFile()
            writeText("x".repeat(1024))
            deleteOnExit()
        }
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getTransferById(any()) } returns null
        coEvery { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        val flow = fileTransferManager.sendFile(realFile, deviceId)
        val transfer = flow.first()

        // Then
        assertNotNull(transfer)
        assertEquals("test.txt", transfer.fileName)
        assertEquals(1024L, transfer.fileSize)
        assertEquals(deviceId, transfer.deviceId)
        assertEquals(TransferDirection.UPLOAD, transfer.direction)
        assertEquals(TransferState.PENDING, transfer.state)

        coVerify { fileRepository.saveTransferWithLink(any(), any(), any(), any()) }
    }

    @Test
    fun `cancelTransfer should update state to cancelled`() = runTest {
        // Given
        val transferId = "transfer123"
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs

        // When
        fileTransferManager.cancelTransfer(transferId)

        // Then
        coVerify {
            fileRepository.updateTransfer(
                transferId,
                TransferState.CANCELLED,
                0f,
                0L
            )
        }
    }

    @Test
    fun `getTransferHistory should return history list`() = runTest {
        // Given
        val expectedHistory = listOf(
            FileTransfer(
                id = "1",
                fileName = "file1.txt",
                fileSize = 1024L,
                mimeType = "text/plain",
                deviceId = "device1",
                direction = TransferDirection.UPLOAD,
                state = TransferState.COMPLETED,
                progress = 1f,
                timestamp = System.currentTimeMillis()
            )
        )
        coEvery { fileRepository.getTransferHistory(any()) } returns expectedHistory

        // When
        val history = fileTransferManager.getTransferHistory(50)

        // Then
        assertEquals(1, history.size)
        assertEquals("file1.txt", history[0].fileName)
        coVerify { fileRepository.getTransferHistory(50) }
    }

    @Test
    fun `getActiveTransfers should return active transfers flow`() = runTest {
        // Given
        val activeTransfers = listOf(
            FileTransfer(
                id = "1",
                fileName = "file1.txt",
                fileSize = 1024L,
                mimeType = "text/plain",
                deviceId = "device1",
                direction = TransferDirection.UPLOAD,
                state = TransferState.TRANSFERRING,
                progress = 0.5f,
                timestamp = System.currentTimeMillis()
            )
        )
        every { fileRepository.getActiveTransfers() } returns flowOf(activeTransfers)

        // When
        val result = fileTransferManager.getActiveTransfers().first()

        // Then
        assertEquals(1, result.size)
        assertEquals(TransferState.TRANSFERRING, result[0].state)
    }

    @Test
    fun `receiveFile should return transfer flow`() = runTest {
        // Given
        val transferId = "transfer123"
        val transfer = FileTransfer(
            id = transferId,
            fileName = "received.txt",
            fileSize = 2048L,
            mimeType = "text/plain",
            deviceId = "device1",
            direction = TransferDirection.DOWNLOAD,
            state = TransferState.PENDING,
            progress = 0f,
            timestamp = System.currentTimeMillis()
        )
        coEvery { fileRepository.getTransferById(transferId) } returns transfer
        every { fileRepository.getActiveTransfers() } returns flowOf(listOf(transfer))

        // When
        val flow = fileTransferManager.receiveFile(transferId)
        val result = flow.first()

        // Then
        assertEquals(transferId, result.id)
        assertEquals("received.txt", result.fileName)
        assertEquals(TransferDirection.DOWNLOAD, result.direction)
    }

    @Test
    fun `sendFile should handle file not found error`() = runTest {
        // Given
        val deviceId = "device123"
        every { mockFile.exists() } returns false

        // When & Then
        try {
            val flow = fileTransferManager.sendFile(mockFile, deviceId)
            flow.first()
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("File not found or not readable", e.message)
        }
    }

    @Test
    fun `sendFile should handle file not readable error`() = runTest {
        // Given
        val deviceId = "device123"
        every { mockFile.exists() } returns true
        every { mockFile.canRead() } returns false

        // When & Then
        try {
            val flow = fileTransferManager.sendFile(mockFile, deviceId)
            flow.first()
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("File not found or not readable", e.message)
        }
    }

    @Test
    fun `receiveFile should throw error when transfer not found`() = runTest {
        // Given
        val transferId = "nonexistent"
        coEvery { fileRepository.getTransferById(transferId) } returns null

        // When & Then
        try {
            val flow = fileTransferManager.receiveFile(transferId)
            flow.first()
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Transfer not found", e.message)
        }
    }

    @Test
    fun `cancelTransfer should cancel active job`() = runTest {
        // Given
        val transferId = "transfer123"
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs

        // When
        fileTransferManager.cancelTransfer(transferId)

        // Then
        coVerify {
            fileRepository.updateTransfer(
                transferId,
                TransferState.CANCELLED,
                0f,
                0L
            )
        }
    }

    @Test
    fun `getTransferHistory should return limited results`() = runTest {
        // Given
        val limit = 10
        val expectedHistory = List(limit) { index ->
            FileTransfer(
                id = "transfer$index",
                fileName = "file$index.txt",
                fileSize = 1024L,
                mimeType = "text/plain",
                deviceId = "device1",
                direction = TransferDirection.UPLOAD,
                state = TransferState.COMPLETED,
                progress = 1f,
                timestamp = System.currentTimeMillis()
            )
        }
        coEvery { fileRepository.getTransferHistory(limit) } returns expectedHistory

        // When
        val history = fileTransferManager.getTransferHistory(limit)

        // Then
        assertEquals(limit, history.size)
        coVerify { fileRepository.getTransferHistory(limit) }
    }

    @Test
    fun `sendFile should update progress during transfer`() = runTest {
        // Given
        val deviceId = "device123"
        val transfers = mutableListOf<FileTransfer>()
        val realFile = File(System.getProperty("java.io.tmpdir"), "progress.txt").apply {
            if (exists()) delete()
            createNewFile()
            writeText("x".repeat(1024))
            deleteOnExit()
        }
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getTransferById(any()) } returns null
        every { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        val flow = fileTransferManager.sendFile(realFile, deviceId)
        flow.collect { transfer ->
            transfers.add(transfer)
        }

        // Then
        assertTrue(transfers.isNotEmpty())
        assertEquals("progress.txt", transfers.first().fileName)
    }
}
