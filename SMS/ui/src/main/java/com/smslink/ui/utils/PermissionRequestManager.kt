package com.smslink.ui.utils

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 权限请求管理器
 * 简化权限请求流程
 */
class PermissionRequestManager(
    private val activity: Activity? = null,
    private val fragment: Fragment? = null
) {
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var onPermissionsResult: ((Map<String, Boolean>) -> Unit)? = null

    /**
     * 初始化权限请求启动器（必须在 onCreate 中调用）
     */
    fun initialize(onResult: (Map<String, Boolean>) -> Unit) {
        onPermissionsResult = onResult

        permissionLauncher = when {
            activity != null -> {
                (activity as? androidx.activity.ComponentActivity)?.registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    onPermissionsResult?.invoke(permissions)
                }
            }
            fragment != null -> {
                fragment.registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    onPermissionsResult?.invoke(permissions)
                }
            }
            else -> null
        }
    }

    /**
     * 请求权限
     */
    fun requestPermissions(permissions: List<String>) {
        val context = activity ?: fragment?.requireContext() ?: return

        // 过滤出未授予的权限
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            // 所有权限都已授予
            onPermissionsResult?.invoke(permissions.associateWith { true })
            return
        }

        // 请求权限
        permissionLauncher?.launch(permissionsToRequest.toTypedArray())
    }

    /**
     * 请求单个权限
     */
    fun requestPermission(permission: String) {
        requestPermissions(listOf(permission))
    }

    /**
     * 检查权限是否已授予
     */
    fun isPermissionGranted(permission: String): Boolean {
        val context = activity ?: fragment?.requireContext() ?: return false
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查多个权限是否都已授予
     */
    fun arePermissionsGranted(permissions: List<String>): Boolean {
        val context = activity ?: fragment?.requireContext() ?: return false
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 是否应该显示权限说明
     */
    fun shouldShowRequestPermissionRationale(permission: String): Boolean {
        return when {
            activity != null -> activity.shouldShowRequestPermissionRationale(permission)
            fragment != null -> fragment.shouldShowRequestPermissionRationale(permission)
            else -> false
        }
    }
}
