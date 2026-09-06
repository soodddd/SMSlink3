package com.smslink.call

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.call.model.CallAction
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallLog
import com.smslink.core.model.CallState
import com.smslink.core.model.Device
import com.smslink.core.permission.IPermissionManager
import com.smslink.device.IDeviceManager
import com.smslink.device.observeLiveConnections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: ICallManager,
    private val callRepository: CallRepository,
    private val permissionManager: IPermissionManager,
    private val deviceManager: IDeviceManager? = null,
    private val defaultPhoneAppGuide: DefaultPhoneAppGuide? = null,
    private val logger: ILogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()
    private val _currentCallState = MutableStateFlow<CallState?>(null)
    val currentCallState: StateFlow<CallState?> = _currentCallState.asStateFlow()
    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()
    private val allCallLogs = MutableStateFlow<List<CallLog>>(emptyList())
    private val selectedCallType = MutableStateFlow<CallDirection?>(null)

    init {
        observeCallState()
        loadCallHistory()
        observeConnectedDevices()
        checkDefaultPhoneApp()
    }

    private fun observeCallState() {
        viewModelScope.launch {
            callManager.getCallState()
                .catch { e -> logger.e(TAG, "Error observing call state", e) }
                .collect { _currentCallState.value = it }
        }
    }

    fun loadCallHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            callRepository.getAllCallLogs()
                .catch { e ->
                    logger.e(TAG, "Error loading call history", e)
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load call history: ${e.message}") }
                }
                .collect { callLogs ->
                    allCallLogs.value = callLogs
                    val filtered = selectedCallType.value?.let { type ->
                        callLogs.filter { it.type == type }
                    } ?: callLogs
                    _uiState.update { it.copy(isLoading = false, callLogs = filtered, error = null) }
                }
        }
    }

    fun syncCallHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            callRepository.syncFromSystem().fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isSyncing = false, syncMessage = "Synced $count call logs") }
                },
                onFailure = { e ->
                    logger.e(TAG, "Failed to sync call history", e)
                    _uiState.update { it.copy(isSyncing = false, error = "Sync failed: ${e.message}") }
                }
            )
        }
    }

    fun makeCall(phoneNumber: String) {
        viewModelScope.launch {
            if (!permissionManager.hasCallPermission()) {
                _uiState.update { it.copy(error = "Call permission required") }
                return@launch
            }
            if (!callManager.makeCall(phoneNumber)) {
                _uiState.update { it.copy(error = "Call failed") }
            }
        }
    }

    fun answerCall(callId: String) {
        viewModelScope.launch {
            if (!permissionManager.hasCallPermission()) {
                _uiState.update { it.copy(error = "Call permission required") }
                return@launch
            }
            if (!callManager.answerCall(callId)) {
                _uiState.update { it.copy(error = "Answer call failed") }
            }
        }
    }

    fun endCall(callId: String) {
        viewModelScope.launch {
            if (!permissionManager.hasCallPermission()) {
                _uiState.update { it.copy(error = "Call permission required") }
                return@launch
            }
            if (!callManager.endCall(callId)) {
                _uiState.update { it.copy(error = "End call failed") }
            }
        }
    }

    fun deleteCallLog(callLog: CallLog) {
        viewModelScope.launch {
            try {
                callRepository.deleteCallLog(callLog)
            } catch (e: Exception) {
                logger.e(TAG, "Failed to delete call log", e)
                _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSyncMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }

    fun clearCallHistory() {
        viewModelScope.launch {
            callRepository.clearAllCallLogs()
            allCallLogs.value = emptyList()
            _uiState.update { it.copy(callLogs = emptyList()) }
        }
    }

    fun filterByType(type: CallDirection) {
        selectedCallType.value = type
        _uiState.update { it.copy(callLogs = allCallLogs.value.filter { log -> log.type == type }) }
    }

    fun startListening() = callManager.startListening()

    fun stopListening() = callManager.stopListening()

    private fun observeConnectedDevices() {
        val manager = deviceManager ?: return
        viewModelScope.launch {
            manager.observeLiveConnections()
                .catch { e -> logger.e(TAG, "Error observing connected devices", e) }
                .collect { _connectedDevices.value = it }
        }
    }

    fun selectDevice(deviceId: String?) {
        _selectedDeviceId.value = deviceId
        logger.d(TAG, "Selected device: $deviceId")
    }

    fun muteCall(callId: String) {
        viewModelScope.launch {
            if (!checkCallControlPermissions()) return@launch
            val success = callManager.muteCall(callId)
            if (!success) _uiState.update { it.copy(error = "Mute failed") }
        }
    }

    fun unmuteCall(callId: String) {
        viewModelScope.launch {
            if (!checkCallControlPermissions()) return@launch
            val success = callManager.unmuteCall(callId)
            if (!success) _uiState.update { it.copy(error = "Unmute failed") }
        }
    }

    fun holdCall(callId: String) {
        viewModelScope.launch {
            if (!checkCallControlPermissions()) return@launch
            val success = callManager.holdCall(callId)
            if (!success) _uiState.update { it.copy(error = "Hold failed") }
        }
    }

    fun resumeCall(callId: String) {
        viewModelScope.launch {
            if (!checkCallControlPermissions()) return@launch
            val success = callManager.resumeCall(callId)
            if (!success) _uiState.update { it.copy(error = "Resume failed") }
        }
    }

    fun sendRemoteControl(callState: CallState, action: CallAction) {
        viewModelScope.launch {
            val targetDeviceId = _selectedDeviceId.value ?: run {
                _uiState.update { it.copy(error = "Select a target device first") }
                return@launch
            }
            _uiState.update { it.copy(isRemoteControlling = true) }
            val success = callManager.sendCallControl(callState, action, targetDeviceId)
            _uiState.update {
                it.copy(
                    isRemoteControlling = false,
                    error = if (!success) "Remote control failed" else null,
                    remoteControlMessage = if (success) "Remote control command sent" else null
                )
            }
        }
    }

    fun checkDefaultPhoneApp() {
        val guide = defaultPhoneAppGuide ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            _uiState.update { it.copy(isDefaultPhoneApp = guide.isDefaultPhoneApp()) }
        } else {
            _uiState.update { it.copy(isDefaultPhoneApp = false) }
        }
    }

    fun requestDefaultPhoneApp() {
        val guide = defaultPhoneAppGuide ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = guide.requestDefaultPhoneApp()
            if (intent != null) {
                _uiState.update { it.copy(defaultPhoneAppIntent = intent) }
            } else {
                _uiState.update { it.copy(error = "Unable to open default app settings") }
            }
        } else {
            _uiState.update { it.copy(error = guide.getMinVersionMessage()) }
        }
    }

    fun getDefaultPhoneAppRationale(): String = defaultPhoneAppGuide?.getPermissionRationale() ?: ""

    private fun checkCallControlPermissions(): Boolean {
        if (!permissionManager.hasCallPermission()) {
            _uiState.update { it.copy(error = "Call permission required") }
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && defaultPhoneAppGuide?.isDefaultPhoneApp() == false) {
            _uiState.update { it.copy(error = "Set as default phone app") }
            return false
        }
        return true
    }

    fun clearRemoteControlMessage() {
        _uiState.update { it.copy(remoteControlMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val TAG = "CallViewModel"
    }
}

data class CallUiState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isRemoteControlling: Boolean = false,
    val callLogs: List<CallLog> = emptyList(),
    val error: String? = null,
    val syncMessage: String? = null,
    val remoteControlMessage: String? = null,
    val isDefaultPhoneApp: Boolean = false,
    val defaultPhoneAppIntent: android.content.Intent? = null
)
