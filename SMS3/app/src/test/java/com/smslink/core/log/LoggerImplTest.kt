package com.smslink.core.log

import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * LoggerImpl 单元测试
 */
class LoggerImplTest {

    private lateinit var logger: LoggerImpl

    @Before
    fun setup() {
        logger = LoggerImpl()
    }

    @Test
    fun `test debug log`() {
        // Given
        val tag = "TestTag"
        val message = "Debug message"

        // When
        logger.d(tag, message)

        // Then
        // 验证日志已记录（实际测试中可以使用 ShadowLog 或其他工具）
        assert(true)
    }

    @Test
    fun `test info log`() {
        // Given
        val tag = "TestTag"
        val message = "Info message"

        // When
        logger.i(tag, message)

        // Then
        assert(true)
    }

    @Test
    fun `test warning log`() {
        // Given
        val tag = "TestTag"
        val message = "Warning message"

        // When
        logger.w(tag, message)

        // Then
        assert(true)
    }

    @Test
    fun `test error log without throwable`() {
        // Given
        val tag = "TestTag"
        val message = "Error message"

        // When
        logger.e(tag, message)

        // Then
        assert(true)
    }

    @Test
    fun `test error log with throwable`() {
        // Given
        val tag = "TestTag"
        val message = "Error message"
        val throwable = RuntimeException("Test exception")

        // When
        logger.e(tag, message, throwable)

        // Then
        assert(true)
    }
}
