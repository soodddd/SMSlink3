package com.smslink.network.encryption

import android.content.Context
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * 加密实现
 * 负责 TLS/SSL 加密通信和证书管理
 */
@Singleton
class EncryptionImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) : IEncryption {

    private val certificateDir: File by lazy {
        val basePath = runCatching { context.filesDir?.absolutePath }.getOrNull()
            ?: System.getProperty("java.io.tmpdir")
        File(basePath, "smslink/certificates").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val localKeyStoreFile: File by lazy {
        File(certificateDir, "local_tls.p12")
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(LOCAL_KEYSTORE_TYPE).apply {
            if (localKeyStoreFile.exists()) {
                FileInputStream(localKeyStoreFile).use { input ->
                    load(input, LOCAL_KEYSTORE_PASSWORD)
                }
            } else {
                load(null, LOCAL_KEYSTORE_PASSWORD)
            }
        }
    }

    override fun createSSLContext(deviceId: String): SSLContext {
        logger.d(TAG, "Creating SSL context for device: $deviceId")

        try {
            val sslContext = SSLContext.getInstance("TLS")

            ensureLocalCertificate()

            // 获取设备证书
            val deviceCert = getDeviceCertificate(deviceId)
            val keyManagers = createKeyManagers()

            if (deviceCert != null) {
                // 使用设备证书创建信任管理器
                val trustManager = createTrustManager(deviceCert)
                sslContext.init(keyManagers, arrayOf(trustManager), SecureRandom())
            } else {
                // 如果没有证书，使用宽松的信任管理器（仅用于开发）
                logger.w(TAG, "No certificate found for device: $deviceId, using permissive trust manager")
                val trustManager = createPermissiveTrustManager()
                sslContext.init(keyManagers, arrayOf(trustManager), SecureRandom())
            }

            return sslContext

        } catch (e: Exception) {
            logger.e(TAG, "Failed to create SSL context for device: $deviceId", e)
            throw e
        }
    }

    override fun getDeviceCertificate(deviceId: String): X509Certificate? {
        val certFile = File(certificateDir, "$deviceId.crt")

        if (!certFile.exists()) {
            logger.d(TAG, "Certificate not found for device: $deviceId")
            return null
        }

        return try {
            FileInputStream(certFile).use { fis ->
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(fis) as X509Certificate
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to load certificate for device: $deviceId", e)
            null
        }
    }

    override suspend fun saveDeviceCertificate(deviceId: String, certificate: X509Certificate) = withContext(Dispatchers.IO) {
        logger.i(TAG, "Saving certificate for device: $deviceId")

        val certFile = File(certificateDir, "$deviceId.crt")

        try {
            FileOutputStream(certFile).use { fos ->
                fos.write(certificate.encoded)
            }

            logger.i(TAG, "Certificate saved for device: $deviceId")

        } catch (e: Exception) {
            logger.e(TAG, "Failed to save certificate for device: $deviceId", e)
            throw e
        }
    }

    override suspend fun removeDeviceCertificate(deviceId: String) = withContext(Dispatchers.IO) {
        logger.i(TAG, "Removing certificate for device: $deviceId")

        val certFile = File(certificateDir, "$deviceId.crt")

        if (certFile.exists()) {
            certFile.delete()
            logger.i(TAG, "Certificate removed for device: $deviceId")
        }
    }

    override fun verifyCertificate(certificate: X509Certificate): Boolean {
        return try {
            // 检查证书是否过期
            certificate.checkValidity()

            // 验证证书签名
            certificate.verify(certificate.publicKey)

            logger.d(TAG, "Certificate is valid")
            true

        } catch (e: Exception) {
            logger.e(TAG, "Certificate verification failed", e)
            false
        }
    }

    override fun generateSelfSignedCertificate(): X509Certificate {
        ensureLocalCertificate()
        return keyStore.getCertificate(LOCAL_KEY_ALIAS) as X509Certificate
    }

    private fun ensureLocalCertificate() {
        if (keyStore.containsAlias(LOCAL_KEY_ALIAS)) {
            return
        }

        val now = Calendar.getInstance()
        val end = Calendar.getInstance().apply {
            add(Calendar.YEAR, CERT_VALID_YEARS)
        }

        val generator = KeyPairGenerator.getInstance(
            LOCAL_KEY_ALGORITHM
        )
        generator.initialize(KEY_SIZE_BITS, SecureRandom())

        val keyPair = generator.generateKeyPair()
        val certificate = createSelfSignedCertificate(keyPair, now, end)
        keyStore.setKeyEntry(
            LOCAL_KEY_ALIAS,
            keyPair.private,
            LOCAL_KEYSTORE_PASSWORD,
            arrayOf(certificate)
        )
        saveLocalKeyStore()
        logger.i(TAG, "Generated local TLS certificate")
    }

    private fun createSelfSignedCertificate(
        keyPair: KeyPair,
        notBefore: Calendar,
        notAfter: Calendar
    ): X509Certificate {
        val subject = X500Name("CN=SMS-link Local Device")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore.time,
            notAfter.time,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder(LOCAL_SIGNATURE_ALGORITHM).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(keyPair.public)
        return certificate
    }

    private fun saveLocalKeyStore() {
        FileOutputStream(localKeyStoreFile).use { output ->
            keyStore.store(output, LOCAL_KEYSTORE_PASSWORD)
        }
    }

    private fun createKeyManagers(): Array<KeyManager> {
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, LOCAL_KEYSTORE_PASSWORD)
        return keyManagerFactory.keyManagers.map { keyManager ->
            if (keyManager is X509ExtendedKeyManager) {
                AliasForcingKeyManager(keyManager, LOCAL_KEY_ALIAS)
            } else {
                keyManager
            }
        }.toTypedArray()
    }

    private class AliasForcingKeyManager(
        private val delegate: X509ExtendedKeyManager,
        private val alias: String
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String = alias

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String = alias

        override fun getCertificateChain(alias: String?): Array<X509Certificate> {
            return delegate.getCertificateChain(this.alias) ?: emptyArray()
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
            return arrayOf(alias)
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            return delegate.getPrivateKey(this.alias)
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
            return arrayOf(alias)
        }
    }

    /**
     * 创建信任管理器
     */
    private fun createTrustManager(certificate: X509Certificate): TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // 客户端模式不需要验证客户端证书
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) {
                    throw IllegalArgumentException("Certificate chain is empty")
                }

                // 验证服务器证书
                val serverCert = chain[0]

                // 检查证书是否匹配
                if (!serverCert.equals(certificate)) {
                    logger.w(TAG, "Server certificate does not match stored certificate")
                    throw CertificateException("Certificate mismatch")
                }

                // 验证证书有效性
                serverCert.checkValidity()
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf(certificate)
            }
        }
    }

    /**
     * 创建宽松的信任管理器（仅用于开发）
     */
    private fun createPermissiveTrustManager(): TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // 接受所有客户端证书
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // 接受所有服务器证书
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }
    }

    companion object {
        private const val TAG = "Encryption"
        private const val LOCAL_KEYSTORE_TYPE = "PKCS12"
        private val LOCAL_KEYSTORE_PASSWORD = "smslink-local-tls".toCharArray()
        private const val LOCAL_KEY_ALIAS = "smslink_local_tls"
        private const val CERT_VALID_YEARS = 20
        private const val LOCAL_KEY_ALGORITHM = "RSA"
        private const val LOCAL_SIGNATURE_ALGORITHM = "SHA256withRSA"
        private const val KEY_SIZE_BITS = 2048
    }
}
