package com.smslink.device.pairing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores short-lived QR capabilities.  The issuer keeps the capability so it
 * can authenticate the first connection from the scanner; the scanner keeps a
 * separate peer token until that first connection succeeds.
 */
@Singleton
class PairingTokenStore private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") private val marker: Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Unit)

    internal constructor() : this(null, Unit)

    private val memory = ConcurrentHashMap<String, TokenRecord>()
    private val preferences by lazy {
        context?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    fun saveIssued(token: String, targetDeviceId: String, expiresAt: Long) {
        val record = TokenRecord(targetDeviceId, expiresAt)
        memory[token] = record
        preferences?.edit()
            ?.putString(tokenKey(token), "${record.targetDeviceId}|${record.expiresAt}")
            ?.apply()
    }

    fun savePeer(deviceId: String, token: String, expiresAt: Long) {
        val record = TokenRecord(deviceId, expiresAt)
        memory[peerKey(deviceId)] = record.copy(token = token)
        preferences?.edit()
            ?.putString(peerKey(deviceId), "${token}|${record.expiresAt}")
            ?.apply()
    }

    fun getPeer(deviceId: String, now: Long = System.currentTimeMillis()): String? {
        val record = memory[peerKey(deviceId)] ?: preferences?.getString(peerKey(deviceId), null)
            ?.split('|', limit = 2)
            ?.takeIf { it.size == 2 }
            ?.let { TokenRecord(deviceId, it[1].toLongOrNull() ?: 0L, it[0]) }
        if (record == null || record.expiresAt <= now) {
            if (record != null) removePeer(deviceId)
            return null
        }
        return record.token
    }

    @Synchronized
    fun consumeIssued(token: String, targetDeviceId: String, now: Long = System.currentTimeMillis()): Boolean {
        val record = memory[token] ?: preferences?.getString(tokenKey(token), null)
            ?.split('|', limit = 2)
            ?.takeIf { it.size == 2 }
            ?.let { TokenRecord(it[0], it[1].toLongOrNull() ?: 0L) }

        val valid = record != null &&
            record.targetDeviceId == targetDeviceId &&
            record.expiresAt > now
        if (valid) {
            memory.remove(token)
            preferences?.edit()?.remove(tokenKey(token))?.apply()
        }
        return valid
    }

    fun removePeer(deviceId: String) {
        memory.remove(peerKey(deviceId))
        preferences?.edit()?.remove(peerKey(deviceId))?.apply()
    }

    fun cleanup(now: Long = System.currentTimeMillis()) {
        memory.entries.removeIf { it.value.expiresAt <= now }

        // The process may be restarted before an in-memory cleanup runs. Do
        // the same bounded sweep over persisted records so expired QR
        // capabilities cannot accumulate or be accidentally reused.
        val editor = preferences?.edit()
        preferences?.all?.forEach { (key, value) ->
            if (!key.startsWith("issued_") && !key.startsWith("peer_")) return@forEach
            val encoded = value as? String ?: run {
                editor?.remove(key)
                return@forEach
            }
            val expiry = encoded.substringAfterLast('|', "").toLongOrNull()
            if (expiry == null || expiry <= now) editor?.remove(key)
        }
        editor?.apply()
    }

    private fun tokenKey(token: String) = "issued_$token"
    private fun peerKey(deviceId: String) = "peer_$deviceId"

    private data class TokenRecord(
        val targetDeviceId: String,
        val expiresAt: Long,
        val token: String? = null
    )

    companion object {
        private const val PREFERENCES = "smslink_pairing_tokens"

        fun forTests(): PairingTokenStore = PairingTokenStore()
    }
}
