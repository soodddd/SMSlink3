package com.smslink.core.permission

import kotlinx.coroutines.flow.Flow

/**
 * 权限管理器接口
 * 负责运行时权限的请求和管理
 */
interface IPermissionManager {
    /**
     * 检查权限是否已授予
     * @param permission 权限名称
     * @return 是否已授予
     */
    fun hasPermission(permission: String): Boolean

    /**
     * 检查多个权限是否已授予
     * @param permissions 权限列表
     * @return 是否全部已授予
     */
    fun hasPermissions(permissions: List<String>): Boolean

    /**
     * 请求权限
     * @param permission 权限名称
     * @return 权限请求结果流
     */
    fun requestPermission(permission: String): Flow<PermissionResult>

    /**
     * 请求多个权限
     * @param permissions 权限列表
     * @return 权限请求结果流
     */
    fun requestPermissions(permissions: List<String>): Flow<Map<String, PermissionResult>>

    /**
     * 检查是否应该显示权限说明
     * @param permission 权限名称
     * @return 是否应该显示说明
     */
    fun shouldShowRationale(permission: String): Boolean

    /**
     * 检查是否有通话权限
     * @return 是否有通话权限
     */
    fun hasCallPermission(): Boolean = hasPermissions(
        listOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.CALL_PHONE
        )
    )
}

/**
 * 权限请求结果
 */
data class PermissionResult(
    val permission: String,
    val granted: Boolean,
    val shouldShowRationale: Boolean
)
