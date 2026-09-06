package com.smslink.core.permission

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Small compatibility facade for library callers. Runtime requests in the
 * application use [EnhancedPermissionManager], which owns Activity Result
 * launchers; this class remains useful for non-Activity checks and old tests.
 */
class PermissionManagerImpl(private val context: Context) : IPermissionManager {
    override fun hasPermission(permission: String): Boolean =
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    override fun hasPermissions(permissions: List<String>): Boolean = permissions.all(::hasPermission)

    override fun requestPermission(permission: String): Flow<PermissionResult> = flow {
        emit(PermissionResult(permission, hasPermission(permission), false))
    }

    override fun requestPermissions(permissions: List<String>): Flow<Map<String, PermissionResult>> = flow {
        emit(permissions.associateWith { permission ->
            PermissionResult(permission, hasPermission(permission), false)
        })
    }

    override fun shouldShowRationale(permission: String): Boolean = false
}
