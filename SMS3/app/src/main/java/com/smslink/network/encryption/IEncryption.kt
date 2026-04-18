package com.smslink.network.encryption

import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext

/**
 * 加密接口
 * 负责 TLS/SSL 加密通信和证书管理
 */
interface IEncryption {
    /**
     * 创建 SSL 上下文
     * @param deviceId 设备ID
     * @return SSL 上下文
     */
    fun createSSLContext(deviceId: String): SSLContext

    /**
     * 获取设备证书
     * @param deviceId 设备ID
     * @return 证书，如果不存在返回 null
     */
    fun getDeviceCertificate(deviceId: String): X509Certificate?

    /**
     * 保存设备证书
     * @param deviceId 设备ID
     * @param certificate 证书
     */
    suspend fun saveDeviceCertificate(deviceId: String, certificate: X509Certificate)

    /**
     * 删除设备证书
     * @param deviceId 设备ID
     */
    suspend fun removeDeviceCertificate(deviceId: String)

    /**
     * 验证证书
     * @param certificate 证书
     * @return 是否有效
     */
    fun verifyCertificate(certificate: X509Certificate): Boolean

    /**
     * 生成自签名证书
     * @return 证书
     */
    fun generateSelfSignedCertificate(): X509Certificate
}
