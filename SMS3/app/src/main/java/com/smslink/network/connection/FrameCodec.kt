package com.smslink.network.connection

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The only wire framing used by SMS-link.
 *
 * A frame is a big-endian 32-bit byte length followed by exactly that many
 * bytes.  Keeping framing in one place prevents the TCP and RFCOMM links from
 * slowly acquiring incompatible protocols.
 */
object FrameCodec {
    // Business messages are small and file chunks are bounded separately by
    // FilePacketCodec. Keep the common framing allocation cap low enough that
    // a single unauthenticated peer cannot request a 100 MiB heap allocation.
    const val MAX_FRAME_SIZE = 2 * 1024 * 1024

    @Throws(IOException::class)
    fun readFrame(input: InputStream): ByteArray {
        val length = try {
            DataInputStream(input).readInt()
        } catch (e: EOFException) {
            throw IOException("End of stream", e)
        }

        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid frame length: $length")
        }

        val frame = ByteArray(length)
        DataInputStream(input).readFully(frame)
        return frame
    }

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, data: ByteArray) {
        require(data.isNotEmpty()) { "Frame must not be empty" }
        require(data.size <= MAX_FRAME_SIZE) { "Frame is too large: ${data.size}" }
        val dataOutput = DataOutputStream(output)
        dataOutput.writeInt(data.size)
        dataOutput.write(data)
        dataOutput.flush()
    }
}
