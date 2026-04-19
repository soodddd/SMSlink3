package com.smslink.ui.bridge

import android.content.Context
import com.smslink.core.model.DeviceInfo
import com.smslink.feature.device.DeviceConnectionState
import com.smslink.feature.device.DeviceManager
import com.smslink.feature.device.DeviceRole
import com.smslink.feature.device.PairingState
import kotlinx.coroutines.flow.Flow

class DeviceBridge(
    private val context: Context,
    private val deviceManager: DeviceManager
) {
    fun getCurrentRole(): Flow<DeviceRole> = deviceManager.currentRole

    fun getConnectionState(): Flow<DeviceConnectionState> = deviceManager.connectionState

    fun getPairedDevices(): Flow<List<DeviceInfo>> = deviceManager.getPairedDevices()

    fun getPairingState(): Flow<PairingState> = deviceManager.pairingState

    suspend fun switchRole(newRole: DeviceRole) {
        deviceManager.switchRole(newRole)
    }

    suspend fun generatePairingCode(): String = deviceManager.generatePairingCode()

    fun enterPairingCode(code: String) {
        deviceManager.enterPairingCode(code)
    }

    fun cancelPairing() {
        deviceManager.cancelPairing()
    }

    fun disconnectDevice(deviceId: String) {
        deviceManager.disconnectDevice(deviceId)
    }

    suspend fun unpairDevice(deviceId: String) {
        deviceManager.unpairDevice(deviceId)
    }

    fun startDiscovery() {
        deviceManager.startDiscovery()
    }

    fun stopDiscovery() {
        deviceManager.stopDiscovery()
    }

    fun getDiscoveredDevices(): Flow<List<DeviceInfo>> = deviceManager.discoveredDevices

    suspend fun connectToDevice(deviceId: String) {
        deviceManager.connectToDevice(deviceId)
    }
}
