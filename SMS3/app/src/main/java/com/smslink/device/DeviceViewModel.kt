package com.smslink.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.PairResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val deviceManager: DeviceManagerImpl,
    private val logger: ILogger
) : ViewModel() {

    companion object {
        private const val TAG = "DeviceViewModel"
    }

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> =
        deviceManager.getDiscoveredDevices()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val pairedDevices: StateFlow<List<Device>> =
        deviceManager.getAllPairedDevices()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val connectedDevices: StateFlow<List<Device>> =
        deviceManager.getConnectedDevices()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private val _localDevice = MutableStateFlow(deviceManager.getLocalDevice())
    val localDevice: StateFlow<Device> = _localDevice.asStateFlow()

    private val _uiState = MutableStateFlow<DeviceUiState>(DeviceUiState.Idle)
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private val _pairQRCode = MutableStateFlow<String?>(null)
    val pairQRCode: StateFlow<String?> = _pairQRCode.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<PairRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PairRequest>> = _pendingRequests.asStateFlow()

    fun startDiscovery() {
        logger.i(TAG, "Starting discovery from ViewModel")
        _uiState.value = DeviceUiState.Discovering
        deviceManager.startDiscovery()
    }

    fun stopDiscovery() {
        logger.i(TAG, "Stopping discovery from ViewModel")
        deviceManager.stopDiscovery()
        _uiState.value = DeviceUiState.Idle
    }

    fun pairDevice(deviceId: String, qrCode: String) {
        viewModelScope.launch {
            _uiState.value = DeviceUiState.Pairing
            deviceManager.pairDevice(deviceId, qrCode).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Pairing successful")
                    _uiState.value = DeviceUiState.PairSuccess(result.message)
                } else {
                    logger.w(TAG, "Pairing failed: ${result.message}")
                    _uiState.value = DeviceUiState.PairError(result.message)
                }
            }
        }
    }

    fun generatePairQRCode() {
        viewModelScope.launch {
            try {
                val qrCode = deviceManager.generatePairQRCode()
                _pairQRCode.value = qrCode
                logger.d(TAG, "QR code generated")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to generate QR code", e)
                _uiState.value = DeviceUiState.Error("Failed to generate QR code")
            }
        }
    }

    fun acceptPairRequest(requestId: String) {
        viewModelScope.launch {
            _uiState.value = DeviceUiState.Pairing
            deviceManager.acceptPairRequest(requestId).collect { result ->
                if (result.success) {
                    logger.i(TAG, "Pair request accepted")
                    _uiState.value = DeviceUiState.PairSuccess(result.message)
                    refreshPendingRequests()
                } else {
                    logger.w(TAG, "Failed to accept pair request: ${result.message}")
                    _uiState.value = DeviceUiState.PairError(result.message)
                }
            }
        }
    }

    fun rejectPairRequest(requestId: String) {
        viewModelScope.launch {
            deviceManager.rejectPairRequest(requestId)
            refreshPendingRequests()
            logger.i(TAG, "Pair request rejected")
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                deviceManager.removeDevice(deviceId)
                logger.i(TAG, "Device removed: $deviceId")
                _uiState.value = DeviceUiState.DeviceRemoved
            } catch (e: Exception) {
                logger.e(TAG, "Failed to remove device", e)
                _uiState.value = DeviceUiState.Error("Failed to remove device")
            }
        }
    }

    fun setDeviceRole(deviceId: String, role: DeviceRole) {
        viewModelScope.launch {
            try {
                deviceManager.setDeviceRole(deviceId, role)
                _localDevice.value = deviceManager.getLocalDevice()
                logger.i(TAG, "Device role updated: $deviceId -> $role")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to update device role", e)
                _uiState.value = DeviceUiState.Error("Failed to update device role")
            }
        }
    }

    fun refreshPendingRequests() {
        _pendingRequests.value = deviceManager.getPendingPairRequests()
    }

    fun resetUiState() {
        _uiState.value = DeviceUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}

sealed class DeviceUiState {
    object Idle : DeviceUiState()
    object Discovering : DeviceUiState()
    object Pairing : DeviceUiState()
    data class PairSuccess(val message: String) : DeviceUiState()
    data class PairError(val message: String) : DeviceUiState()
    object DeviceRemoved : DeviceUiState()
    data class Error(val message: String) : DeviceUiState()
}
