package com.smslink.sms

import com.google.gson.JsonParser
import com.smslink.core.model.MessageType
import com.smslink.sms.model.SmsSendResult
import com.smslink.sms.model.SmsSyncPayload
import com.smslink.sms.model.SyncAction
import org.junit.Assert.*
import org.junit.Test

/**
 * 短信同步模型测试
 */
class SmsSyncModelTest {

    @Test
    fun `test SmsSyncPayload serialization`() {
        val payload = SmsSyncPayload(
            messageId = "msg123",
            threadId = "thread456",
            address = "+1234567890",
            body = "Test message",
            timestamp = 1234567890L,
            type = MessageType.SENT,
            read = true,
            simSlot = 0,
            action = SyncAction.NEW_MESSAGE
        )

        val json = payload.toJson()
        assertNotNull(json)
        assertTrue(json.contains("msg123"))
        assertTrue(json.contains("Test message"))

        val deserialized = SmsSyncPayload.fromJson(json)
        assertEquals(payload.messageId, deserialized.messageId)
        assertEquals(payload.address, deserialized.address)
        assertEquals(payload.body, deserialized.body)
        assertEquals(payload.type, deserialized.type)
        assertEquals(payload.action, deserialized.action)
    }

    @Test
    fun `test SmsSyncPayload with null simSlot`() {
        val payload = SmsSyncPayload(
            messageId = "msg123",
            threadId = "thread456",
            address = "+1234567890",
            body = "Test message",
            timestamp = 1234567890L,
            type = MessageType.INBOX,
            read = false,
            simSlot = null,
            action = SyncAction.NEW_MESSAGE
        )

        val json = payload.toJson()
        val deserialized = SmsSyncPayload.fromJson(json)
        assertNull(deserialized.simSlot)
    }

    @Test
    fun `test SmsSendResult serialization`() {
        val result = SmsSendResult(
            requestId = "req123",
            success = true,
            messageId = "msg456",
            error = null,
            timestamp = 1234567890L
        )

        val json = result.toJson()
        assertNotNull(json)
        assertTrue(json.contains("req123"))
        assertTrue(json.contains("msg456"))

        val deserialized = SmsSendResult.fromJson(json)
        assertEquals(result.requestId, deserialized.requestId)
        assertEquals(result.success, deserialized.success)
        assertEquals(result.messageId, deserialized.messageId)
    }

    @Test
    fun `test SmsSendResult with error`() {
        val result = SmsSendResult(
            requestId = "req123",
            success = false,
            messageId = null,
            error = "Send failed"
        )

        val json = result.toJson()
        val deserialized = SmsSendResult.fromJson(json)
        assertFalse(deserialized.success)
        assertNull(deserialized.messageId)
        assertEquals("Send failed", deserialized.error)
    }

    @Test
    fun `test SyncAction types`() {
        val actions = listOf(
            SyncAction.NEW_MESSAGE,
            SyncAction.MARK_READ,
            SyncAction.DELETE_MESSAGE,
            SyncAction.SEND_REQUEST,
            SyncAction.SEND_RESULT
        )

        assertEquals(5, actions.size)
        assertTrue(actions.contains(SyncAction.NEW_MESSAGE))
        assertTrue(actions.contains(SyncAction.MARK_READ))
    }

    @Test
    fun `test SmsSyncPayload for mark read action`() {
        val payload = SmsSyncPayload(
            messageId = "msg123",
            threadId = "thread456",
            address = "+1234567890",
            body = "Test message",
            timestamp = 1234567890L,
            type = MessageType.INBOX,
            read = true,
            action = SyncAction.MARK_READ
        )

        assertEquals(SyncAction.MARK_READ, payload.action)
        assertTrue(payload.read)
    }

    @Test
    fun `test SmsSyncPayload for delete action`() {
        val payload = SmsSyncPayload(
            messageId = "msg123",
            threadId = "thread456",
            address = "+1234567890",
            body = "",
            timestamp = 1234567890L,
            type = MessageType.INBOX,
            read = false,
            action = SyncAction.DELETE_MESSAGE
        )

        assertEquals(SyncAction.DELETE_MESSAGE, payload.action)
    }

    @Test
    fun `test SmsSyncPayload JSON compatibility`() {
        val payload = SmsSyncPayload(
            messageId = "msg123",
            threadId = "thread456",
            address = "+1234567890",
            body = "Test message with special chars: 你好 🎉",
            timestamp = 1234567890L,
            type = MessageType.SENT,
            read = true,
            simSlot = 1,
            action = SyncAction.NEW_MESSAGE
        )

        val json = payload.toJson()
        val jsonObject = JsonParser.parseString(json).asJsonObject

        assertTrue(jsonObject.has("messageId"))
        assertTrue(jsonObject.has("body"))
        assertTrue(jsonObject.has("action"))

        val deserialized = SmsSyncPayload.fromJson(json)
        assertEquals(payload.body, deserialized.body)
    }
}
