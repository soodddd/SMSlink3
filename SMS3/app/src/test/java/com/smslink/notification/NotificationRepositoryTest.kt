package com.smslink.notification

import com.smslink.core.database.dao.NotificationDao
import com.smslink.core.model.AppNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NotificationRepository 单元测试
 */
class NotificationRepositoryTest {

    private lateinit var notificationDao: NotificationDao
    private lateinit var repository: NotificationRepository

    @Before
    fun setup() {
        notificationDao = mockk(relaxed = true)
        repository = NotificationRepository(notificationDao)
    }

    @Test
    fun `getAllNotifications should return notifications from dao`() = runTest {
        // Given
        val notifications = listOf(
            createTestNotification("1"),
            createTestNotification("2")
        )
        coEvery { notificationDao.getAll(any()) } returns flowOf(notifications)

        // When
        var result: List<AppNotification>? = null
        repository.getAllNotifications(100).collect { result = it }

        // Then
        assertNotNull(result)
        assertEquals(2, result?.size)
        coVerify { notificationDao.getAll(100) }
    }

    @Test
    fun `getNotificationsByDevice should return notifications for specific device`() = runTest {
        // Given
        val deviceId = "device123"
        val notifications = listOf(createTestNotification("1", deviceId = deviceId))
        coEvery { notificationDao.getByDeviceId(deviceId) } returns flowOf(notifications)

        // When
        var result: List<AppNotification>? = null
        repository.getNotificationsByDevice(deviceId).collect { result = it }

        // Then
        assertNotNull(result)
        assertEquals(1, result?.size)
        assertEquals(deviceId, result?.first()?.deviceId)
        coVerify { notificationDao.getByDeviceId(deviceId) }
    }

    @Test
    fun `getUnsyncedNotifications should return only unsynced notifications`() = runTest {
        // Given
        val notifications = listOf(
            createTestNotification("1", isSynced = false),
            createTestNotification("2", isSynced = false)
        )
        coEvery { notificationDao.getUnsynced() } returns flowOf(notifications)

        // When
        var result: List<AppNotification>? = null
        repository.getUnsyncedNotifications().collect { result = it }

        // Then
        assertNotNull(result)
        assertEquals(2, result?.size)
        result?.forEach { assertFalse(it.isSynced) }
        coVerify { notificationDao.getUnsynced() }
    }

    @Test
    fun `insertNotification should call dao insert`() = runTest {
        // Given
        val notification = createTestNotification("1")

        // When
        repository.insertNotification(notification)

        // Then
        coVerify { notificationDao.insert(notification) }
    }

    @Test
    fun `deleteNotificationById should call dao deleteById`() = runTest {
        // Given
        val notificationId = "notification123"

        // When
        repository.deleteNotificationById(notificationId)

        // Then
        coVerify { notificationDao.deleteById(notificationId) }
    }

    @Test
    fun `markAsSynced should call dao markAsSynced`() = runTest {
        // Given
        val notificationId = "notification123"

        // When
        repository.markAsSynced(notificationId)

        // Then
        coVerify { notificationDao.markAsSynced(notificationId) }
    }

    @Test
    fun `clearAll should call dao deleteAll`() = runTest {
        // When
        repository.clearAll()

        // Then
        coVerify { notificationDao.deleteAll() }
    }

    private fun createTestNotification(
        id: String,
        deviceId: String = "device1",
        isSynced: Boolean = false
    ) = AppNotification(
        id = id,
        packageName = "com.example.app",
        appName = "Test App",
        title = "Test Title",
        text = "Test Text",
        timestamp = System.currentTimeMillis(),
        deviceId = deviceId,
        isSynced = isSynced
    )
}
