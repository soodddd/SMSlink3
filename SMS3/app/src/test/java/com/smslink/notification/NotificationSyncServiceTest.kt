package com.smslink.notification

import android.content.Context
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test

class NotificationSyncServiceTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManagerImpl
    private lateinit var logger: ILogger
    private lateinit var service: NotificationSyncService

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        service = NotificationSyncService()

        setField(service, "notificationManager", notificationManager)
        setField(service, "logger", logger)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `startNotificationSync should only forward unsynced notifications`() {
        val localNotification = AppNotification(
            id = "local_1",
            packageName = "com.example.local",
            appName = "Local App",
            title = "Local Title",
            text = "Local Text",
            timestamp = 1L,
            deviceId = "device_a",
            isSynced = false
        )
        val remoteNotification = localNotification.copy(
            id = "remote_1",
            packageName = "com.example.remote",
            appName = "Remote App",
            isSynced = true
        )

        every { notificationManager.getNotifications() } returns flowOf(localNotification, remoteNotification)
        coEvery { notificationManager.syncNotificationToDevices(any()) } just runs

        invokePrivateStartNotificationSync()

        Thread.sleep(300)

        coVerify(exactly = 1) { notificationManager.syncNotificationToDevices(localNotification) }
    }

    private fun invokePrivateStartNotificationSync() {
        val method = NotificationSyncService::class.java.getDeclaredMethod("startNotificationSync")
        method.isAccessible = true
        method.invoke(service)
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
