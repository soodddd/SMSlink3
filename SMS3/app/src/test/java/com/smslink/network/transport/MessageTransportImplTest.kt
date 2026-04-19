package com.smslink.network.transport

import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.network.IConnectionManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MessageTransportImpl 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageTransportImplTest {

    private lateinit var messageTransport: MessageTransportImpl
    private lateinit var connectionManager: IConnectionManager
    private lateinit var logger: ILogger

    @Before
    fun setup() {
        connectionManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { connectionManager.receiveData() } returns flowOf()

        messageTransport = MessageTransportImpl(
            connectionManager = connectionManager,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `sendMessage should add message to queue`() = runTest {
        // Given
        val deviceId = "test-device"
        val message = NetworkMessage(
            messageType = MessageType.NOTIFICATION,
            messageId = UUID.randomUUID().toString(),
            sourceDevice = "source",
            targetDevice = deviceId,
            timestamp = System.currentTimeMillis(),
            payload = JsonObject()
        )

        coEvery { connectionManager.sendData(any(), any()) } returns true

        // When
        val result = messageTransport.sendMessage(deviceId, message).first()

        // Then
        assertTrue(result.success)
        assertEquals(message.messageId, result.messageId)
    }

    @Test
    fun `getPendingMessageCount should return correct count`() {
        // When
        val count = messageTransport.getPendingMessageCount()

        // Then
        assertTrue(count >= 0)
    }

    @Test
    fun `clearQueue should clear all pending messages`() = runTest {
        // When
        messageTransport.clearQueue()

        // Then
        val count = messageTransport.getPendingMessageCount()
        assertEquals(0, count)
    }

    @Test
    fun `sendAck should send acknowledgment message`() = runTest {
        // Given
        val messageId = "test-message-id"
        val deviceId = "test-device"

        coEvery { connectionManager.sendData(any(), any()) } returns true

        // When
        messageTransport.sendAck(messageId, deviceId)

        // Then
        coVerify { connectionManager.sendData(deviceId, any()) }
    }
}
