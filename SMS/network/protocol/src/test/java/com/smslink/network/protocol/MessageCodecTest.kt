package com.smslink.network.protocol

import org.junit.Assert.*
import org.junit.Test

class MessageCodecTest {

    @Test
    fun `encode and decode message successfully`() {
        val originalMessage = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = Message.FLAG_REQUIRES_ACK.toByte(),
            messageId = 12345678L,
            payload = "test payload".toByteArray()
        )

        val encoded = MessageCodec.encode(originalMessage)
        val decoded = MessageCodec.decode(encoded)

        assertTrue(decoded.isSuccess)
        val decodedMessage = decoded.getOrThrow()

        assertEquals(originalMessage.type, decodedMessage.type)
        assertEquals(originalMessage.flags, decodedMessage.flags)
        assertEquals(originalMessage.messageId, decodedMessage.messageId)
        assertArrayEquals(originalMessage.payload, decodedMessage.payload)
    }

    @Test
    fun `decode validates magic number`() {
        val invalidData = ByteArray(20) { 0 }
        val result = MessageCodec.decode(invalidData)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid magic") == true)
    }

    @Test
    fun `decode validates version`() {
        val data = ByteArray(20)
        data[0] = 0x53 // 'S'
        data[1] = 0x4C // 'L'
        data[2] = 0x99.toByte() // invalid version

        val result = MessageCodec.decode(data)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported version") == true)
    }

    @Test
    fun `decode rejects data shorter than header`() {
        val shortData = ByteArray(10)
        val result = MessageCodec.decode(shortData)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Data too short") == true)
    }

    @Test
    fun `encode handles empty payload`() {
        val message = Message(
            type = MessageType.DEVICE_HEARTBEAT,
            flags = 0,
            messageId = 999L,
            payload = ByteArray(0)
        )

        val encoded = MessageCodec.encode(message)
        val decoded = MessageCodec.decode(encoded)

        assertTrue(decoded.isSuccess)
        assertEquals(0, decoded.getOrThrow().payload.size)
    }

    @Test
    fun `flags are preserved correctly`() {
        val flags = (Message.FLAG_REQUIRES_ACK or Message.FLAG_ENCRYPTED).toByte()
        val message = Message(
            type = MessageType.NOTIFICATION_SYNC,
            flags = flags,
            messageId = 555L,
            payload = ByteArray(10)
        )

        val encoded = MessageCodec.encode(message)
        val decoded = MessageCodec.decode(encoded).getOrThrow()

        assertTrue(decoded.requiresAck)
        assertTrue(decoded.isEncrypted)
    }

    @Test
    fun `all message types can be encoded and decoded`() {
        MessageType.entries.forEach { type ->
            val message = Message(
                type = type,
                flags = 0,
                messageId = 1L,
                payload = "test".toByteArray()
            )

            val encoded = MessageCodec.encode(message)
            val decoded = MessageCodec.decode(encoded)

            assertTrue("Failed for type $type", decoded.isSuccess)
            assertEquals(type, decoded.getOrThrow().type)
        }
    }

    @Test
    fun `encode and decode payload larger than 255 bytes successfully`() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val message = Message(
            type = MessageType.FILE_TRANSFER_DATA,
            flags = 0,
            messageId = 42L,
            payload = payload
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(message))

        assertTrue(decoded.isSuccess)
        assertArrayEquals(payload, decoded.getOrThrow().payload)
    }

    @Test
    fun `decode rejects trailing bytes`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0,
            messageId = 1L,
            payload = "abc".toByteArray()
        )
        val encoded = MessageCodec.encode(message) + byteArrayOf(0x01, 0x02)

        val result = MessageCodec.decode(encoded)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Payload length mismatch") == true)
    }

    @Test
    fun `decode rejects unsupported flags`() {
        // Manually construct a message with unsupported flags to bypass encode validation
        val buffer = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(Message.MAGIC)
        buffer.put(Message.VERSION)
        buffer.put(MessageType.DEVICE_DISCOVERY.value)
        buffer.put(0x04) // unsupported flag
        buffer.put(0) // padding
        buffer.put(0) // padding
        buffer.put(0) // padding
        buffer.putInt(0) // payload length
        buffer.putLong(1L) // message id

        val result = MessageCodec.decode(buffer.array())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported flags") == true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects unsupported flags`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0x04,
            messageId = 1L,
            payload = byteArrayOf()
        )

        MessageCodec.encode(message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects oversized payload`() {
        val message = Message(
            type = MessageType.FILE_TRANSFER_DATA,
            flags = 0,
            messageId = 1L,
            payload = ByteArray(Message.MAX_PAYLOAD_SIZE + 1)
        )

        MessageCodec.encode(message)
    }

    @Test
    fun `decode rejects oversized payload length`() {
        val buffer = java.nio.ByteBuffer.allocate(Message.HEADER_SIZE).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(Message.MAGIC)
        buffer.put(Message.VERSION)
        buffer.put(MessageType.DEVICE_DISCOVERY.value)
        buffer.put(0)
        buffer.put(0) // padding
        buffer.put(0) // padding
        buffer.put(0) // padding
        buffer.putInt(Message.MAX_PAYLOAD_SIZE + 1)
        buffer.putLong(1L)

        val result = MessageCodec.decode(buffer.array())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Payload too large") == true)
    }
}
