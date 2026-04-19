package com.smslink.device.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * BLE 权限助手单元测试
 */
class BlePermissionHelperTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var locationManager: LocationManager
    private lateinit var permissionHelper: BlePermissionHelper

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        locationManager = mockk(relaxed = true)

        every { context.packageManager } returns packageManager
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager

        permissionHelper = BlePermissionHelper(context)
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `test bluetooth supported`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) } returns true
        assertTrue(permissionHelper.isBluetoothSupported())

        every { packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) } returns false
        assertFalse(permissionHelper.isBluetoothSupported())
    }

    @Test
    fun `test location enabled`() {
        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true
        every { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false
        assertTrue(permissionHelper.isLocationEnabled())

        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns false
        every { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
        assertTrue(permissionHelper.isLocationEnabled())

        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns false
        every { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false
        assertFalse(permissionHelper.isLocationEnabled())
    }

    @Test
    fun `test has all permissions when granted`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(permissionHelper.hasAllBlePermissions())
        assertTrue(permissionHelper.hasBluetoothPermissions())
        assertTrue(permissionHelper.hasLocationPermissions())

        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `test has all permissions when denied`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(permissionHelper.hasAllBlePermissions())

        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `test get required permissions`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_DENIED

        val permissions = permissionHelper.getRequiredPermissions()
        assertTrue(permissions.isNotEmpty())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
            assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
            assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            assertTrue(permissions.contains(Manifest.permission.BLUETOOTH))
            assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_ADMIN))
        }

        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))

        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `test permission status`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_GRANTED

        every { packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) } returns true
        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true

        val status = permissionHelper.getPermissionStatus()

        assertTrue(status.bluetoothSupported)
        assertTrue(status.bluetoothPermissions)
        assertTrue(status.locationPermissions)
        assertTrue(status.locationEnabled)
        assertTrue(status.allPermissionsGranted)

        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `test missing permissions description`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_DENIED

        every { packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) } returns false
        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns false
        every { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false

        val status = permissionHelper.getPermissionStatus()
        val description = status.getMissingPermissionsDescription()

        assertTrue(description.contains("设备不支持 BLE"))
        assertTrue(description.contains("蓝牙权限"))
        assertTrue(description.contains("位置权限"))
        assertTrue(description.contains("位置服务未开启"))

        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `test all permissions granted description`() {
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, any())
        } returns PackageManager.PERMISSION_GRANTED

        every { packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) } returns true
        every { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true

        val status = permissionHelper.getPermissionStatus()
        val description = status.getMissingPermissionsDescription()

        assertEquals("所有权限已授予", description)

        unmockkStatic(ContextCompat::class)
    }
}
