package com.smslink.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_prefs")

/**
 * 设备身份偏好设置
 * 持久化设备 ID 和设备名称
 */
@Singleton
class DevicePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
    }

    /**
     * 获取或创建本地设备 ID
     * 如果不存在则生成新的 UUID 并持久化
     */
    suspend fun getOrCreateLocalDeviceId(): String {
        val prefs = context.deviceDataStore.data.first()
        val existingId = prefs[KEY_DEVICE_ID]

        return if (existingId != null) {
            existingId
        } else {
            val newId = UUID.randomUUID().toString()
            context.deviceDataStore.edit { it[KEY_DEVICE_ID] = newId }
            newId
        }
    }

    /**
     * 获取本地设备名称
     * 如果不存在则使用默认名称并持久化
     */
    suspend fun getLocalDeviceName(defaultName: String = android.os.Build.MODEL): String {
        val prefs = context.deviceDataStore.data.first()
        val existingName = prefs[KEY_DEVICE_NAME]

        return if (existingName != null) {
            existingName
        } else {
            context.deviceDataStore.edit { it[KEY_DEVICE_NAME] = defaultName }
            defaultName
        }
    }

    /**
     * 设置本地设备名称
     */
    suspend fun setLocalDeviceName(name: String) {
        context.deviceDataStore.edit { it[KEY_DEVICE_NAME] = name }
    }

    /**
     * 获取本地设备 ID 的 Flow
     */
    fun getLocalDeviceIdFlow() = context.deviceDataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    /**
     * 获取本地设备名称的 Flow
     */
    fun getLocalDeviceNameFlow() = context.deviceDataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: android.os.Build.MODEL
    }

    suspend fun getCurrentRole(): String {
        val prefs = context.deviceDataStore.data.first()
        return prefs[KEY_DEVICE_ROLE] ?: "UNPAIRED"
    }

    suspend fun setCurrentRole(role: String) {
        context.deviceDataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role
        }
    }

    fun getCurrentRoleFlow() = context.deviceDataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ROLE] ?: "UNPAIRED"
    }
}
