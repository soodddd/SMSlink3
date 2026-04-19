package com.smslink.sms

import com.smslink.core.database.dao.MessageDao
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SmsRepository 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsRepositoryTest {

    private lateinit var messageDao: MessageDao
    private lateinit var repository: SmsRepository

    @Before
    fun setup() {
        messageDao = mockk(relaxed = true)
        repository = SmsRepository(messageDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getAllMessages should return messages from dao`() = runTest {
        // Given
        val expectedMessages = listOf(
            createTestMessage("1"),
            createTestMessage("2")
        )
        coEvery { messageDao.getAll(any()) } returns flowOf(expectedMessages)

        // When
        val result = repository.getAllMessages(100).first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getAll(100) }
    }

    @Test
    fun `getMessagesByThread should return messages for thread`() = runTest {
        // Given
        val threadId = "thread-1"
        val expectedMessages = listOf(createTestMessage("1"))
        coEvery { messageDao.getByThreadId(threadId) } returns flowOf(expectedMessages)

        // When
        val result = repository.getMessagesByThread(threadId).first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getByThreadId(threadId) }
    }

    @Test
    fun `insertMessage should call dao insert`() = runTest {
        // Given
        val message = createTestMessage("1")
        coEvery { messageDao.insert(any()) } just Runs

        // When
        repository.insertMessage(message)

        // Then
        coVerify { messageDao.insert(message) }
    }

    @Test
    fun `insertMessages should call dao insertAll`() = runTest {
        // Given
        val messages = listOf(
            createTestMessage("1"),
            createTestMessage("2")
        )
        coEvery { messageDao.insertAll(any()) } just Runs

        // When
        repository.insertMessages(messages)

        // Then
        coVerify { messageDao.insertAll(messages) }
    }

    @Test
    fun `markAsRead should call dao markAsRead`() = runTest {
        // Given
        val messageId = "test-id"
        coEvery { messageDao.markAsRead(messageId) } just Runs

        // When
        repository.markAsRead(messageId)

        // Then
        coVerify { messageDao.markAsRead(messageId) }
    }

    @Test
    fun `deleteMessageById should call dao deleteById`() = runTest {
        // Given
        val messageId = "test-id"
        coEvery { messageDao.deleteById(messageId) } just Runs

        // When
        repository.deleteMessageById(messageId)

        // Then
        coVerify { messageDao.deleteById(messageId) }
    }

    @Test
    fun `getUnreadCount should return count from dao`() = runTest {
        // Given
        val expectedCount = 5
        coEvery { messageDao.getUnreadCount() } returns flowOf(expectedCount)

        // When
        val result = repository.getUnreadCount().first()

        // Then
        assertEquals(expectedCount, result)
        coVerify { messageDao.getUnreadCount() }
    }

    @Test
    fun `getMessageById should return message from dao`() = runTest {
        // Given
        val messageId = "test-id"
        val expectedMessage = createTestMessage(messageId)
        coEvery { messageDao.getById(messageId) } returns expectedMessage

        // When
        val result = repository.getMessageById(messageId)

        // Then
        assertEquals(expectedMessage, result)
        coVerify { messageDao.getById(messageId) }
    }

    @Test
    fun `updateMessage should call dao update`() = runTest {
        // Given
        val message = createTestMessage("1")
        coEvery { messageDao.update(any()) } just Runs

        // When
        repository.updateMessage(message)

        // Then
        coVerify { messageDao.update(message) }
    }

    @Test
    fun `deleteAllMessages should call dao deleteAll`() = runTest {
        // Given
        coEvery { messageDao.deleteAll() } just Runs

        // When
        repository.deleteAllMessages()

        // Then
        coVerify { messageDao.deleteAll() }
    }

    @Test
    fun `getMessagesByAddress should return messages for address`() = runTest {
        // Given
        val address = "+1234567890"
        val expectedMessages = listOf(createTestMessage("1"))
        coEvery { messageDao.getByAddress(address) } returns flowOf(expectedMessages)

        // When
        val result = repository.getMessagesByAddress(address).first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getByAddress(address) }
    }

    @Test
    fun `getSentMessages should return sent messages`() = runTest {
        // Given
        val sentMessage = createTestMessage("1").copy(type = MessageType.SENT)
        val expectedMessages = listOf(sentMessage)
        coEvery { messageDao.getByType(MessageType.SENT) } returns flowOf(expectedMessages)

        // When
        val result = repository.getSentMessages().first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getByType(MessageType.SENT) }
    }

    @Test
    fun `getReceivedMessages should return received messages`() = runTest {
        // Given
        val receivedMessage = createTestMessage("1").copy(type = MessageType.INBOX)
        val expectedMessages = listOf(receivedMessage)
        coEvery { messageDao.getByType(MessageType.INBOX) } returns flowOf(expectedMessages)

        // When
        val result = repository.getReceivedMessages().first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getByType(MessageType.INBOX) }
    }

    @Test
    fun `markThreadAsRead should call dao markThreadAsRead`() = runTest {
        // Given
        val threadId = "thread-1"
        coEvery { messageDao.markThreadAsRead(threadId) } just Runs

        // When
        repository.markThreadAsRead(threadId)

        // Then
        coVerify { messageDao.markThreadAsRead(threadId) }
    }

    @Test
    fun `getUnreadMessages should return unread messages`() = runTest {
        // Given
        val unreadMessages = listOf(
            createTestMessage("1").copy(read = false),
            createTestMessage("2").copy(read = false)
        )
        coEvery { messageDao.getUnreadMessages() } returns flowOf(unreadMessages)

        // When
        val result = repository.getUnreadMessages().first()

        // Then
        assertEquals(2, result.size)
        assertFalse(result[0].read)
        assertFalse(result[1].read)
    }

    private fun createTestMessage(id: String): Message {
        return Message(
            id = id,
            threadId = "thread-1",
            address = "+1234567890",
            body = "Test message",
            timestamp = System.currentTimeMillis(),
            type = MessageType.INBOX,
            read = false,
            deviceId = "local"
        )
    }
}
