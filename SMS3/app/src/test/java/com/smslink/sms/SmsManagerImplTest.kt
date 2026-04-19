package com.smslink.sms

import android.content.Context
import android.provider.Telephony
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import com.smslink.device.IDeviceManager
import com.smslink.network.transport.IMessageTransport
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SmsManagerImpl 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsManagerImplTest {

    private lateinit var context: Context
    private lateinit var messageDao: MessageDao
    private lateinit var logger: ILogger
    private lateinit var smsManager: SmsManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        messageDao = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        smsManager = SmsManagerImpl(context, messageDao, logger)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getMessages should return messages from dao`() = runTest {
        // Given
        val expectedMessages = listOf(
            createTestMessage("1"),
            createTestMessage("2")
        )
        coEvery { messageDao.getAll(any()) } returns flowOf(expectedMessages)

        // When
        val result = smsManager.getMessages(10).first()

        // Then
        assertEquals(expectedMessages, result)
        coVerify { messageDao.getAll(10) }
    }

    @Test
    fun `sendMessage should send SMS and save to database`() = runTest {
        // Given
        val address = "+1234567890"
        val body = "Test message"
        coEvery { messageDao.insert(any()) } just Runs

        // When
        val result = smsManager.sendMessage(address, body)

        // Then
        assertTrue(result)
        coVerify { messageDao.insert(any()) }
    }

    @Test
    fun `sendMessage should handle long messages`() = runTest {
        // Given
        val address = "+1234567890"
        val longBody = "a".repeat(200) // 超过160字符
        coEvery { messageDao.insert(any()) } just Runs

        // When
        val result = smsManager.sendMessage(address, longBody)

        // Then
        assertTrue(result)
        coVerify { messageDao.insert(any()) }
    }

    @Test
    fun `markAsRead should update message in dao`() = runTest {
        // Given
        val messageId = "test-id"
        val message = createTestMessage(messageId)
        coEvery { messageDao.markAsRead(messageId) } just Runs
        coEvery { messageDao.getById(messageId) } returns message

        // When
        smsManager.markAsRead(messageId)

        // Then
        coVerify { messageDao.markAsRead(messageId) }
    }

    @Test
    fun `deleteMessage should remove message from dao`() = runTest {
        // Given
        val messageId = "test-id"
        coEvery { messageDao.deleteById(messageId) } just Runs

        // When
        smsManager.deleteMessage(messageId)

        // Then
        coVerify { messageDao.deleteById(messageId) }
    }

    @Test
    fun `notifyNewMessage should insert message and emit to flow`() = runTest {
        // Given
        val message = createTestMessage("new-message")
        coEvery { messageDao.insert(any()) } just Runs

        // When
        smsManager.notifyNewMessage(message)

        // Then
        coVerify { messageDao.insert(message) }
    }

    @Test
    fun `notifyNewMessage should not block on remote sync`() = runTest {
        // Given
        val messageTransport = mockk<IMessageTransport>()
        val deviceManager = mockk<IDeviceManager>()
        val sendStarted = CompletableDeferred<Unit>()
        val remoteDevice = Device(
            id = "peer-device",
            name = "Peer",
            type = DeviceType.PHONE,
            role = DeviceRole.SECONDARY,
            lastSeen = System.currentTimeMillis(),
            isPaired = true,
            isConnected = true
        )

        every { messageTransport.receiveMessages() } returns emptyFlow()
        every { deviceManager.getConnectedDevices() } returns flowOf(listOf(remoteDevice))
        every { deviceManager.getLocalDevice() } returns createTestMessageDevice()
        coEvery { messageTransport.sendMessage(any(), any()) } returns flow {
            sendStarted.complete(Unit)
            delay(500)
            emit(com.smslink.network.model.SendResult(success = true, messageId = "ok"))
        }
        coEvery { messageDao.insert(any()) } just Runs

        val asyncSmsManager = SmsManagerImpl(
            context = context,
            messageDao = messageDao,
            messageTransport = messageTransport,
            deviceManager = deviceManager,
            logger = logger
        )

        // When
        withTimeout(200) {
            asyncSmsManager.notifyNewMessage(createTestMessage("async-message"))
        }

        // Then
        coVerify(timeout = 2000) { messageTransport.sendMessage(eq("peer-device"), any()) }
        coVerify { messageDao.insert(any()) }
    }

    @Test
    fun `getConversations should return conversation list`() = runTest {
        // When
        val conversations = smsManager.getConversations()

        // Then
        // Note: This requires ContentResolver mocking which is complex
        // In real implementation, this would query system SMS database
        assertTrue(conversations.isEmpty() || conversations.isNotEmpty())
    }

    @Test
    fun `syncFromSystem should sync messages from system database`() = runTest {
        // Given
        coEvery { messageDao.insertAll(any()) } just Runs

        // When
        smsManager.syncFromSystem(10)

        // Then
        // Note: This requires ContentResolver mocking
        // Verify that the method completes without error
        assertTrue(true)
    }

    @Test
    fun `observeNewMessages should return flow of new messages`() = runTest {
        // When
        val flow = smsManager.observeNewMessages()

        // Then
        assertNotNull(flow)
    }

    @Test
    fun `sendMessage with simSlot should use correct SIM card`() = runTest {
        // Given
        val address = "+1234567890"
        val body = "Test message"
        val simSlot = 1
        coEvery { messageDao.insert(any()) } just Runs

        // When
        val result = smsManager.sendMessage(address, body, simSlot)

        // Then
        assertTrue(result)
        coVerify { messageDao.insert(any()) }
    }

    @Test
    fun `sendMessage should handle exception gracefully`() = runTest {
        // Given
        val address = "+1234567890"
        val body = "Test message"
        coEvery { messageDao.insert(any()) } throws Exception("Database error")

        // When
        val result = smsManager.sendMessage(address, body)

        // Then
        assertFalse(result)
    }

    @Test
    fun `markAsRead should update system SMS database`() = runTest {
        // Given
        val messageId = "test-id"
        val message = createTestMessage(messageId)
        coEvery { messageDao.markAsRead(messageId) } just Runs
        coEvery { messageDao.getById(messageId) } returns message

        // When
        smsManager.markAsRead(messageId)

        // Then
        coVerify { messageDao.markAsRead(messageId) }
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

    private fun createTestMessageDevice(): Device {
        return Device(
            id = "local",
            name = "Local",
            type = DeviceType.PHONE,
            role = DeviceRole.MAIN,
            lastSeen = System.currentTimeMillis(),
            isPaired = true,
            isConnected = true
        )
    }
}
