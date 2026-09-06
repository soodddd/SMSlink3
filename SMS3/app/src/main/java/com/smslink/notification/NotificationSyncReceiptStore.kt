package com.smslink.notification

import android.content.Context
import com.smslink.core.model.AppNotification
import java.security.MessageDigest

/**
 * Per-device notification delivery receipts.
 *
 * AppNotification.isSynced is retained for UI/backward compatibility, but it
 * cannot represent delivery to more than one peer. These receipts are the
 * durable source used when a newly connected device catches up.
 */
internal class NotificationSyncReceiptStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun isSynced(
        notificationId: String,
        deviceId: String,
        contentFingerprint: String? = null
    ): Boolean {
        if (preferences.getLong(receiptKey(notificationId, deviceId), 0L) <= 0L) {
            return false
        }
        // Older builds stored only the timestamp. Treat such a receipt as
        // stale when the caller supplies content, so the first update after
        // upgrading is sent instead of silently losing the new text.
        return contentFingerprint == null ||
            preferences.getString(fingerprintKey(notificationId, deviceId), null) == contentFingerprint
    }

    @Synchronized
    fun markSynced(
        notificationId: String,
        deviceId: String,
        contentFingerprint: String? = null
    ) {
        preferences.edit().apply {
            putLong(receiptKey(notificationId, deviceId), System.currentTimeMillis())
            if (contentFingerprint != null) {
                putString(fingerprintKey(notificationId, deviceId), contentFingerprint)
            }
        }.apply()
    }

    @Synchronized
    fun clearNotification(notificationId: String) {
        val editor = preferences.edit()
        val notificationDigest = digest(notificationId)
        preferences.all.keys
            .filter { it.contains("_" + notificationDigest) }
            .forEach(editor::remove)
        editor.apply()
    }

    internal fun fingerprint(notification: AppNotification): String = digest(
        listOf(
            notification.packageName,
            notification.appName,
            notification.title,
            notification.text,
            notification.timestamp.toString(),
            notification.deviceId
        ).joinToString("\u0000")
    )

    private fun receiptKey(notificationId: String, deviceId: String): String =
        digest(deviceId) + "_" + digest(notificationId)

    private fun fingerprintKey(notificationId: String, deviceId: String): String =
        receiptKey(notificationId, deviceId) + "_fingerprint"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val PREFERENCES_NAME = "smslink_notification_sync_receipts"
    }
}
