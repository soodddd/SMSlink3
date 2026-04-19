package com.smslink.file.data

import com.smslink.core.model.TransferDirection
import com.smslink.core.model.TransferState
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件仓库测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileRepositoryTest {

    private lateinit var fileRepository: FileRepository
    private lateinit var fileTransferDao: FileTransferDao

    @Before
    fun setup() {
        fileTransferDao = mockk(relaxed = true)
        fileRepository = FileRepository(fileTransferDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `saveTransfer should insert entity`() = runTest {
        // Given
        val transfer = createTestTransfer()
        coEvery { fileTransferDao.insert(any()) } just Runs

        // When
        fileRepository.saveTransfer(transfer, "/path/to/file", 0L)

        // Then
        coVerify {
            fileTransferDao.insert(
                match {
                    it.id == transfer.id &&
                    it.fileName == transfer.fileName &&
                    it.filePath == "/path/to/file"
                }
            )
        }
    }

    @Test
    fun `updateTransfer should update entity`() = runTest {
        // Given
        val transferId = "transfer123"
        val entity = createTestEntity(transferId)
        coEvery { fileTransferDao.getById(transferId) } returns entity
        coEvery { fileTransferDao.update(any()) } just Runs

        // When
        fileRepository.updateTransfer(
            transferId,
            TransferState.COMPLETED,
            1f,
            1024L,
            null
        )

        // Then
        coVerify {
            fileTransferDao.update(
                match {
                    it.id == transferId &&
                    it.state == TransferState.COMPLETED &&
                    it.progress == 1f &&
                    it.bytesTransferred == 1024L
                }
            )
        }
    }

    @Test
    fun `getTransferById should return transfer`() = runTest {
        // Given
        val transferId = "transfer123"
        val entity = createTestEntity(transferId)
        coEvery { fileTransferDao.getById(transferId) } returns entity

        // When
        val result = fileRepository.getTransferById(transferId)

        // Then
        assertNotNull(result)
        assertEquals(transferId, result.id)
        assertEquals("test.txt", result.fileName)
    }

    @Test
    fun `getTransferById should return null when not found`() = runTest {
        // Given
        val transferId = "nonexistent"
        coEvery { fileTransferDao.getById(transferId) } returns null

        // When
        val result = fileRepository.getTransferById(transferId)

        // Then
        assertNull(result)
    }

    @Test
    fun `getTransferHistory should return list`() = runTest {
        // Given
        val entities = listOf(
            createTestEntity("1"),
            createTestEntity("2")
        )
        coEvery { fileTransferDao.getAll(50) } returns entities

        // When
        val result = fileRepository.getTransferHistory(50)

        // Then
        assertEquals(2, result.size)
        assertEquals("1", result[0].id)
        assertEquals("2", result[1].id)
    }

    @Test
    fun `getActiveTransfers should return flow`() = runTest {
        // Given
        val entities = listOf(createTestEntity("1"))
        every { fileTransferDao.getActiveTransfers() } returns flowOf(entities)

        // When
        val result = fileRepository.getActiveTransfers().first()

        // Then
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `deleteTransfer should delete entity`() = runTest {
        // Given
        val transferId = "transfer123"
        coEvery { fileTransferDao.delete(transferId) } just Runs

        // When
        fileRepository.deleteTransfer(transferId)

        // Then
        coVerify { fileTransferDao.delete(transferId) }
    }

    @Test
    fun `getFilePath should return path`() = runTest {
        // Given
        val transferId = "transfer123"
        val entity = createTestEntity(transferId)
        coEvery { fileTransferDao.getById(transferId) } returns entity

        // When
        val result = fileRepository.getFilePath(transferId)

        // Then
        assertEquals("/path/to/file", result)
    }

    @Test
    fun `getBytesTransferred should return bytes`() = runTest {
        // Given
        val transferId = "transfer123"
        val entity = createTestEntity(transferId).copy(bytesTransferred = 512L)
        coEvery { fileTransferDao.getById(transferId) } returns entity

        // When
        val result = fileRepository.getBytesTransferred(transferId)

        // Then
        assertEquals(512L, result)
    }

    @Test
    fun `saveTransferWithLink should save transfer with link type`() = runTest {
        // Given
        val transfer = createTestTransfer()
        val linkType = "WIFI_LAN"
        coEvery { fileTransferDao.insert(any()) } just Runs

        // When
        fileRepository.saveTransferWithLink(transfer, "/path/to/file", 0L, linkType)

        // Then
        coVerify {
            fileTransferDao.insert(
                match {
                    it.id == transfer.id &&
                    it.fileName == transfer.fileName
                }
            )
        }
    }

    @Test
    fun `updateTransferRetry should update retry count`() = runTest {
        // Given
        val transferId = "transfer123"
        val retryCount = 2
        val errorMessage = "Network error"
        val entity = createTestEntity(transferId)
        coEvery { fileTransferDao.getById(transferId) } returns entity
        coEvery { fileTransferDao.update(any()) } just Runs

        // When
        fileRepository.updateTransferRetry(transferId, retryCount, errorMessage)

        // Then
        coVerify { fileTransferDao.update(any()) }
    }

    @Test
    fun `getCompletedTransfers should return completed transfers`() = runTest {
        // Given
        val completedEntities = listOf(
            createTestEntity("1").copy(state = TransferState.COMPLETED),
            createTestEntity("2").copy(state = TransferState.COMPLETED)
        )
        coEvery { fileTransferDao.getByState(TransferState.COMPLETED) } returns completedEntities

        // When
        val result = fileRepository.getCompletedTransfers()

        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { it.state == TransferState.COMPLETED })
    }

    @Test
    fun `getFailedTransfers should return failed transfers`() = runTest {
        // Given
        val failedEntities = listOf(
            createTestEntity("1").copy(state = TransferState.FAILED)
        )
        coEvery { fileTransferDao.getByState(TransferState.FAILED) } returns failedEntities

        // When
        val result = fileRepository.getFailedTransfers()

        // Then
        assertEquals(1, result.size)
        assertEquals(TransferState.FAILED, result[0].state)
    }

    @Test
    fun `getTransfersByDevice should return transfers for device`() = runTest {
        // Given
        val deviceId = "device1"
        val entities = listOf(
            createTestEntity("1").copy(deviceId = deviceId),
            createTestEntity("2").copy(deviceId = deviceId)
        )
        coEvery { fileTransferDao.getByDevice(deviceId) } returns entities

        // When
        val result = fileRepository.getTransfersByDevice(deviceId)

        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { it.deviceId == deviceId })
    }

    @Test
    fun `clearCompletedTransfers should delete completed transfers`() = runTest {
        // Given
        coEvery { fileTransferDao.deleteByState(TransferState.COMPLETED) } just Runs

        // When
        fileRepository.clearCompletedTransfers()

        // Then
        coVerify { fileTransferDao.deleteByState(TransferState.COMPLETED) }
    }

    @Test
    fun `getTransferCount should return total count`() = runTest {
        // Given
        val expectedCount = 10
        coEvery { fileTransferDao.getCount() } returns expectedCount

        // When
        val result = fileRepository.getTransferCount()

        // Then
        assertEquals(expectedCount, result)
    }

    private fun createTestTransfer() = com.smslink.core.model.FileTransfer(
        id = "transfer123",
        fileName = "test.txt",
        fileSize = 1024L,
        mimeType = "text/plain",
        deviceId = "device1",
        direction = TransferDirection.UPLOAD,
        state = TransferState.PENDING,
        progress = 0f,
        timestamp = System.currentTimeMillis()
    )

    private fun createTestEntity(id: String) = FileTransferEntity(
        id = id,
        fileName = "test.txt",
        filePath = "/path/to/file",
        fileSize = 1024L,
        mimeType = "text/plain",
        deviceId = "device1",
        direction = TransferDirection.UPLOAD,
        state = TransferState.PENDING,
        progress = 0f,
        bytesTransferred = 0L,
        timestamp = System.currentTimeMillis()
    )
}
