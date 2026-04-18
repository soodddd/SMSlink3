package com.smslink.sms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 短信权限辅助类
 * 处理短信相关权限的请求和检查
 */
@Singleton
class SmsPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {

    /**
     * 检查是否拥有所有必需的短信权限
     */
    fun hasAllSmsPermissions(): Boolean {
        return hasReadSmsPermission() &&
                hasSendSmsPermission() &&
                hasReceiveSmsPermission()
    }

    /**
     * 检查是否有读取短信权限
     */
    fun hasReadSmsPermission(): Boolean {
        return hasPermission(Manifest.permission.READ_SMS)
    }

    /**
     * 检查是否有发送短信权限
     */
    fun hasSendSmsPermission(): Boolean {
        return hasPermission(Manifest.permission.SEND_SMS)
    }

    /**
     * 检查是否有接收短信权限
     */
    fun hasReceiveSmsPermission(): Boolean {
        return hasPermission(Manifest.permission.RECEIVE_SMS)
    }

    /**
     * 请求所有短信权限
     */
    fun requestSmsPermissions(activity: Activity, requestCode: Int) {
        val permissions = mutableListOf<String>()

        if (!hasReadSmsPermission()) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (!hasSendSmsPermission()) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        if (!hasReceiveSmsPermission()) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }

        if (permissions.isNotEmpty()) {
            logger.i(TAG, "Requesting SMS permissions: $permissions")
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                requestCode
            )
        } else {
            logger.d(TAG, "All SMS permissions already granted")
        }
    }

    /**
     * 检查权限请求结果
     */
    fun onPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
        expectedRequestCode: Int
    ): PermissionResult {
        if (requestCode != expectedRequestCode) {
            return PermissionResult.NotHandled
        }

        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()

        permissions.forEachIndexed { index, permission ->
            if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission)
            } else {
                denied.add(permission)
            }
        }

        logger.i(TAG, "Permissions granted: $granted, denied: $denied")

        return when {
            denied.isEmpty() -> PermissionResult.AllGranted
            granted.isEmpty() -> PermissionResult.AllDenied
            else -> PermissionResult.PartiallyGranted(granted, denied)
        }
    }

    /**
     * 检查是否应该显示权限说明
     */
    fun shouldShowRequestPermissionRationale(activity: Activity): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.READ_SMS
        ) || ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.SEND_SMS
        ) || ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.RECEIVE_SMS
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "SmsPermissionHelper"
        const val SMS_PERMISSION_REQUEST_CODE = 1001
    }
}

/**
 * 权限请求结果
 */
sealed class PermissionResult {
    object NotHandled : PermissionResult()
    object AllGranted : PermissionResult()
    object AllDenied : PermissionResult()
    data class PartiallyGranted(
        val granted: List<String>,
        val denied: List<String>
    ) : PermissionResult()
}
