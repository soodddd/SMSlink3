package com.smslink.network.protocol

import org.junit.Assert.*
import org.junit.Test

class MessageTypeTest {

    @Test
    fun `fromValue returns correct type`() {
        assertEquals(MessageType.DEVICE_DISCOVERY, MessageType.fromValue(0x00))
        assertEquals(MessageType.DEVICE_PAIR_REQUEST, MessageType.fromValue(0x01))
        assertEquals(MessageType.NOTIFICATION_SYNC, MessageType.fromValue(0x10))
        assertEquals(MessageType.CALL_INCOMING, MessageType.fromValue(0x20))
        assertEquals(MessageType.FILE_TRANSFER_REQUEST, MessageType.fromValue(0x30))
        assertEquals(MessageType.ACK, MessageType.fromValue(0xF0.toByte()))
        assertEquals(MessageType.ERROR, MessageType.fromValue(0xF1.toByte()))
    }

    @Test
    fun `fromValue returns null for unknown type`() {
        assertNull(MessageType.fromValue(0x99.toByte()))
        assertNull(MessageType.fromValue(0xFF.toByte()))
    }

    @Test
    fun `all message types have unique values`() {
        val values = MessageType.entries.map { it.value }.toSet()
        assertEquals(MessageType.entries.size, values.size)
    }
}
