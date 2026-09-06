package com.smslink.notification

import android.content.Context
import android.content.SharedPreferences
import com.smslink.core.model.AppNotification
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationSyncReceiptStoreTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var values: MutableMap<String, Any?>
    private lateinit var store: NotificationSyncReceiptStore

    @Before
    fun setUp() {
        values = mutableMapOf()
        context = mockk()
        preferences = mockk()
        editor = mockk()

        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getLong(any(), any()) } answers {
            values[firstArg<String>()] as? Long ?: secondArg<Long>()
        }
        every { preferences.getString(any(), any()) } answers {
            values[firstArg<String>()] as? String ?: secondArg<String?>()
        }
        every { preferences.all } answers { values.toMap() }
        every { preferences.edit() } returns editor
        every { editor.putLong(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<Long>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg<String>())
            editor
        }
        every { editor.apply() } answers { }

        store = NotificationSyncReceiptStore(context)
    }

    @Test
    fun `content changes invalidate an old per-device receipt`() {
        val old = notification(text = "old")
        val updated = old.copy(text = "updated")
        val target = "device-1"

        store.markSynced(old.id, target, store.fingerprint(old))

        assertTrue(store.isSynced(old.id, target, store.fingerprint(old)))
        assertFalse(store.isSynced(updated.id, target, store.fingerprint(updated)))

        store.markSynced(updated.id, target, store.fingerprint(updated))
        assertTrue(store.isSynced(updated.id, target, store.fingerprint(updated)))
    }

    @Test
    fun `clearing a notification removes its receipt and fingerprint`() {
        val notification = notification(text = "text")
        val target = "device-1"
        store.markSynced(notification.id, target, store.fingerprint(notification))

        store.clearNotification(notification.id)

        assertFalse(store.isSynced(notification.id, target, store.fingerprint(notification)))
    }

    private fun notification(text: String) = AppNotification(
        id = "notification-1",
        packageName = "com.example.app",
        appName = "Example",
        title = "Title",
        text = text,
        timestamp = 1_700_000_000_000L,
        deviceId = "local-device",
        isSynced = false
    )
}
