package com.smslink.device.ble

import java.io.ByteArrayOutputStream

/** Small application framing for BLE characteristic writes/notifications. */
object BleChunkCodec {
    private const val MAGIC: Byte = 0x53
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 7
    // Android's default ATT MTU is 23 bytes, which leaves 20 bytes for a
    // characteristic value. The framing header is part of that value, so a
    // 13-byte payload is the largest size that works without MTU negotiation.
    // Keeping the default at the legacy-safe size is slower but interoperable
    // with devices that do not support (or reject) requestMtu().
    const val DEFAULT_PAYLOAD_SIZE = 13
    const val MAX_PAYLOAD_SIZE = 240
    const val MAX_ASSEMBLED_SIZE = 64 * 1024

    fun encode(data: ByteArray, payloadSize: Int = DEFAULT_PAYLOAD_SIZE): List<ByteArray> {
        require(data.isNotEmpty()) { "BLE message must not be empty" }
        require(data.size <= MAX_ASSEMBLED_SIZE) { "BLE message is too large" }
        require(payloadSize in 1..MAX_PAYLOAD_SIZE) { "Invalid BLE payload size" }
        val total = (data.size + payloadSize - 1) / payloadSize
        require(total <= 0xFFFF) { "BLE message has too many chunks" }
        return (0 until total).map { sequence ->
            val start = sequence * payloadSize
            val end = minOf(start + payloadSize, data.size)
            val payload = data.copyOfRange(start, end)
            ByteArray(HEADER_SIZE + payload.size).also { chunk ->
                chunk[0] = MAGIC
                chunk[1] = VERSION
                chunk[2] = (sequence ushr 8).toByte()
                chunk[3] = sequence.toByte()
                chunk[4] = (total ushr 8).toByte()
                chunk[5] = total.toByte()
                chunk[6] = payload.size.toByte()
                payload.copyInto(chunk, HEADER_SIZE)
            }
        }
    }

    data class DecodedChunk(val sequence: Int, val total: Int, val payload: ByteArray)

    fun decode(chunk: ByteArray): DecodedChunk? {
        if (chunk.size < HEADER_SIZE || chunk[0] != MAGIC || chunk[1] != VERSION) return null
        val sequence = ((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)
        val total = ((chunk[4].toInt() and 0xFF) shl 8) or (chunk[5].toInt() and 0xFF)
        val payloadLength = chunk[6].toInt() and 0xFF
        if (total <= 0 || sequence >= total || payloadLength !in 1..MAX_PAYLOAD_SIZE ||
            payloadLength != chunk.size - HEADER_SIZE
        ) return null
        return DecodedChunk(sequence, total, chunk.copyOfRange(HEADER_SIZE, chunk.size))
    }

    fun assemble(chunks: Collection<DecodedChunk>): ByteArray? {
        if (chunks.isEmpty()) return null
        val total = chunks.first().total
        if (chunks.size != total || chunks.any { it.total != total } ||
            chunks.map { it.sequence }.toSet().size != total
        ) return null
        if (chunks.sumOf { it.payload.size } > MAX_ASSEMBLED_SIZE) return null
        val output = ByteArrayOutputStream()
        chunks.sortedBy { it.sequence }.forEach { output.write(it.payload) }
        return output.toByteArray()
    }
}
