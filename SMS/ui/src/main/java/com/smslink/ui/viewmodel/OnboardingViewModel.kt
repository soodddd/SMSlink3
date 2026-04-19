package com.smslink.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.feature.device.DeviceRole
import com.smslink.ui.bridge.DeviceBridge
import com.smslink.ui.screens.onboarding.OnboardingStep
import com.smslink.ui.utils.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceBridge: DeviceBridge
) : ViewModel() {

    private val _currentStep = MutableStateFlow(OnboardingStep.WELCOME)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private val _selectedRole = MutableStateFlow<DeviceRole?>(null)
    val selectedRole: StateFlow<DeviceRole?> = _selectedRole.asStateFlow()

    fun nextStep() {
        _currentStep.value = when (_currentStep.value) {
            OnboardingStep.WELCOME -> OnboardingStep.ROLE_SELECTION
            OnboardingStep.ROLE_SELECTION -> OnboardingStep.PERMISSIONS
            OnboardingStep.PERMISSIONS -> OnboardingStep.PAIRING
            OnboardingStep.PAIRING -> OnboardingStep.PAIRING
        }
    }

    fun selectRole(role: DeviceRole) {
        _selectedRole.value = role
        viewModelScope.launch {
            deviceBridge.switchRole(role)
        }
        nextStep()
    }

    fun requestNotificationPermissions() {
        if (!PermissionHelper.isNotificationListenerEnabled(context)) {
            PermissionHelper.openNotificationListenerSettings(context)
        }
    }

    fun checkPermissionsAndProceed() {
        viewModelScope.launch {
            val hasPermissions = PermissionHelper.hasAllNotificationPermissions(context)
            if (hasPermissions) {
                nextStep()
            } else {
                requestNotificationPermissions()
            }
        }
    }
}
