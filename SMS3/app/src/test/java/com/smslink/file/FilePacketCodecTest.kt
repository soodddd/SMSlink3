package com.smslink.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class FilePacketCodecTest {

    @Test
    fun `chunk round trip preserves binary payload and metadata`() {
        val transferId = UUID.randomUUID().toString()
        val payload = ByteArray(4096) { (it * 31).toByte() }

        val decoded = FilePacketCodec.decode(
            FilePacketCodec.chunk(transferId, sequence = 7, totalSize = 8192L, payload = payload)
        )

        requireNotNull(decoded)
        assertEquals(FilePacketCodec.PacketType.CHUNK, decoded.type)
        assertEquals(transferId, decoded.transferId)
        assertEquals(7, decoded.sequence)
        assertEquals(8192L, decoded.totalSize)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `request round trip decodes bounded metadata`() {
        val transferId = UUID.randomUUID().toString()
        val hash = "a".repeat(64)
        val packet = FilePacketCodec.request(
            transferId = transferId,
            fileName = "report.pdf",
            mimeType = "application/pdf",
            fileSize = 123L,
            fileHash = hash
        )

        val decoded = requireNotNull(FilePacketCodec.decode(packet))
        assertEquals(FilePacketCodec.PacketType.REQUEST, decoded.type)
        assertEquals(hash, decoded.hash)
        assertEquals(
            FilePacketCodec.RequestMetadata("report.pdf", "application/pdf"),
            FilePacketCodec.decodeRequestMetadata(decoded)
        )
    }

    @Test
    fun `decoder rejects wrong magic, version, and inconsistent lengths`() {
        val transferId = UUID.randomUUID().toString()
        val original = FilePacketCodec.chunk(transferId, 0, 1L, byteArrayOf(1))

        val wrongMagic = original.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(0, 0)
        }
        assertNull(FilePacketCodec.decode(wrongMagic))

        val wrongVersion = original.copyOf().also { it[4] = 1 }
        assertNull(FilePacketCodec.decode(wrongVersion))

        val wrongPayloadLength = original.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(35, 99)
        }
        assertNull(FilePacketCodec.decode(wrongPayloadLength))
    }

    @Test
    fun `encoder rejects invalid identifiers and hashes`() {
        assertThrows(IllegalArgumentException::class.java) {
            FilePacketCodec.chunk("not-a-uuid", 0, 0L, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilePacketCodec.complete(UUID.randomUUID().toString(), 0L, "bad")
        }
    }
}
