package com.smslink.network.protocol

data class Message(
    val type: MessageType,
    val flags: Byte = 0,
    val messageId: Long,
    val payload: ByteArray
) {
    val requiresAck: Boolean
        get() = (flags.toInt() and FLAG_REQUIRES_ACK) != 0

    val isEncrypted: Boolean
        get() = (flags.toInt() and FLAG_ENCRYPTED) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Message
        if (type != other.type) return false
        if (flags != other.flags) return false
        if (messageId != other.messageId) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + messageId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val FLAG_REQUIRES_ACK = 0x01
        const val FLAG_ENCRYPTED = 0x02
        const val SUPPORTED_FLAGS_MASK = 0x03
        const val UNSUPPORTED_FLAGS_MASK = 0xFC
        const val MAX_PAYLOAD_SIZE = 1024 * 1024
        const val HEADER_SIZE = 20 // Magic(2) + Version(1) + Type(1) + Flags(1) + Padding(3) + Length(4) + MessageId(8)
        const val MAGIC = 0x534C.toShort() // 'SL'
        const val VERSION: Byte = 0x01
    }
}
