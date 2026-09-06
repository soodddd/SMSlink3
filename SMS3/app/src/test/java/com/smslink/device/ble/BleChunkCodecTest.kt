package com.smslink.device.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleChunkCodecTest {

    @Test
    fun `default chunks fit the legacy 20 byte ATT value`() {
        val chunks = BleChunkCodec.encode(ByteArray(64) { it.toByte() })

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.size <= 20 })
        assertEquals(ByteArray(64) { it.toByte() }.size, chunks.sumOf { it.size - 7 })
    }

    @Test
    fun `chunks assemble independent of arrival order`() {
        val source = ByteArray(97) { (it * 3).toByte() }
        val decoded = BleChunkCodec.encode(source)
            .reversed()
            .mapNotNull(BleChunkCodec::decode)

        assertArrayEquals(source, BleChunkCodec.assemble(decoded))
    }
}
