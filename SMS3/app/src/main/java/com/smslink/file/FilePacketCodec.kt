package com.smslink.file

import com.google.gson.Gson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Versioned, length-delimited file packet codec.
 *
 * The connection layer already frames each packet. This inner envelope still
 * carries its own magic/version and strict lengths so a malformed or stale
 * file packet cannot be interpreted as another operation.
 */
internal object FilePacketCodec {
    const val PROTOCOL_VERSION = 2
    const val MAX_PACKET_SIZE = 1 * 1024 * 1024
    const val MAX_FILE_SIZE = 4L * 1024 * 1024 * 1024

    private const val MAGIC = 0x534C4632 // ASCII "SLF2"
    private const val HASH_LENGTH = 64
    private const val BASE_HEADER_SIZE = 4 + 1 + 1 + 16 + 4 + 8 + 1 + 4
    private val gson = Gson()

    enum class PacketType(val wireValue: Int) {
        REQUEST(1),
        ACCEPT(2),
        REJECT(3),
        CHUNK(4),
        COMPLETE(5),
        CANCEL(6),
        ERROR(7),
        ACK(8)
    }

    data class Packet(
        val type: PacketType,
        val transferId: String,
        val sequence: Int = -1,
        val totalSize: Long = -1L,
        val hash: String? = null,
        val payload: ByteArray = ByteArray(0)
    )

    data class RequestMetadata(
        val fileName: String,
        val mimeType: String
    )

    fun request(
        transferId: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        fileHash: String
    ): ByteArray = encode(
        Packet(
            type = PacketType.REQUEST,
            transferId = transferId,
            totalSize = fileSize,
            hash = fileHash,
            payload = gson.toJson(RequestMetadata(fileName, mimeType))
                .toByteArray(StandardCharsets.UTF_8)
        )
    )

    fun accept(transferId: String, nextSequence: Int = 0): ByteArray =
        encode(
            Packet(
                type = PacketType.ACCEPT,
                transferId = transferId,
                sequence = nextSequence
            )
        )

    fun reject(transferId: String, reason: String): ByteArray =
        control(PacketType.REJECT, transferId, reason)

    fun cancel(transferId: String): ByteArray = control(PacketType.CANCEL, transferId)

    fun error(transferId: String, reason: String): ByteArray =
        control(PacketType.ERROR, transferId, reason)

    fun chunk(transferId: String, sequence: Int, totalSize: Long, payload: ByteArray): ByteArray =
        encode(
            Packet(
                type = PacketType.CHUNK,
                transferId = transferId,
                sequence = sequence,
                totalSize = totalSize,
                payload = payload.copyOf()
            )
        )

    fun complete(transferId: String, totalSize: Long, fileHash: String): ByteArray =
        encode(
            Packet(
                type = PacketType.COMPLETE,
                transferId = transferId,
                totalSize = totalSize,
                hash = fileHash
            )
        )

    fun ack(transferId: String, sequence: Int? = null): ByteArray =
        encode(
            Packet(
                type = PacketType.ACK,
                transferId = transferId,
                sequence = sequence ?: -1
            )
        )

    fun encode(packet: Packet): ByteArray {
        val transferUuid = runCatching { UUID.fromString(packet.transferId) }
            .getOrElse { throw IllegalArgumentException("Invalid transfer id") }
        require(packet.totalSize in -1L..MAX_FILE_SIZE) { "Invalid file size" }
        require(packet.sequence >= -1) { "Invalid packet sequence" }
        require(packet.payload.size <= MAX_PACKET_SIZE - BASE_HEADER_SIZE) { "Payload too large" }

        val hashBytes = packet.hash?.lowercase()?.toByteArray(StandardCharsets.US_ASCII)
            ?: ByteArray(0)
        require(hashBytes.size == 0 || hashBytes.size == HASH_LENGTH) { "Invalid hash length" }
        require(hashBytes.all { it in '0'.code.toByte()..'9'.code.toByte() || it in 'a'.code.toByte()..'f'.code.toByte() }) {
            "Invalid hash"
        }

        val size = BASE_HEADER_SIZE + hashBytes.size + packet.payload.size
        require(size <= MAX_PACKET_SIZE) { "Packet too large" }
        return ByteBuffer.allocate(size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(PROTOCOL_VERSION.toByte())
            .put(packet.type.wireValue.toByte())
            .putLong(transferUuid.mostSignificantBits)
            .putLong(transferUuid.leastSignificantBits)
            .putInt(packet.sequence)
            .putLong(packet.totalSize)
            .put(hashBytes.size.toByte())
            .putInt(packet.payload.size)
            .put(hashBytes)
            .put(packet.payload)
            .array()
    }

    fun decode(data: ByteArray): Packet? {
        if (data.size < BASE_HEADER_SIZE || data.size > MAX_PACKET_SIZE) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC) return null
            if ((buffer.get().toInt() and 0xFF) != PROTOCOL_VERSION) return null
            // Read the wire type once. Reading from the buffer inside the
            // predicate would consume one byte for every enum entry and make
            // valid CHUNK/COMPLETE packets appear malformed.
            val wireType = buffer.get().toInt() and 0xFF
            val type = PacketType.entries.firstOrNull { it.wireValue == wireType }
                ?: return null
            val transferId = UUID(buffer.long, buffer.long).toString()
            val sequence = buffer.int
            val totalSize = buffer.long
            if (totalSize !in -1L..MAX_FILE_SIZE || sequence < -1) return null
            val hashLength = buffer.get().toInt() and 0xFF
            val payloadLength = buffer.int
            if (hashLength != 0 && hashLength != HASH_LENGTH) return null
            if (payloadLength < 0 || hashLength + payloadLength != buffer.remaining()) return null

            val hash = if (hashLength == 0) {
                null
            } else {
                String(ByteArray(hashLength).also(buffer::get), StandardCharsets.US_ASCII)
                    .lowercase()
                    .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: return null
            }
            val payload = ByteArray(payloadLength).also(buffer::get)
            Packet(type, transferId, sequence, totalSize, hash, payload)
        }.getOrNull()
    }

    fun decodeRequestMetadata(packet: Packet): RequestMetadata? {
        if (packet.type != PacketType.REQUEST) return null
        return runCatching {
            gson.fromJson(
                String(packet.payload, StandardCharsets.UTF_8),
                RequestMetadata::class.java
            )
        }.getOrNull()?.takeIf { metadata ->
            metadata.fileName.isNotBlank() && metadata.fileName.length <= 255 &&
                metadata.mimeType.length <= 128
        }
    }

    fun payloadText(packet: Packet): String =
        String(packet.payload, StandardCharsets.UTF_8).take(1024)

    private fun control(type: PacketType, transferId: String, text: String? = null): ByteArray =
        encode(
            Packet(
                type = type,
                transferId = transferId,
                payload = text?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            )
        )
}
