package com.smslink.network.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class FrameCodecTest {

    @Test
    fun `multiple frames round trip without coalescing`() {
        val output = ByteArrayOutputStream()
        FrameCodec.writeFrame(output, byteArrayOf(1, 2, 3))
        FrameCodec.writeFrame(output, "second".toByteArray())

        val input = ByteArrayInputStream(output.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), FrameCodec.readFrame(input))
        assertArrayEquals("second".toByteArray(), FrameCodec.readFrame(input))
    }

    @Test
    fun `invalid and empty frames are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(ByteArrayOutputStream(), ByteArray(0))
        }

        val invalid = ByteArrayOutputStream()
        DataOutputStream(invalid).use { it.writeInt(0) }
        assertThrows(IOException::class.java) {
            FrameCodec.readFrame(ByteArrayInputStream(invalid.toByteArray()))
        }
    }
}
