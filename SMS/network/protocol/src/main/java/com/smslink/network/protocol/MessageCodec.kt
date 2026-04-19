package com.smslink.network.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MessageCodec {

    fun encode(message: Message): ByteArray {
        val totalSize = Message.HEADER_SIZE + message.payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Magic (2 bytes)
        buffer.putShort(Message.MAGIC)

        // Version (1 byte)
        buffer.put(Message.VERSION)

        // Type (1 byte)
        buffer.put(message.type.value)

        // Flags (1 byte)
        val normalizedFlags = message.flags.toInt() and 0xFF
        require((normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK) == 0) {
            "Unsupported flags: 0x${normalizedFlags.toString(16)}"
        }
        buffer.put(normalizedFlags.toByte())

        // Padding (3 bytes)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)

        // Payload Length (4 bytes)
        require(message.payload.size <= Message.MAX_PAYLOAD_SIZE) {
            "Payload too large: ${message.payload.size} bytes"
        }
        buffer.putInt(message.payload.size)

        // Message ID (8 bytes)
        buffer.putLong(message.messageId)

        // Payload
        buffer.put(message.payload)

        return buffer.array()
    }

    fun decode(data: ByteArray): Result<Message> {
        if (data.size < Message.HEADER_SIZE) {
            return Result.failure(IllegalArgumentException("Data too short: ${data.size} bytes"))
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Magic
        val magic = buffer.short
        if (magic != Message.MAGIC) {
            return Result.failure(IllegalArgumentException("Invalid magic: 0x${magic.toString(16)}"))
        }

        // Version
        val version = buffer.get()
        if (version != Message.VERSION) {
            return Result.failure(IllegalArgumentException("Unsupported version: $version"))
        }

        // Type
        val typeByte = buffer.get()
        val type = MessageType.fromValue(typeByte)
            ?: return Result.failure(IllegalArgumentException("Unknown message type: 0x${typeByte.toString(16)}"))

        // Flags
        val flags = buffer.get()
        val normalizedFlags = flags.toInt() and 0xFF
        val unsupportedFlags = normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK
        if (unsupportedFlags != 0) {
            return Result.failure(
                IllegalArgumentException("Unsupported flags: 0x${unsupportedFlags.toString(16)}")
            )
        }

        // Padding (3 bytes)
        buffer.get() // skip
        buffer.get() // skip
        buffer.get() // skip

        // Payload Length (4 bytes)
        val payloadLength = buffer.int
        if (payloadLength < 0) {
            return Result.failure(IllegalArgumentException("Negative payload length: $payloadLength"))
        }
        if (payloadLength > Message.MAX_PAYLOAD_SIZE) {
            return Result.failure(
                IllegalArgumentException("Payload too large: $payloadLength bytes")
            )
        }

        // Message ID
        val messageId = buffer.long

        // Validate payload length
        val remainingBytes = data.size - Message.HEADER_SIZE
        if (payloadLength != remainingBytes) {
            return Result.failure(
                IllegalArgumentException(
                    "Payload length mismatch: expected $payloadLength, available $remainingBytes"
                )
            )
        }

        // Payload
        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        return Result.success(Message(type, flags, messageId, payload))
    }
}
