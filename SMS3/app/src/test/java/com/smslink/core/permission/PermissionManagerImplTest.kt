package com.smslink.core.permission

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * PermissionManagerImpl 单元测试
 */
class PermissionManagerImplTest {

    private lateinit var context: Context
    private lateinit var packageManager: android.content.pm.PackageManager
    private lateinit var permissionManager: PermissionManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.smslink.test"
        permissionManager = PermissionManagerImpl(context)
    }

    @Test
    fun `test hasPermission returns true when permission granted`() {
        // Given
        val permission = "android.permission.READ_SMS"
        every {
            packageManager.checkPermission(permission, "com.smslink.test")
        } returns PackageManager.PERMISSION_GRANTED

        // When
        val result = permissionManager.hasPermission(permission)

        // Then
        assertTrue(result)
    }

    @Test
    fun `test hasPermission returns false when permission denied`() {
        // Given
        val permission = "android.permission.READ_SMS"
        every {
            packageManager.checkPermission(permission, "com.smslink.test")
        } returns PackageManager.PERMISSION_DENIED

        // When
        val result = permissionManager.hasPermission(permission)

        // Then
        assertFalse(result)
    }

    @Test
    fun `test hasPermissions returns true when all permissions granted`() {
        // Given
        val permissions = listOf(
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS"
        )
        every {
            packageManager.checkPermission(any(), "com.smslink.test")
        } returns PackageManager.PERMISSION_GRANTED

        // When
        val result = permissionManager.hasPermissions(permissions)

        // Then
        assertTrue(result)
    }

    @Test
    fun `test hasPermissions returns false when any permission denied`() {
        // Given
        val permissions = listOf(
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS"
        )
        every {
            packageManager.checkPermission("android.permission.READ_SMS", "com.smslink.test")
        } returns PackageManager.PERMISSION_GRANTED
        every {
            packageManager.checkPermission("android.permission.SEND_SMS", "com.smslink.test")
        } returns PackageManager.PERMISSION_DENIED

        // When
        val result = permissionManager.hasPermissions(permissions)

        // Then
        assertFalse(result)
    }

    @Test
    fun `test requestPermission returns flow with result`() = runTest {
        // Given
        val permission = "android.permission.READ_SMS"
        every {
            packageManager.checkPermission(permission, "com.smslink.test")
        } returns PackageManager.PERMISSION_GRANTED

        // When
        val result = permissionManager.requestPermission(permission).first()

        // Then
        assertEquals(permission, result.permission)
        assertTrue(result.granted)
    }
}
