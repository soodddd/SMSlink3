package com.smslink.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认电话应用引导
 * 检查和引导用户设置应用为默认电话应用
 */
@Singleton
class DefaultPhoneAppGuide @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {

    /**
     * 检查是否为默认电话应用
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun isDefaultPhoneApp(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val defaultDialerPackage = telecomManager?.defaultDialerPackage
                val isDefault = defaultDialerPackage == context.packageName
                logger.d(TAG, "Is default phone app: $isDefault (current: $defaultDialerPackage)")
                isDefault
            } else {
                logger.w(TAG, "Default phone app check not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to check default phone app", e)
            false
        }
    }

    /**
     * 请求设置为默认电话应用
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun requestDefaultPhoneApp(): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                logger.i(TAG, "Created intent to request default phone app")
                intent
            } else {
                logger.w(TAG, "Request default phone app not supported on this Android version")
                null
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to create request intent", e)
            null
        }
    }

    /**
     * 打开默认应用设置页面
     */
    fun openDefaultAppSettings(): Intent? {
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            logger.i(TAG, "Created intent to open default app settings")
            intent
        } catch (e: Exception) {
            logger.e(TAG, "Failed to create settings intent", e)
            null
        }
    }

    /**
     * 获取为什么需要默认电话应用权限的说明
     */
    fun getPermissionRationale(): String {
        return """
            为了实现通话控制功能，SMS Link 需要设置为默认电话应用。

            这将允许应用：
            • 接听来电
            • 挂断通话
            • 静音/取消静音
            • 保持/恢复通话
            • 跨设备控制通话

            您可以随时在系统设置中更改默认电话应用。
            SMS Link 不会干扰您的正常通话功能。
        """.trimIndent()
    }

    /**
     * 获取简短说明
     */
    fun getShortRationale(): String {
        return "需要设置为默认电话应用才能实现接听/挂断等通话控制功能"
    }

    /**
     * 检查是否支持默认电话应用功能
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * 获取最低支持版本说明
     */
    fun getMinVersionMessage(): String {
        return "通话控制功能需要 Android 6.0 (API 23) 或更高版本"
    }

    companion object {
        private const val TAG = "DefaultPhoneAppGuide"
    }
}
