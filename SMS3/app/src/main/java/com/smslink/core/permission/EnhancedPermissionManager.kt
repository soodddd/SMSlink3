package com.smslink.core.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 完善的权限管理器
 * 支持运行时权限请求、电池优化、自启动等
 *
 * 使用方式：
 * 1. 在Activity中调用setupPermissionLaunchers()
 * 2. 使用requestPermission()或requestPermissions()请求权限
 * 3. 通过Flow接收权限结果
 */
@Singleton
class EnhancedPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) : IPermissionManager {

    companion object {
        private const val TAG = "EnhancedPermissionManager"
        private const val PERMISSION_RESULT_TIMEOUT_MS = 120_000L
    }

    // Activity Result Launchers（需要从Activity设置）
    private var singlePermissionLauncher: ActivityResultLauncher<String>? = null
    private var multiPermissionLauncher: ActivityResultLauncher<Array<String>>? = null

    // 每次请求只允许一个待处理结果，避免不同业务的权限回调串线。
    private var currentRequestedPermission: String? = null
    private var currentRequestedPermissions: List<String>? = null
    private var pendingSingleResult: CompletableDeferred<PermissionResult>? = null
    private var pendingMultipleResult: CompletableDeferred<Map<String, PermissionResult>>? = null

    /**
     * 在Activity中设置权限启动器
     * 必须在Activity的onCreate中调用
     */
    fun setupPermissionLaunchers(activity: ComponentActivity) {
        logger.d(TAG, "Setting up permission launchers")

        // 单个权限请求
        singlePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val permission = currentRequestedPermission ?: return@registerForActivityResult
            val result = PermissionResult(
                permission = permission,
                granted = granted,
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission)
            )

