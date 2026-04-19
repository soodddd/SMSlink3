package com.smslink.ui.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * 检查通知监听权限是否已授予
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }

    /**
     * 打开通知监听设置页面
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 检查发送通知权限（Android 13+）
     */
    fun hasPostNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 以下默认有权限
            true
        }
    }

    /**
     * 检查所有通知相关权限
     */
    fun hasAllNotificationPermissions(context: Context): Boolean {
        return isNotificationListenerEnabled(context) && hasPostNotificationPermission(context)
    }

    /**
     * 检查电话权限
     */
    fun hasPhonePermissions(context: Context): Boolean {
        val readPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val callPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val readCallLog = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        return readPhoneState && callPhone && readCallLog
    }

    /**
     * 检查存储权限
     */
    fun hasStoragePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用细粒度权限
            val readImages = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED

            val readVideo = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED

            val readAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            readImages && readVideo && readAudio
        } else {
            // Android 13 以下使用传统权限
            val readExternal = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val writeExternal = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            readExternal && writeExternal
        }
    }

    /**
     * 检查位置权限（WiFi 扫描需要）
     */
    fun hasLocationPermissions(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    /**
     * 检查 WiFi 权限
     */
    fun hasWifiPermissions(context: Context): Boolean {
        val changeWifiState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val accessWifiState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return changeWifiState && accessWifiState
    }

    /**
     * 检查录音权限
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 获取所有需要的权限列表
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        return permissions
    }

    /**
     * 获取必需的权限列表（应用启动时必须授予）
     */
    fun getEssentialPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    /**
     * 检查是否所有必需权限都已授予
     */
    fun hasAllEssentialPermissions(context: Context): Boolean {
        return getEssentialPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 打开应用设置页面
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 获取权限的友好名称
     */
    fun getPermissionName(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "精确位置"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "大致位置"
            Manifest.permission.CHANGE_WIFI_STATE -> "WiFi 控制"
            Manifest.permission.ACCESS_WIFI_STATE -> "WiFi 状态"
            Manifest.permission.READ_PHONE_STATE -> "电话状态"
            Manifest.permission.CALL_PHONE -> "拨打电话"
            Manifest.permission.READ_CALL_LOG -> "通话记录"
            Manifest.permission.RECORD_AUDIO -> "录音"
            Manifest.permission.CAMERA -> "相机"
            Manifest.permission.POST_NOTIFICATIONS -> "通知"
            Manifest.permission.READ_MEDIA_IMAGES -> "图片"
            Manifest.permission.READ_MEDIA_VIDEO -> "视频"
            Manifest.permission.READ_MEDIA_AUDIO -> "音频"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "存储读取"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "存储写入"
            else -> permission
        }
    }

    /**
     * 获取权限的说明
     */
    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> "用于 WiFi 扫描和设备发现"
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_WIFI_STATE -> "用于创建热点和连接设备"
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG -> "用于通话功能"
            Manifest.permission.RECORD_AUDIO -> "用于通话音频传输"
            Manifest.permission.CAMERA -> "用于拍照发送"
            Manifest.permission.POST_NOTIFICATIONS -> "用于显示同步通知"
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "用于文件传输"
            else -> ""
        }
    }
}

