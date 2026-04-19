package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.feature.device.PairingState
import com.smslink.ui.bridge.DeviceBridge
import com.smslink.ui.screens.pairing.DiscoveredDeviceUiModel
import com.smslink.ui.screens.pairing.PairingStep
import com.smslink.ui.screens.pairing.PairingUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 配对 ViewModel
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val deviceBridge: DeviceBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        observePairingState()
        observeDiscoveredDevices()
    }

    private fun observePairingState() {
        viewModelScope.launch {
            deviceBridge.getPairingState()
                .collect { state ->
                    when (state) {
                        is PairingState.Idle -> {
                            _uiState.update { it.copy(step = PairingStep.CHOOSE_METHOD) }
                        }
                        is PairingState.WaitingForCode -> {
                            _uiState.update {
                                it.copy(
                                    step = PairingStep.WAITING_FOR_CODE,
                                    pairingCode = state.code
                                )
                            }
                        }
                        is PairingState.Pairing -> {
                            _uiState.update { it.copy(step = PairingStep.PAIRING) }
                        }
                        is PairingState.Success -> {
                            _uiState.update {
                                it.copy(
                                    step = PairingStep.SUCCESS,
                                    selectedDeviceName = state.deviceId
                                )
                            }
                        }
                        is PairingState.Failed -> {
                            _uiState.update {
                                it.copy(
                                    step = PairingStep.FAILED,
                                    error = state.error
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun observeDiscoveredDevices() {
        viewModelScope.launch {
            deviceBridge.getDiscoveredDevices()
                .collect { devices ->
                    val deviceModels = devices.map { device ->
                        DiscoveredDeviceUiModel(
                            deviceId = device.deviceId,
                            deviceName = device.deviceName,
                            deviceType = device.deviceType.name
                        )
                    }
                    _uiState.update {
                        val nextStep = when {
                            deviceModels.isEmpty() && it.step == PairingStep.DISCOVERING -> PairingStep.CHOOSE_METHOD
                            deviceModels.isNotEmpty() &&
                                (it.step == PairingStep.CHOOSE_METHOD ||
                                    (it.step == PairingStep.PAIRING && it.selectedDeviceName == null)) -> PairingStep.DISCOVERING
                            else -> it.step
                        }
                        it.copy(
                            discoveredDevices = deviceModels,
                            step = nextStep
                        )
                    }
                }
        }
    }

    fun generateCode() {
        viewModelScope.launch {
            try {
                deviceBridge.generatePairingCode()
                deviceBridge.startDiscovery()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        step = PairingStep.FAILED,
                        error = e.message ?: "生成配对码失败"
                    )
                }
            }
        }
    }

    fun enterCode(code: String) {
        viewModelScope.launch {
            try {
                deviceBridge.enterPairingCode(code)
                deviceBridge.startDiscovery()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        step = PairingStep.FAILED,
                        error = e.message ?: "配对失败"
                    )
                }
            }
        }
    }

    fun selectDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                val selectedDeviceName = _uiState.value.discoveredDevices
                    .firstOrNull { it.deviceId == deviceId }
                    ?.deviceName
                    ?: deviceId
                _uiState.update {
                    it.copy(
                        step = PairingStep.PAIRING,
                        selectedDeviceName = selectedDeviceName,
                        error = null
                    )
                }
                deviceBridge.connectToDevice(deviceId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        step = PairingStep.FAILED,
                        error = e.message ?: "连接设备失败"
                    )
                }
            }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            deviceBridge.cancelPairing()
            deviceBridge.stopDiscovery()
            _uiState.update { it.copy(step = PairingStep.CHOOSE_METHOD) }
        }
    }
}
