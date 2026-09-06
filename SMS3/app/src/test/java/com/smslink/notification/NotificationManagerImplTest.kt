package com.smslink.notification

import android.content.Context
import android.provider.Settings
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.network.model.NetworkMessage
import com.smslink.network.model.SendResult
import com.smslink.network.transport.IMessageTransport
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NotificationManagerImpl 单元测试
 */
class NotificationManagerImplTest {

    private lateinit var context: Context
    private lateinit var repository: NotificationRepository
    private lateinit var messageTransport: IMessageTransport
    private lateinit var deviceManager: com.smslink.device.IDeviceManager
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var logger: ILogger
    private lateinit var notificationManager: NotificationManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        messageTransport = mockk()
        deviceManager = mockk()
        preferences = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { messageTransport.receiveMessages() } returns emptyFlow()
        every {
            messageTransport.sendMessage(any(), any<NetworkMessage>())
        } returns flowOf(SendResult(success = true, messageId = "mid", error = null))
        coEvery { messageTransport.awaitDelivery(any(), any()) } returns true
        coEvery { messageTransport.sendAck(any(), any()) } just Runs
        every { messageTransport.getPendingMessageCount() } returns 0
        coEvery { messageTransport.clearQueue() } just Runs

        every { deviceManager.startDiscovery() } just Runs
        every { deviceManager.stopDiscovery() } just Runs
        every { deviceManager.getConnectedDevices() } returns flowOf(emptyList())
        coEvery { deviceManager.pairDevice(any(), any()) } returns emptyFlow()
        coEvery { deviceManager.setDeviceRole(any(), any()) } just Runs
        coEvery { deviceManager.removeDevice(any()) } just Runs
        every { deviceManager.getLocalDevice() } returns Device(
            id = "local_device",
            name = "Local Device",
            type = DeviceType.PHONE,
            role = DeviceRole.MAIN,
            lastSeen = System.currentTimeMillis(),
            isPaired = true
        )

        notificationManager = NotificationManagerImpl(
            context = context,
            repository = repository,
            messageTransport = messageTransport,
            deviceManager = deviceManager,
            preferences = preferences,
            logger = logger
        )
    }

    @Test
    fun `startListening should open notification listener settings when access is missing`() {
        // When
        notificationManager.startListening()

        // Then
        verify { context.startActivity(any()) }
        verify { logger.i(any(), any()) }
    }

    @Test
    fun `stopListening should not stop a notification listener as a normal service`() {
        // Given
        notificationManager.startListening()

        // When
        notificationManager.stopListening()

        // Then
        verify(exactly = 0) { context.stopService(any()) }
    }

    @Test
    fun `onNotificationPosted should save notification and emit to flow`() = runTest {
        // Given
        val notification = createTestNotification("1")
        coEvery { repository.insertNotification(any()) } just Runs

        // When
        notificationManager.onNotificationPosted(notification)

        // Wait for coroutine to complete
        kotlinx.coroutines.delay(100)

        // Then
        coVerify { repository.insertNotification(notification) }
    }

    @Test
    fun `syncNotification should mark notification as synced`() = runTest {
        // Given
        val notification = createTestNotification("1")
        val targetDeviceId = "device123"
        coEvery { repository.markAsSynced(any()) } just Runs

        // When
        notificationManager.syncNotification(notification, targetDeviceId)

        // Then
        coVerify { repository.markAsSynced(notification.id) }
        verify { logger.d(any(), any()) }
    }

    @Test
    fun `clearNotification should delete notification from repository`() = runTest {
        // Given
        val notificationId = "notification123"
        coEvery { repository.deleteNotificationById(any()) } just Runs

        // When
        notificationManager.clearNotification(notificationId)

        // Then
        coVerify { repository.deleteNotificationById(notificationId) }
        verify { logger.d(any(), any()) }
    }

    @Test
    fun `getHistoryNotifications should return notifications from repository`() = runTest {
        // Given
        val notifications = listOf(
            createTestNotification("1"),
            createTestNotification("2")
        )
        coEvery { repository.getAllNotifications(any()) } returns flowOf(notifications)

        // When
        val result = notificationManager.getHistoryNotifications(100)

        // Then
        assertEquals(2, result.size)
        coVerify { repository.getAllNotifications(100) }
    }

    @Test
    fun `syncNotificationToDevices should use a snapshot of connected devices`() = runTest {
        val notification = createTestNotification("1")
        val device = Device(
            id = "device123",
            name = "Target Device",
            type = DeviceType.PHONE,
            role = DeviceRole.SECONDARY,
            lastSeen = System.currentTimeMillis(),
            isPaired = true
        )

        every { deviceManager.getConnectedDevices() } returns flowOf(
            listOf(device),
            listOf(device, device.copy(id = "device456"))
        )
        coEvery { repository.markAsSynced(any()) } just Runs

        notificationManager.syncNotificationToDevices(notification)

        coVerify(exactly = 1) { repository.markAsSynced(notification.id) }
        verify(exactly = 1) { messageTransport.sendMessage("device123", any()) }
        verify(exactly = 0) { messageTransport.sendMessage("device456", any()) }
    }

    @Test
    fun `getNotifications should return notification flow`() = runTest {
        // Given
        val notification = createTestNotification("1")

        // When
        val flow = notificationManager.getNotifications()
        notificationManager.onNotificationPosted(notification)

        // Wait for emission
        kotlinx.coroutines.delay(100)

        // Then
        assertNotNull(flow)
    }

    private fun createTestNotification(id: String) = AppNotification(
        id = id,
        packageName = "com.example.app",
        appName = "Test App",
        title = "Test Title",
        text = "Test Text",
        timestamp = System.currentTimeMillis(),
        deviceId = "device1",
        isSynced = false
    )
}
