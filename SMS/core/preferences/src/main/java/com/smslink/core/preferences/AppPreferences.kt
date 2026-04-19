package com.smslink.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * 应用偏好设置
 * 管理首次启动、主题设置、通知过滤、文件传输设置等
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 通用设置
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // 通知过滤设置
        private val KEY_NOTIFICATION_FILTER_MODE = stringPreferencesKey("notification_filter_mode")
        private val KEY_NOTIFICATION_FILTER_APPS = stringSetPreferencesKey("notification_filter_apps")
        private val KEY_NOTIFICATION_SOUND_ENABLED = booleanPreferencesKey("notification_sound_enabled")
        private val KEY_NOTIFICATION_VIBRATION_ENABLED = booleanPreferencesKey("notification_vibration_enabled")

        // 文件传输设置
        private val KEY_FILE_SAVE_PATH = stringPreferencesKey("file_save_path")
        private val KEY_FILE_WIFI_ONLY = booleanPreferencesKey("file_wifi_only")
        private val KEY_FILE_AUTO_ACCEPT = booleanPreferencesKey("file_auto_accept")
    }

    // ==================== 通用设置 ====================

    /**
     * 是否应该显示引导流程
     */
    val shouldShowOnboarding: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        !(prefs[KEY_ONBOARDING_COMPLETED] ?: false)
    }

    /**
     * 标记引导流程已完成
     */
    suspend fun markOnboardingCompleted() {
        context.appDataStore.edit { it[KEY_ONBOARDING_COMPLETED] = true }
    }

    /**
     * 获取主题模式
     */
    val themeMode: Flow<ThemeMode> = context.appDataStore.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    /**
     * 设置主题模式
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.appDataStore.edit {
            it[KEY_THEME_MODE] = when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.System -> "system"
            }
        }
    }

    /**
     * 设置深色主题（兼容旧接口）
     */
    suspend fun setDarkTheme(enabled: Boolean) {
        setThemeMode(if (enabled) ThemeMode.Dark else ThemeMode.Light)
    }

    // ==================== 通知过滤设置 ====================

    /**
     * 获取通知过滤模式
     */
    val notificationFilterMode: Flow<NotificationFilterMode> = context.appDataStore.data.map { prefs ->
        when (prefs[KEY_NOTIFICATION_FILTER_MODE]) {
            "whitelist" -> NotificationFilterMode.Whitelist
            "blacklist" -> NotificationFilterMode.Blacklist
            else -> NotificationFilterMode.None
        }
    }

    /**
     * 设置通知过滤模式
     */
    suspend fun setNotificationFilterMode(mode: NotificationFilterMode) {
        context.appDataStore.edit {
            it[KEY_NOTIFICATION_FILTER_MODE] = when (mode) {
                NotificationFilterMode.None -> "none"
                NotificationFilterMode.Whitelist -> "whitelist"
                NotificationFilterMode.Blacklist -> "blacklist"
            }
        }
    }

    /**
     * 获取通知过滤应用列表
     */
    val notificationFilterApps: Flow<Set<String>> = context.appDataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_FILTER_APPS] ?: emptySet()
    }

    /**
     * 设置通知过滤应用列表
     */
    suspend fun setNotificationFilterApps(apps: Set<String>) {
        context.appDataStore.edit {
            it[KEY_NOTIFICATION_FILTER_APPS] = apps
        }
    }

    /**
     * 添加应用到过滤列表
     */
    suspend fun addAppToFilter(packageName: String) {
        context.appDataStore.edit { prefs ->
            val currentApps = prefs[KEY_NOTIFICATION_FILTER_APPS] ?: emptySet()
            prefs[KEY_NOTIFICATION_FILTER_APPS] = currentApps + packageName
        }
    }

    /**
     * 从过滤列表移除应用
     */
    suspend fun removeAppFromFilter(packageName: String) {
        context.appDataStore.edit { prefs ->
            val currentApps = prefs[KEY_NOTIFICATION_FILTER_APPS] ?: emptySet()
            prefs[KEY_NOTIFICATION_FILTER_APPS] = currentApps - packageName
        }
    }

    /**
     * 是否启用通知声音
     */
    val notificationSoundEnabled: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_SOUND_ENABLED] ?: true
    }

    /**
     * 设置通知声音
     */
    suspend fun setNotificationSoundEnabled(enabled: Boolean) {
        context.appDataStore.edit {
            it[KEY_NOTIFICATION_SOUND_ENABLED] = enabled
        }
    }

    /**
     * 是否启用通知振动
     */
    val notificationVibrationEnabled: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_VIBRATION_ENABLED] ?: true
    }

    /**
     * 设置通知振动
     */
    suspend fun setNotificationVibrationEnabled(enabled: Boolean) {
        context.appDataStore.edit {
            it[KEY_NOTIFICATION_VIBRATION_ENABLED] = enabled
        }
    }

    // ==================== 文件传输设置 ====================

    /**
     * 获取文件保存路径
     */
    val fileSavePath: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FILE_SAVE_PATH] ?: "/storage/emulated/0/Download/SMS-Link"
    }

    /**
     * 设置文件保存路径
     */
    suspend fun setFileSavePath(path: String) {
        context.appDataStore.edit {
            it[KEY_FILE_SAVE_PATH] = path
        }
    }

    /**
     * 是否仅 WiFi 传输
     */
    val fileWifiOnly: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FILE_WIFI_ONLY] ?: true
    }

    /**
     * 设置仅 WiFi 传输
     */
    suspend fun setFileWifiOnly(enabled: Boolean) {
        context.appDataStore.edit {
            it[KEY_FILE_WIFI_ONLY] = enabled
        }
    }

    /**
     * 是否自动接收文件
     */
    val fileAutoAccept: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FILE_AUTO_ACCEPT] ?: false
    }

    /**
     * 设置自动接收文件
     */
    suspend fun setFileAutoAccept(enabled: Boolean) {
        context.appDataStore.edit {
            it[KEY_FILE_AUTO_ACCEPT] = enabled
        }
    }
}

/**
 * 主题模式
 */
enum class ThemeMode {
    Light,
    Dark,
    System;

    fun isDarkMode(isSystemDark: Boolean = false): Boolean {
        return when (this) {
            Light -> false
            Dark -> true
            System -> isSystemDark
        }
    }
}

/**
 * 通知过滤模式
 */
enum class NotificationFilterMode {
    None,       // 不过滤
    Whitelist,  // 白名单模式（仅同步选中的应用）
    Blacklist   // 黑名单模式（屏蔽选中的应用）
}

