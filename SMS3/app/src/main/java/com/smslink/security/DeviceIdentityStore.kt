package com.smslink.security

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent device identity used by pairing and the application-level
 * connection handshake. The private key never leaves Android Keystore.
 */
@Singleton
class DeviceIdentityStore private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
    private val allowJvmFallback: Boolean
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Unit, false)

    /** Lightweight constructor for JVM tests that do not have an Android Context. */
    internal constructor() : this(null, Unit, true)

    /** Test helper which still honors mocked Settings.Secure values. */
    internal constructor(context: Context, testOnly: Boolean) : this(context, Unit, true)

    private val preferences by lazy {
        context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var testKeyPair: KeyPair? = null

    @Volatile
    private var testDeviceId: String? = null

    fun deviceId(): String {
        if (context == null) {
            return testDeviceId ?: synchronized(this) {
                testDeviceId ?: UUID.randomUUID().toString().also { testDeviceId = it }
            }
        }
        val androidId = context?.let {
            runCatching {
                Settings.Secure.getString(it.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
        }?.trim()

        if (!androidId.isNullOrEmpty()) return androidId

        val stored = preferences?.getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrEmpty()) return stored

        val generated = UUID.randomUUID().toString()
        preferences?.edit()?.putString(KEY_DEVICE_ID, generated)?.apply()
        return generated
    }

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(keyPair().public.encoded)

    fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair().private)
        signature.update(payload)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun sign(payload: String): String = sign(payload.toByteArray(StandardCharsets.UTF_8))

    fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean {
        return runCatching {
            val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(payload)
            signature.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)
    }

    fun verify(publicKeyBase64: String, payload: String, signatureBase64: String): Boolean =
        verify(publicKeyBase64, payload.toByteArray(StandardCharsets.UTF_8), signatureBase64)

    private fun keyPair(): KeyPair {
        context ?: return testKeyPair ?: synchronized(this) {
            testKeyPair ?: generateJvmKeyPair().also { testKeyPair = it }
        }

        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            val certificate = keyStore.getCertificate(KEY_ALIAS)
            if (privateKey != null && certificate != null) {
                return@runCatching KeyPair(certificate.publicKey, privateKey)
            }

            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
            )
            generator.generateKeyPair()
        }.getOrElse {
            if (!allowJvmFallback) {
                throw IllegalStateException("Android Keystore is unavailable", it)
            }
            // Some JVM test environments do not expose Android Keystore.
            synchronized(this) {
                testKeyPair ?: generateJvmKeyPair().also { testKeyPair = it }
            }
        }
    }

    private fun generateJvmKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance(KEY_ALGORITHM).apply { initialize(2048) }.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "smslink_identity_rsa"
        private const val KEY_ALGORITHM = "RSA"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        private const val PREFERENCES_NAME = "smslink_identity"
        private const val KEY_DEVICE_ID = "device_id"

        fun forTests(): DeviceIdentityStore = DeviceIdentityStore()

        fun forTests(context: Context): DeviceIdentityStore = DeviceIdentityStore(context, true)
    }
}
