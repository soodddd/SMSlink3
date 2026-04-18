package com.smslink.core.permission

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 权限管理器实现类
 */
@Singleton
class PermissionManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IPermissionManager {

    override fun hasPermission(permission: String): Boolean {
        return try {
            context.packageManager?.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    override fun hasPermissions(permissions: List<String>): Boolean {
        return permissions.all { hasPermission(it) }
    }

    override fun requestPermission(permission: String): Flow<PermissionResult> = flow {
        // NOTE: This is skeleton implementation. Actual permission requests require Activity cooperation.
        // Implementation approach:
        // 1. Create a PermissionLauncher interface that wraps ActivityResultContracts.RequestPermission
        // 2. Pass the launcher from Activity/Fragment to this manager
        // 3. Use launcher.launch(permission) to trigger system permission dialog
        // 4. Emit the result through Flow when callback is received
        // Current behavior: Only checks existing permission status without triggering request dialog
        val granted = hasPermission(permission)
        emit(PermissionResult(permission, granted, false))
    }

    override fun requestPermissions(permissions: List<String>): Flow<Map<String, PermissionResult>> = flow {
        // NOTE: This is skeleton implementation. Actual permission requests require Activity cooperation.
        // Implementation approach: Same as requestPermission() but using RequestMultiplePermissions contract
        // Current behavior: Only checks existing permission status without triggering request dialog
        val results = permissions.associateWith { permission ->
            PermissionResult(permission, hasPermission(permission), false)
        }
        emit(results)
    }

    override fun shouldShowRationale(permission: String): Boolean {
        // NOTE: This requires Activity instance to call shouldShowRequestPermissionRationale()
        // Will be implemented when Activity-level permission handling is added
        return false
    }
}
