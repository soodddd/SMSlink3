package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.preferences.AppPreferences
import com.smslink.feature.device.DeviceRole
import com.smslink.ui.bridge.DeviceBridge
import com.smslink.ui.screens.settings.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceBridge: DeviceBridge,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var currentRoleValue: DeviceRole = DeviceRole.UNPAIRED

    init {
        observeRole()
        observeTheme()
    }

    private fun observeRole() {
        viewModelScope.launch {
            deviceBridge.getCurrentRole().collect { role ->
                currentRoleValue = role
                val roleText = when (role) {
                    DeviceRole.PRIMARY -> "主设备"
                    DeviceRole.SECONDARY -> "副设备"
                    DeviceRole.UNPAIRED -> "未配对"
                }
                _uiState.update { it.copy(currentRole = roleText) }
            }
        }
    }

    private fun observeTheme() {
        viewModelScope.launch {
            appPreferences.themeMode
                .map { it.isDarkMode() }
                .collect { isDark ->
                    _uiState.update { it.copy(isDarkTheme = isDark) }
                }
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setDarkTheme(enabled)
        }
    }

    fun toggleRole() {
        viewModelScope.launch {
            val nextRole = when (currentRoleValue) {
                DeviceRole.PRIMARY -> DeviceRole.SECONDARY
                DeviceRole.SECONDARY, DeviceRole.UNPAIRED -> DeviceRole.PRIMARY
            }
            deviceBridge.switchRole(nextRole)
        }
    }
}
