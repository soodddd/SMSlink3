package com.smslink.ui.settings

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.call.ICallManager
import com.smslink.core.permission.EnhancedPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel
class PermissionGuideViewModel @Inject constructor(
    private val permissionManager: EnhancedPermissionManager,
    private val callManager: ICallManager
) : ViewModel() {
    private val _refresh = MutableStateFlow(0)
    val refresh: StateFlow<Int> = _refresh.asStateFlow()

    fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Keep this only for pre-S BLE implementations that still use
            // the location-gated scanner.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        viewModelScope.launch {
            permissionManager.requestPermissions(permissions).collect {
                // Application.onCreate may have attempted registration before
                // runtime permissions were granted. Retry immediately after
                // the user completes the permission dialog.
                runCatching { callManager.startListening() }
                _refresh.value += 1
            }
        }
    }
}
