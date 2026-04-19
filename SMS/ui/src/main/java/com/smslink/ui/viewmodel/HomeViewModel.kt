package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.feature.device.DeviceConnectionState
import com.smslink.feature.device.DeviceRole
import com.smslink.ui.bridge.DeviceBridge
import com.smslink.ui.screens.home.DeviceUiModel
import com.smslink.ui.screens.home.HomeUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Home screen ViewModel.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceBridge: DeviceBridge
) : ViewModel() {

    private val pairedDevicesFlow = deviceBridge.getPairedDevices()
    private val connectionStateFlow = deviceBridge.getConnectionState()
    private val roleFlow = deviceBridge.getCurrentRole()

    val uiState: StateFlow<HomeUiState> = combine(
        pairedDevicesFlow,
        connectionStateFlow,
        roleFlow
    ) { devices, connectionState, role ->
        val connectedId = (connectionState as? DeviceConnectionState.Connected)?.deviceId

        HomeUiState(
            isLoading = false,
            connectionState = connectionState.toUiText(),
            currentRole = role.toUiText(),
            pairedDevices = devices.map { device ->
                DeviceUiModel(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    deviceType = device.deviceType.name,
                    isConnected = device.deviceId == connectedId,
                    lastSeenText = formatTimestamp(device.timestamp)
                )
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true)
    )

    fun refresh() {
        viewModelScope.launch {
            deviceBridge.startDiscovery()
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hr ago"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun DeviceConnectionState.toUiText(): String {
        return when (this) {
            is DeviceConnectionState.Connected -> "Connected"
            is DeviceConnectionState.Connecting -> "Connecting"
            is DeviceConnectionState.Disconnected -> "Disconnected"
            is DeviceConnectionState.Failed -> "Connection failed"
        }
    }

    private fun DeviceRole.toUiText(): String {
        return when (this) {
            DeviceRole.PRIMARY -> "Primary"
            DeviceRole.SECONDARY -> "Secondary"
            DeviceRole.UNPAIRED -> "Unpaired"
        }
    }
}
