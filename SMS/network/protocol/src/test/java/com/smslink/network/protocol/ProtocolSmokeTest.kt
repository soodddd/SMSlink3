package com.smslink.network.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolSmokeTest {

    @Test
    fun `protocol smoke test passes`() {
        val message = Message(
            type = MessageType.DEVICE_DISCOVERY,
            flags = Message.FLAG_REQUIRES_ACK.toByte(),
            messageId = 12345678L,
            payload = "Hello SMS-Link".toByteArray()
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(message)).getOrThrow()

        assertEquals(message, decoded)
    }
}
