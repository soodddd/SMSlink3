package com.smslink.network.transport

import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageCodec
import com.smslink.network.protocol.MessageType
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolDiagnosticTest {

    @Test
    fun `verify protocol header size`() {
        println("Message.HEADER_SIZE = ${Message.HEADER_SIZE}")
        assertEquals("Header size should be 20 bytes", 20, Message.HEADER_SIZE)
    }

    @Test
    fun `verify encode produces correct header size`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0,
            messageId = 12345L,
            payload = "Hello".toByteArray()
        )

        val encoded = MessageCodec.encode(message)
        println("Encoded size: ${encoded.size}")
        println("Expected size: ${Message.HEADER_SIZE + message.payload.size}")

        assertEquals(Message.HEADER_SIZE + message.payload.size, encoded.size)
    }

    @Test
    fun `verify header layout`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0,
            messageId = 12345L,
            payload = "Hello".toByteArray()
        )

        val encoded = MessageCodec.encode(message)
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)

        // Read header fields
        val magic = buffer.short
        val version = buffer.get()
        val type = buffer.get()
        val flags = buffer.get()
        val padding1 = buffer.get()
        val padding2 = buffer.get()
        val padding3 = buffer.get()
        val length = buffer.int
        val messageId = buffer.long

        println("Magic: 0x${magic.toString(16)}")
        println("Version: 0x${version.toString(16)}")
        println("Type: 0x${type.toString(16)}")
        println("Flags: 0x${flags.toString(16)}")
        println("Padding: $padding1, $padding2, $padding3")
        println("Length: $length")
        println("MessageId: $messageId")

        assertEquals(Message.MAGIC.toLong(), magic.toLong())
        assertEquals(Message.VERSION.toLong(), version.toLong())
        assertEquals(MessageType.DEVICE_DISCOVERY.value.toLong(), type.toLong())
        assertEquals(0L, flags.toLong())
        assertEquals(0L, padding1.toLong())
        assertEquals(0L, padding2.toLong())
        assertEquals(0L, padding3.toLong())
        assertEquals(5L, length.toLong())
        assertEquals(12345L, messageId)
    }

    @Test
    fun `verify connection can read encoded message`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = 0,
            messageId = 12345L,
            payload = "Hello".toByteArray()
        )

        val encoded = MessageCodec.encode(message)

        // Simulate reading header
        val headerBuffer = ByteArray(Message.HEADER_SIZE)
        System.arraycopy(encoded, 0, headerBuffer, 0, Message.HEADER_SIZE)

        // Parse payload length from header (offset 8)
        val payloadLength = ByteBuffer.wrap(headerBuffer, 8, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        println("Parsed payload length: $payloadLength")
        println("Actual payload length: ${message.payload.size}")

        assertEquals(message.payload.size.toLong(), payloadLength.toLong())
    }
}
