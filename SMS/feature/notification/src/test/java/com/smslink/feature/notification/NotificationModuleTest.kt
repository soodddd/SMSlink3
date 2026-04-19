package com.smslink.feature.notification

import com.smslink.core.model.NotificationInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * 通知模块单元测试
 */
class NotificationModuleTest {

    @Test
    fun `test NotificationInfo creation`() {
        val notification = NotificationInfo(
            id = "test-id",
            appName = "测试应用",
            appPackage = "com.test.app",
            title = "测试标题",
            text = "测试内容",
            deviceId = "device-1",
            deviceName = "测试设备",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            iconPath = null
        )

        assertEquals("test-id", notification.id)
        assertEquals("测试应用", notification.appName)
        assertEquals("com.test.app", notification.appPackage)
        assertEquals("测试标题", notification.title)
        assertEquals("测试内容", notification.text)
        assertEquals("device-1", notification.deviceId)
        assertEquals("测试设备", notification.deviceName)
        assertFalse(notification.isRead)
        assertNull(notification.iconPath)
    }

    @Test
    fun `test NotificationInfo with icon path`() {
        val notification = NotificationInfo(
            id = "test-id",
            appName = "测试应用",
            appPackage = "com.test.app",
            title = "测试标题",
            text = "测试内容",
            deviceId = "device-1",
            deviceName = "测试设备",
            timestamp = System.currentTimeMillis(),
            isRead = true,
            iconPath = "/path/to/icon.png"
        )

        assertTrue(notification.isRead)
        assertEquals("/path/to/icon.png", notification.iconPath)
    }
}
