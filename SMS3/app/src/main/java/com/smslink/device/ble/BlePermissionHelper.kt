package com.smslink.device.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE 权限助手
 * 处理 BLE 相关权限检查和请求
 */
@Singleton
class BlePermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 检查是否具有所有必需的 BLE 权限
     */
    fun hasAllBlePermissions(): Boolean {
        return hasBluetoothPermissions() &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || hasLocationPermissions())
    }

    /**
     * 检查蓝牙权限
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要新的蓝牙权限
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 11 及以下使用旧权限
            hasPermission(Manifest.permission.BLUETOOTH) &&
            hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    /**
     * 检查位置权限（BLE 扫描需要）
     */
    fun hasLocationPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The scanner declares neverForLocation; Android 12+ does not
            // require location permission for this app's BLE discovery.
            true
        } else {
            // Android 11 及以下必须有位置权限
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    /**
     * 检查位置服务是否开启
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.let {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    /**
     * 检查蓝牙是否支持
     */
    fun isBluetoothSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * 获取需要请求的权限列表
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            // Android 11 及以下
            if (!hasPermission(Manifest.permission.BLUETOOTH)) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Android 12+ uses BLUETOOTH_SCAN with neverForLocation. Only legacy
        // platforms need location for BLE scanning.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions
    }

    /**
     * 检查单个权限
     */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 获取权限状态描述
     */
    fun getPermissionStatus(): PermissionStatus {
        return PermissionStatus(
            bluetoothSupported = isBluetoothSupported(),
            bluetoothPermissions = hasBluetoothPermissions(),
            locationPermissions = hasLocationPermissions(),
            locationEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || isLocationEnabled(),
            allPermissionsGranted = hasAllBlePermissions()
        )
    }
}

/**
 * 权限状态
 */
data class PermissionStatus(
    val bluetoothSupported: Boolean,
    val bluetoothPermissions: Boolean,
    val locationPermissions: Boolean,
    val locationEnabled: Boolean,
    val allPermissionsGranted: Boolean
) {
    /**
     * 获取缺失的权限描述
     */
    fun getMissingPermissionsDescription(): String {
        val missing = mutableListOf<String>()

        if (!bluetoothSupported) {
            missing.add("设备不支持 BLE")
        }
        if (!bluetoothPermissions) {
            missing.add("蓝牙权限")
        }
        if (!locationPermissions) {
            missing.add("位置权限")
        }
        if (!locationEnabled) {
            missing.add("位置服务未开启")
        }

        return if (missing.isEmpty()) {
            "所有权限已授予"
        } else {
            "缺少: ${missing.joinToString(", ")}"
        }
    }
}