            logger.d(TAG, "Permission result: $permission = $granted")
            pendingSingleResult?.complete(result)
            currentRequestedPermission = null
        }

        // 多个权限请求
        multiPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val permissions = currentRequestedPermissions ?: return@registerForActivityResult
            val resultMap = permissions.associateWith { permission ->
                PermissionResult(
                    permission = permission,
                    granted = results[permission] ?: false,
                    shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission)
                )
            }

            logger.d(TAG, "Multiple permissions result: ${results.size} permissions")
            pendingMultipleResult?.complete(resultMap)
            currentRequestedPermissions = null
        }
    }

    override fun hasPermission(permission: String): Boolean {
        return try {
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            logger.e(TAG, "Failed to check permission: $permission", e)
            false
        }
    }

    override fun hasPermissions(permissions: List<String>): Boolean {
        return permissions.all { hasPermission(it) }
    }

    override fun requestPermission(permission: String): Flow<PermissionResult> {
        return flow {
            logger.i(TAG, "Requesting permission: $permission")
            if (hasPermission(permission)) {
                emit(PermissionResult(permission, true, false))
                return@flow
            }

            val launcher = singlePermissionLauncher
            if (launcher == null) {
                logger.e(TAG, "Permission launcher not set up. Call setupPermissionLaunchers() first.")
                emit(PermissionResult(permission, false, false))
                return@flow
            }

            if (pendingSingleResult != null || pendingMultipleResult != null) {
                emit(PermissionResult(permission, false, false))
                return@flow
            }

            val deferred = CompletableDeferred<PermissionResult>()
            pendingSingleResult = deferred
            currentRequestedPermission = permission
            try {
                withContext(Dispatchers.Main.immediate) { launcher.launch(permission) }
                emit(
                    withTimeoutOrNull(PERMISSION_RESULT_TIMEOUT_MS) { deferred.await() }
                        ?: PermissionResult(permission, false, false)
                )
            } finally {
                if (pendingSingleResult === deferred) pendingSingleResult = null
                currentRequestedPermission = null
            }
        }
    }

    override fun requestPermissions(permissions: List<String>): Flow<Map<String, PermissionResult>> {
        return flow {
            logger.i(TAG, "Requesting ${permissions.size} permissions")
            if (permissions.isEmpty()) {
                emit(emptyMap())
                return@flow
            }

            val deniedPermissions = permissions.filterNot(::hasPermission)
            if (deniedPermissions.isEmpty()) {
                emit(permissions.associateWith { PermissionResult(it, true, false) })
                return@flow
            }

            val launcher = multiPermissionLauncher
            if (launcher == null) {
                logger.e(TAG, "Permission launcher not set up. Call setupPermissionLaunchers() first.")
                emit(permissions.associateWith { PermissionResult(it, false, false) })
                return@flow
            }

            if (pendingSingleResult != null || pendingMultipleResult != null) {
                emit(permissions.associateWith { PermissionResult(it, false, false) })
                return@flow
            }

            val deferred = CompletableDeferred<Map<String, PermissionResult>>()
            pendingMultipleResult = deferred
            currentRequestedPermissions = permissions
            try {
                withContext(Dispatchers.Main.immediate) { launcher.launch(deniedPermissions.toTypedArray()) }
                val result = withTimeoutOrNull(PERMISSION_RESULT_TIMEOUT_MS) { deferred.await() }
                    ?: deniedPermissions.associateWith { PermissionResult(it, false, false) }
                emit(
                    permissions.associateWith { permission ->
                        result[permission] ?: PermissionResult(permission, hasPermission(permission), false)
                    }
                )
            } finally {
                if (pendingMultipleResult === deferred) pendingMultipleResult = null
                currentRequestedPermissions = null
            }
        }
    }

    override fun shouldShowRationale(permission: String): Boolean {
        // 需要Activity实例，这里返回false
        // 实际使用时应该在Activity中调用shouldShowRequestPermissionRationale
        return false
    }

    /**
     * 检查是否忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    /**
     * 请求忽略电池优化
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    activity.startActivity(intent)
                    logger.i(TAG, "Requested battery optimization exemption")
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to request battery optimization", e)
                    // 回退到设置页面
                    openBatteryOptimizationSettings(activity)
                }
            }
        }
    }

    /**
     * 打开电池优化设置页面
     */
    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            activity.startActivity(intent)
            logger.i(TAG, "Opened battery optimization settings")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open battery optimization settings", e)
        }
    }

    /**
     * 打开自启动设置（厂商特定）
     */
    fun openAutoStartSettings(activity: Activity) {
        try {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val intent = when {
                manufacturer.contains("xiaomi") -> {
                    Intent().apply {
                        setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }
                }
                manufacturer.contains("oppo") -> {
                    Intent().apply {
                        setClassName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    }
                }
                manufacturer.contains("vivo") -> {
                    Intent().apply {
                        setClassName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    }
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    Intent().apply {
                        setClassName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    }
                }
                else -> {
                    // 通用设置页面
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }

            activity.startActivity(intent)
            logger.i(TAG, "Opened auto-start settings for $manufacturer")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open auto-start settings", e)
            // 回退到应用详情页面
            openAppDetailsSettings(activity)
        }
    }

    /**
     * 打开后台限制设置
     */
    fun openBackgroundRestrictionSettings(activity: Activity) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }

            activity.startActivity(intent)
            logger.i(TAG, "Opened background restriction settings")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open background restriction settings", e)
        }
    }

    /**
     * 打开应用详情设置
     */
    fun openAppDetailsSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivity(intent)
            logger.i(TAG, "Opened app details settings")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open app details settings", e)
        }
    }

    /**
     * 打开通知监听设置
     */
    fun openNotificationListenerSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            activity.startActivity(intent)
            logger.i(TAG, "Opened notification listener settings")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to open notification listener settings", e)
        }
    }

    /**
     * 检查通知监听权限
     */
    fun hasNotificationListenerPermission(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners?.contains(context.packageName) == true
    }

    /**
     * 获取权限状态摘要
     */
    fun getPermissionSummary(): PermissionSummary {
        return PermissionSummary(
            hasNotificationListener = hasNotificationListenerPermission(),
            isIgnoringBatteryOptimization = isIgnoringBatteryOptimizations(),
            hasSmsPermissions = hasPermissions(
                listOf(
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.RECEIVE_SMS
                )
            ),
            // Files are selected through SAF or received through a content URI;
            // the app deliberately does not request broad storage access.
            hasStoragePermissions = true,
            hasBluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hasPermissions(
                    listOf(
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_ADVERTISE
                    )
                )
            } else {
                hasPermissions(
                    listOf(
                        android.Manifest.permission.BLUETOOTH,
                        android.Manifest.permission.BLUETOOTH_ADMIN
                    )
                )
            }
        )
    }

}

/**
 * 权限状态摘要
 */
data class PermissionSummary(
    val hasNotificationListener: Boolean,
    val isIgnoringBatteryOptimization: Boolean,
    val hasSmsPermissions: Boolean,
    val hasStoragePermissions: Boolean,
    val hasBluetoothPermissions: Boolean
) {
    fun isAllGranted(): Boolean {
        return hasNotificationListener &&
            isIgnoringBatteryOptimization &&
            hasSmsPermissions &&
            hasStoragePermissions &&
            hasBluetoothPermissions
    }

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!hasNotificationListener) missing.add("通知监听")
        if (!isIgnoringBatteryOptimization) missing.add("电池优化豁免")
        if (!hasSmsPermissions) missing.add("短信权限")
        if (!hasStoragePermissions) missing.add("存储权限")
        if (!hasBluetoothPermissions) missing.add("蓝牙权限")
        return missing
    }
}
