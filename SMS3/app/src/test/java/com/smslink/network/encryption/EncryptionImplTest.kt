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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
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
    }

    @Test
    fun `generated certificate should round trip through the bounded codec`() {
        val certificate = encryption.generateSelfSignedCertificate()

        assertTrue(encryption.verifyCertificate(certificate))
        val encoded = CertificateCodec.encode(certificate)
        val decoded = CertificateCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(certificate.encoded.contentEquals(decoded.encoded))
    }

    @Test
    fun `TLS socket should require the peer certificate pinned during pairing`() = runTest {
        val serverEncryption = encryptionAt("/tmp/smslink-tls-server-${UUID.randomUUID()}")
        val clientEncryption = encryptionAt("/tmp/smslink-tls-client-${UUID.randomUUID()}")
        val serverCertificate = serverEncryption.generateSelfSignedCertificate()
        clientEncryption.saveDeviceCertificate("server-device", serverCertificate)

        val serverSocket = (serverEncryption.createSSLContext("local-listener")
            .serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
            needClientAuth = false
        }
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverHandshakeComplete = CountDownLatch(1)
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                (serverSocket.accept() as SSLSocket).use { it.startHandshake() }
            } catch (error: Throwable) {
                serverFailure.set(error)
            } finally {
                serverHandshakeComplete.countDown()
                serverSocket.close()
            }
        }

        try {
            (clientEncryption.createSSLContext("server-device")
                .socketFactory.createSocket("127.0.0.1", serverSocket.localPort) as SSLSocket).use {
                it.startHandshake()
                assertTrue(serverHandshakeComplete.await(5, TimeUnit.SECONDS))
            }
            assertNull(serverFailure.get())
        } finally {
            serverSocket.close()
            serverThread.join(5_000)
        }
    }

    private fun encryptionAt(path: String): EncryptionImpl {
        val testContext = mockk<Context>(relaxed = true)
        every { testContext.filesDir } returns File(path)
        return EncryptionImpl(testContext, logger)
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
