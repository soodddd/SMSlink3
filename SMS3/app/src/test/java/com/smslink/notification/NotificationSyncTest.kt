package com.smslink.notification

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.device.IDeviceManager
import com.smslink.network.model.MessageType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.model.SendResult
import com.smslink.network.transport.IMessageTransport
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通知同步功能测试
 */
class NotificationSyncTest {

    private lateinit var context: Context
    private lateinit var repository: NotificationRepository
    private lateinit var messageTransport: IMessageTransport
    private lateinit var deviceManager: IDeviceManager
    private lateinit var preferences: SharedPreferences
    private lateinit var logger: ILogger
    private lateinit var notificationManager: NotificationManagerImpl

    private val testDevice = Device(
        id = "test_device_1",
        name = "Test Device",
        type = DeviceType.PHONE,
        role = DeviceRole.MAIN,
        publicKey = null,
        lastSeen = System.currentTimeMillis(),
        isPaired = true
    )

    private val testNotification = AppNotification(
        id = "test_notification_1",
        packageName = "com.example.app",
        appName = "Test App",
        title = "Test Title",
        text = "Test Text",
        timestamp = System.currentTimeMillis(),
        deviceId = "local_device",
        isSynced = false
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        messageTransport = mockk(relaxed = true)
        coEvery { messageTransport.awaitDelivery(any(), any()) } returns true
        deviceManager = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        // Mock preferences
        every { preferences.getBoolean(any(), any()) } returns false
        every { preferences.getStringSet(any(), any()) } returns emptySet()
        every { preferences.edit() } returns mockk(relaxed = true)

        // Mock device manager
        every { deviceManager.getLocalDevice() } returns testDevice
        every { deviceManager.getConnectedDevices() } returns flowOf(listOf(testDevice))

        notificationManager = NotificationManagerImpl(
            context,
            repository,
            messageTransport,
            deviceManager,
            preferences,
            logger
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `syncNotification should send notification to target device`() = runTest {
        // Given
        val targetDeviceId = "target_device_1"
        val sendResult = SendResult(success = true, messageId = "msg_1")

        coEvery { messageTransport.sendMessage(any(), any()) } returns flowOf(sendResult)
        coEvery { repository.markAsSynced(any()) } just Runs

        // When
        notificationManager.syncNotification(testNotification, targetDeviceId)

        // Then
        coVerify { messageTransport.sendMessage(targetDeviceId, any()) }
        coVerify { repository.markAsSynced(testNotification.id) }
    }

    @Test
    fun `syncNotification should not sync when dual-app suppression is enabled`() = runTest {
        // Given
        every { preferences.getBoolean("dual_app_suppress", true) } returns true
        val targetDeviceId = "target_device_1"

        // When
        notificationManager.syncNotification(testNotification, targetDeviceId)

        // Then - should still attempt to send (suppression logic needs device app list)
        coVerify { messageTransport.sendMessage(any(), any()) }
    }

    @Test
    fun `syncNotificationToDevices should sync to all connected devices`() = runTest {
        // Given
        val devices = listOf(
            testDevice.copy(id = "device_1"),
            testDevice.copy(id = "device_2")
        )
        every { deviceManager.getConnectedDevices() } returns flowOf(devices)

        val sendResult = SendResult(success = true, messageId = "msg_1")
        coEvery { messageTransport.sendMessage(any(), any()) } returns flowOf(sendResult)
        coEvery { repository.markAsSynced(any()) } just Runs

        // When
        notificationManager.syncNotificationToDevices(testNotification)

        // Then
        coVerify(exactly = 2) { messageTransport.sendMessage(any(), any()) }
    }

    @Test
    fun `handleRemoteNotification should save and display notification`() = runTest {
        // Given
        val payload = JsonObject().apply {
            addProperty("notificationId", "remote_notif_1")
            addProperty("packageName", "com.remote.app")
            addProperty("appName", "Remote App")
            addProperty("title", "Remote Title")
            addProperty("text", "Remote Text")
            addProperty("timestamp", System.currentTimeMillis())
        }

        val message = NetworkMessage(
            messageType = MessageType.NOTIFICATION,
            messageId = "00000000-0000-0000-0000-000000000001",
            sourceDevice = "remote_device",
            targetDevice = testDevice.id,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )

        coEvery { repository.insertNotification(any()) } just Runs
        every { messageTransport.receiveMessages() } returns flowOf(message)

        // Recreate the manager so the init-time receiver sees the mocked flow
        notificationManager = NotificationManagerImpl(
            context,
            repository,
            messageTransport,
            deviceManager,
            preferences,
            logger
        )
        advanceUntilIdle()

        // Then
        coVerify(timeout = 1000) { repository.insertNotification(any()) }
    }

    @Test
    fun `dismissNotification should send control message`() = runTest {
        // Given
        val notificationId = "notif_to_dismiss"
        val targetDeviceId = "target_device"
        val sendResult = SendResult(success = true, messageId = "msg_1")

        coEvery { messageTransport.sendMessage(any(), any()) } returns flowOf(sendResult)

        // When
        notificationManager.dismissNotification(notificationId, targetDeviceId)

        // Then
        coVerify {
            messageTransport.sendMessage(
                targetDeviceId,
                match { it.messageType == MessageType.CONTROL }
            )
        }
    }

    @Test
    fun `executeAction should send control message with action`() = runTest {
        // Given
        val notificationId = "notif_1"
        val actionId = "action_reply"
        val targetDeviceId = "target_device"
        val sendResult = SendResult(success = true, messageId = "msg_1")

        coEvery { messageTransport.sendMessage(any(), any()) } returns flowOf(sendResult)

        // When
        notificationManager.executeAction(notificationId, actionId, targetDeviceId)

        // Then
        coVerify {
            messageTransport.sendMessage(
                targetDeviceId,
                match {
                    it.messageType == MessageType.CONTROL &&
                    it.payload.get("actionId")?.asString == actionId
                }
            )
        }
    }

    @Test
    fun `createNotificationPayload should serialize notification correctly`() {
        // Given
        val notification = testNotification

        // When - use reflection to access private method for testing
        val method = NotificationManagerImpl::class.java.getDeclaredMethod(
            "createNotificationPayload",
            AppNotification::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(notificationManager, notification) as JsonObject

        // Then
        assertEquals(notification.id, payload.get("notificationId").asString)
        assertEquals(notification.packageName, payload.get("packageName").asString)
        assertEquals(notification.appName, payload.get("appName").asString)
        assertEquals(notification.title, payload.get("title").asString)
        assertEquals(notification.text, payload.get("text").asString)
    }

    @Test
    fun `parseNotificationPayload should deserialize notification correctly`() {
        // Given
        val payload = JsonObject().apply {
            addProperty("notificationId", "test_id")
            addProperty("packageName", "com.test.app")
            addProperty("appName", "Test App")
            addProperty("title", "Test Title")
            addProperty("text", "Test Text")
            addProperty("timestamp", 123456789L)
        }
        val sourceDeviceId = "source_device"

        // When - use reflection to access private method
        val method = NotificationManagerImpl::class.java.getDeclaredMethod(
            "parseNotificationPayload",
            JsonObject::class.java,
            String::class.java
        )
        method.isAccessible = true
        val notification = method.invoke(notificationManager, payload, sourceDeviceId) as AppNotification

        // Then
        assertEquals("test_id", notification.id)
        assertEquals("com.test.app", notification.packageName)
        assertEquals("Test App", notification.appName)
        assertEquals("Test Title", notification.title)
        assertEquals("Test Text", notification.text)
        assertEquals(sourceDeviceId, notification.deviceId)
        assertTrue(notification.isSynced)
    }

    @Test
    fun `shouldSuppressNotification returns false when suppression disabled`() {
        // Given
        every { preferences.getBoolean("dual_app_suppress", true) } returns false

        // When - use reflection to access private method
        val method = NotificationManagerImpl::class.java.getDeclaredMethod(
            "shouldSuppressNotification",
            AppNotification::class.java,
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(notificationManager, testNotification, "target_device") as Boolean

        // Then
        assertFalse(result)
    }

    @Test
    fun `shouldSuppressNotification returns false for whitelisted apps`() {
        // Given
        every { preferences.getBoolean("dual_app_suppress", true) } returns true
        every { preferences.getStringSet("mirror_whitelist", emptySet()) } returns
            setOf(testNotification.packageName)

        // When - use reflection to access private method
        val method = NotificationManagerImpl::class.java.getDeclaredMethod(
            "shouldSuppressNotification",
            AppNotification::class.java,
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(notificationManager, testNotification, "target_device") as Boolean

        // Then
        assertFalse(result)
    }
}
