package com.smslink.network.encryption

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Small, bounded codec for exchanging the self-signed TLS certificate. */
object CertificateCodec {
    private const val MAX_CERTIFICATE_BYTES = 32 * 1024

    fun encode(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(certificate.encoded)

    fun decode(value: String?): X509Certificate? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(value)
            require(bytes.isNotEmpty() && bytes.size <= MAX_CERTIFICATE_BYTES)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull()
    }
}
