package com.smslink.network.protocol

import org.junit.Assert.*
import org.junit.Test

class ErrorCodeTest {

    @Test
    fun `fromValue returns correct error code`() {
        assertEquals(ErrorCode.UNKNOWN_ERROR, ErrorCode.fromValue(0))
        assertEquals(ErrorCode.INVALID_MESSAGE, ErrorCode.fromValue(1))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, ErrorCode.fromValue(2))
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, ErrorCode.fromValue(3))
        assertEquals(ErrorCode.DEVICE_NOT_PAIRED, ErrorCode.fromValue(4))
        assertEquals(ErrorCode.PERMISSION_DENIED, ErrorCode.fromValue(5))
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.fromValue(6))
        assertEquals(ErrorCode.TRANSFER_FAILED, ErrorCode.fromValue(7))
    }

    @Test
    fun `fromValue returns null for unknown code`() {
        assertNull(ErrorCode.fromValue(999))
        assertNull(ErrorCode.fromValue(-1))
    }

    @Test
    fun `all error codes have unique values`() {
        val values = ErrorCode.entries.map { it.value }.toSet()
        assertEquals(ErrorCode.entries.size, values.size)
    }
}
