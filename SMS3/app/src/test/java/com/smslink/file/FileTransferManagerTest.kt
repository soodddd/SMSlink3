package com.smslink.file

import com.smslink.core.log.ILogger
import com.smslink.core.model.*
import com.smslink.file.data.FileRepository
import com.smslink.network.IConnectionManager
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import android.content.Context
import org.junit.Assert.*

/**
 * 文件传输管理器测试
 */
class FileTransferManagerTest {

    private lateinit var fileTransferManager: FileTransferManagerImpl
    private lateinit var context: Context
    private lateinit var connectionManager: IConnectionManager
    private lateinit var fileRepository: FileRepository
    private lateinit var logger: ILogger

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        fileRepository = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        fileTransferManager = FileTransferManagerImpl(
            context,
            connectionManager,
            fileRepository,
            logger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test link selection for large file prefers WiFi`() = runTest {
        // Given
        val largeFile = mockk<File> {
            every { isFile } returns true
            every { exists() } returns true
            every { canRead() } returns true
            every { name } returns "large_file.mp4"
            every { length() } returns 15 * 1024 * 1024L // 15MB
            every { extension } returns "mp4"
            every { absolutePath } returns "/test/large_file.mp4"
        }

        val wifiConnection = Connection(
            deviceId = "device1",
            type = ConnectionType.WIFI,
            state = ConnectionState.CONNECTED,
            quality = ConnectionQuality.EXCELLENT,
            lastActivity = System.currentTimeMillis()
        )

        coEvery { connectionManager.getActiveConnections() } returns flowOf(listOf(wifiConnection))
        coEvery { connectionManager.sendData(any(), any()) } returns true
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getBytesTransferred(any()) } returns 0L
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        fileTransferManager.sendFile(largeFile, "device1").first()

        // Then
        coVerify {
            fileRepository.saveTransferWithLink(
                any(),
                any(),
                any(),
                "WIFI_LAN"
            )
        }
    }

    @Test
    fun `test link selection for small file accepts Bluetooth`() = runTest {
        // Given
        val smallFile = mockk<File> {
            every { isFile } returns true
            every { exists() } returns true
            every { canRead() } returns true
            every { name } returns "small_file.txt"
            every { length() } returns 500 * 1024L // 500KB
            every { extension } returns "txt"
            every { absolutePath } returns "/test/small_file.txt"
        }

        val bluetoothConnection = Connection(
            deviceId = "device1",
            type = ConnectionType.BLUETOOTH,
            state = ConnectionState.CONNECTED,
            quality = ConnectionQuality.GOOD,
            lastActivity = System.currentTimeMillis()
        )

        coEvery { connectionManager.getActiveConnections() } returns flowOf(listOf(bluetoothConnection))
        coEvery { connectionManager.sendData(any(), any()) } returns true
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getBytesTransferred(any()) } returns 0L
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        fileTransferManager.sendFile(smallFile, "device1").first()

        // Then
        coVerify {
            fileRepository.saveTransferWithLink(
                any(),
                any(),
                any(),
                "BLUETOOTH"
            )
        }
    }

    @Test
    fun `test resume transfer from checkpoint`() = runTest {
        // Given
        val file = mockk<File> {
            every { isFile } returns true
            every { exists() } returns true
            every { canRead() } returns true
            every { name } returns "resume_file.pdf"
            every { length() } returns 5 * 1024 * 1024L // 5MB
            every { extension } returns "pdf"
            every { absolutePath } returns "/test/resume_file.pdf"
        }

        val resumePosition = 2 * 1024 * 1024L // 2MB already transferred

        val wifiConnection = Connection(
            deviceId = "device1",
            type = ConnectionType.WIFI,
            state = ConnectionState.CONNECTED,
            quality = ConnectionQuality.EXCELLENT,
            lastActivity = System.currentTimeMillis()
        )

        coEvery { connectionManager.getActiveConnections() } returns flowOf(listOf(wifiConnection))
        coEvery { connectionManager.sendData(any(), any()) } returns true
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getBytesTransferred(any()) } returns resumePosition
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        fileTransferManager.sendFile(file, "device1").first()

        // Then
        coVerify(timeout = 5000) {
            fileRepository.getBytesTransferred(any())
        }
    }

    @Test
    fun `test retry on transfer failure`() = runTest {
        // Given
        val file = mockk<File> {
            every { isFile } returns true
            every { exists() } returns true
            every { canRead() } returns true
            every { name } returns "retry_file.jpg"
            every { length() } returns 1 * 1024 * 1024L // 1MB
            every { extension } returns "jpg"
            every { absolutePath } returns "/test/retry_file.jpg"
        }

        coEvery { connectionManager.getActiveConnections() } returns flowOf(emptyList())
        coEvery { connectionManager.sendData(any(), any()) } returns false // Simulate failure
        coEvery { fileRepository.saveTransferWithLink(any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.getBytesTransferred(any()) } returns 0L
        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs
        coEvery { fileRepository.updateTransferRetry(any(), any(), any()) } just Runs
        coEvery { fileRepository.getActiveTransfers() } returns flowOf(emptyList())

        // When
        fileTransferManager.sendFile(file, "device1").first()

        // Then - Should retry up to MAX_RETRY_COUNT times
        coVerify(timeout = 5000, atLeast = 1) {
            fileRepository.updateTransferRetry(any(), any(), any())
        }
    }

    @Test
    fun `test cancel transfer`() = runTest {
        // Given
        val transferId = "test-transfer-id"

        coEvery { fileRepository.updateTransfer(any(), any(), any(), any(), any()) } just Runs

        // When
        fileTransferManager.cancelTransfer(transferId)

        // Then
        coVerify {
            fileRepository.updateTransfer(
                transferId,
                TransferState.CANCELLED,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `test get transfer history`() = runTest {
        // Given
        val mockTransfers = listOf(
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

        coEvery { fileRepository.getTransferHistory(any()) } returns mockTransfers

        // When
        val result = fileTransferManager.getTransferHistory(10)

        // Then
        assertEquals(1, result.size)
        assertEquals("file1.txt", result[0].fileName)
    }

    @Test
    fun `test file not found throws exception`() = runTest {
        // Given
        val nonExistentFile = mockk<File> {
            every { isFile } returns false
            every { exists() } returns false
            every { canRead() } returns false
        }

        // When/Then
        try {
            fileTransferManager.sendFile(nonExistentFile, "device1").first()
            fail("Should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not found") == true)
        }
    }
}
