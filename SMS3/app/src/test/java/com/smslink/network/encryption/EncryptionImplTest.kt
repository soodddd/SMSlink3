package com.smslink.network.encryption

import android.content.Context
import com.smslink.core.log.ILogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EncryptionImpl 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptionImplTest {

    private lateinit var encryption: EncryptionImpl
    private lateinit var context: Context
    private lateinit var logger: ILogger
    private lateinit var filesDir: File

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        filesDir = mockk(relaxed = true)

        every { context.filesDir } returns filesDir
        every { filesDir.absolutePath } returns "/tmp/test"

        encryption = EncryptionImpl(
            context = context,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `createSSLContext should return valid SSL context`() {
        // Given
        val deviceId = "test-device"

        // When
        val sslContext = encryption.createSSLContext(deviceId)

        // Then
        assertNotNull(sslContext)
        assertTrue(sslContext is SSLContext)
    }

    @Test
    fun `getDeviceCertificate should return null when certificate does not exist`() {
        // Given
        val deviceId = "non-existent-device"

        // When
        val certificate = encryption.getDeviceCertificate(deviceId)

        // Then
        assertNull(certificate)
    }

    @Test
    fun `verifyCertificate should return false for invalid certificate`() {
        // Given
        val certificate = mockk<X509Certificate>()
        every { certificate.checkValidity() } throws Exception("Invalid certificate")

        // When
        val result = encryption.verifyCertificate(certificate)

        // Then
        assertFalse(result)
    }
}
