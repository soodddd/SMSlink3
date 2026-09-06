package com.smslink.device

import android.content.Context
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.core.model.PairResult
import com.smslink.network.IConnectionManager
import com.smslink.security.DeviceIdentityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 璁惧绠＄悊鍣ㄥ疄鐜? * 鏁村悎璁惧鍙戠幇銆侀厤瀵瑰拰鏁版嵁绠＄悊鍔熻兘
 */
@Singleton
class DeviceManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceDiscovery: DeviceDiscoveryImpl,
    private val devicePairing: DevicePairingImpl,
    private val deviceRepository: DeviceRepository,
    private val logger: ILogger,
    private val identityStore: DeviceIdentityStore,
    private val connectionManager: dagger.Lazy<IConnectionManager>
) : IDeviceManager {

    /** Compatibility constructor retained for existing JVM tests and callers. */
    constructor(
        context: Context,
        deviceDiscovery: DeviceDiscoveryImpl,
        devicePairing: DevicePairingImpl,
        deviceRepository: DeviceRepository,
        logger: ILogger
    ) : this(
        context,
        deviceDiscovery,
        devicePairing,
        deviceRepository,
        logger,
        DeviceIdentityStore.forTests(context),
        FixedLazy(NoopConnectionManager)
    )

    companion object {
        private const val TAG = "DeviceManager"
        private const val DEVICE_PREFERENCES = "smslink_device"
        private const val LOCAL_ROLE_KEY = "local_role"
    }

    private val localDeviceState = MutableStateFlow<Device?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE)

    init {
        // Initialize the cached local identity once. The identity key is
        // persisted by DeviceIdentityStore and the role is persisted here.
        localDeviceState.value = createLocalDevice()
    }

    /**
     * 寮€濮嬭澶囧彂鐜?     */
    override fun startDiscovery() {
        logger.i(TAG, "Starting device discovery")
        val device = localDeviceState.value ?: createLocalDevice()
        scope.launch {
            deviceDiscovery.startDiscovery(device)
        }
    }

    /**
     * 鍋滄璁惧鍙戠幇
     */
    override fun stopDiscovery() {
        logger.i(TAG, "Stopping device discovery")
        scope.launch {
            deviceDiscovery.stopDiscovery()
        }
    }

    /**
     * 閰嶅璁惧
     */
    override fun pairDevice(deviceId: String, qrCode: String): Flow<PairResult> {
        logger.i(TAG, "Pairing device: $deviceId")
        logger.d(
            TAG,
            "Pairing lookup snapshot: requestedId=$deviceId, knownIds=${deviceDiscovery.discoveredDevices.value.joinToString { it.device.id }}"
        )

        // Resolve the discovered device by ID
        val discoveredDevice = deviceDiscovery.discoveredDevices.value
            .find { it.device.id == deviceId }?.device

        if (discoveredDevice == null) {
            return kotlinx.coroutines.flow.flow {
                emit(PairResult(false, "Device not found"))
            }
        }

        return devicePairing.pairDevice(discoveredDevice, deviceDiscovery.discoveredDevices.value
            .find { it.device.id == deviceId }, qrCode)
    }

    /**
     * 鑾峰彇宸茶繛鎺ョ殑璁惧鍒楄〃
     */
    override fun getConnectedDevices(): Flow<List<Device>> {
        return deviceRepository.getConnectedDevices()
    }

    override fun getLiveConnectedDevices(): Flow<List<Device>> {
        return deviceRepository.getActuallyConnectedDevices()
    }

    /**
     * 璁剧疆璁惧瑙掕壊
     */
    override suspend fun setDeviceRole(deviceId: String, role: DeviceRole) {
        logger.i(TAG, "Setting device role: $deviceId -> $role")
        deviceRepository.updateDeviceRole(deviceId, role)

        val currentLocalDevice = localDeviceState.value
        if (currentLocalDevice?.id == deviceId) {
            localDeviceState.value = currentLocalDevice.copy(role = role)
            preferences.edit().putString(LOCAL_ROLE_KEY, role.name).apply()
            enforceExclusiveRole(deviceId, role)
        } else {
            reconcileLocalRole(role)
            reconcileLocalRoleForRemoteAssignment(currentLocalDevice, role)
            enforceExclusiveRole(deviceId, role)
        }
    }

    /**
     * 绉婚櫎璁惧
     */
    override suspend fun removeDevice(deviceId: String) {
        logger.i(TAG, "Removing device: $deviceId")
        if (deviceId.isBlank() || deviceId == getLocalDevice().id) {
            logger.w(TAG, "Refusing to remove invalid or local device id: $deviceId")
            return
        }

        // Stop reconnects and close the live link before deleting the row.
        // Cleanup is best-effort so a stale database row cannot keep secrets
        // around merely because a socket was already broken.
        try {
            connectionManager.get().disconnect(deviceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to disconnect removed device: $deviceId: ${e.message}")
        }
        try {
            devicePairing.removeDeviceSecrets(deviceId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to remove secrets for device: $deviceId: ${e.message}")
        }
        deviceRepository.deleteDevice(deviceId)
    }

    /**
     * 鑾峰彇鏈湴璁惧淇℃伅
     */
    override fun getLocalDevice(): Device {
        return localDeviceState.value ?: createLocalDevice().also {
            localDeviceState.value = it
        }
    }

    /**
     * 鍒涘缓鏈湴璁惧淇℃伅
     */
    private fun createLocalDevice(): Device {
        val deviceId = identityStore.deviceId()

        val deviceName = android.os.Build.MODEL ?: "Unknown Device"

        // Simplified device type detection
        val deviceType = when {
            isTablet() -> DeviceType.TABLET
            isFoldable() -> DeviceType.FOLDABLE
            else -> DeviceType.PHONE
        }

        val role = preferences.getString(LOCAL_ROLE_KEY, DeviceRole.MAIN.name)
            ?.let { value -> runCatching { DeviceRole.valueOf(value) }.getOrNull() }
            ?: DeviceRole.MAIN

        return Device(
            id = deviceId,
            name = deviceName,
            type = deviceType,
            role = role,
            publicKey = identityStore.publicKeyBase64(),
            lastSeen = System.currentTimeMillis(),
            isPaired = true
        ).also {
            localDeviceState.value = it
        }
    }

    /**
     * Ensure the cached local device mirrors a promoted remote role.
     */
    private fun reconcileLocalRole(role: DeviceRole) {
        val currentLocalDevice = localDeviceState.value ?: return
        if (role == DeviceRole.MAIN || role == DeviceRole.CELLULAR_SOURCE) {
            if (currentLocalDevice.role == role) {
                localDeviceState.value = currentLocalDevice.copy(role = DeviceRole.SECONDARY)
                preferences.edit().putString(LOCAL_ROLE_KEY, DeviceRole.SECONDARY.name).apply()
            }
        }
    }

    /**
     * When a remote device takes an exclusive role, keep the local device on SECONDARY.
     */
    private fun reconcileLocalRoleForRemoteAssignment(
        currentLocalDevice: Device?,
        role: DeviceRole
    ) {
        if (currentLocalDevice == null) {
            return
        }

        if (role != DeviceRole.MAIN && role != DeviceRole.CELLULAR_SOURCE) {
            return
        }

        if (currentLocalDevice.role != DeviceRole.SECONDARY) {
            localDeviceState.value = currentLocalDevice.copy(role = DeviceRole.SECONDARY)
            preferences.edit().putString(LOCAL_ROLE_KEY, DeviceRole.SECONDARY.name).apply()
        }
    }

    /**
     * Keep MAIN/CELLULAR_SOURCE unique across paired devices.
     */
    private suspend fun enforceExclusiveRole(targetDeviceId: String, role: DeviceRole) {
        if (role != DeviceRole.MAIN && role != DeviceRole.CELLULAR_SOURCE) {
            return
        }

        val pairedDevices = deviceRepository.getAllPairedDevices().first()
        pairedDevices
            .asSequence()
            .filter { it.id != targetDeviceId && it.role == role }
            .forEach { conflictingDevice ->
                deviceRepository.updateDeviceRole(conflictingDevice.id, DeviceRole.SECONDARY)
            }
    }

    /**
     * 鍒ゆ柇鏄惁涓哄钩鏉?     */
    private fun isTablet(): Boolean {
        val configuration = context.resources.configuration
        val screenLayout = configuration.screenLayout and
            android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
        return screenLayout >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    /**
     * 鍒ゆ柇鏄惁涓烘姌鍙犲睆
     */
    private fun isFoldable(): Boolean {
        // Simplified foldable detection
        val model = android.os.Build.MODEL.orEmpty()
        return model.contains("Fold", ignoreCase = true) ||
               model.contains("Flip", ignoreCase = true)
    }

    /**
     * 鑾峰彇鍙戠幇鐨勮澶囧垪琛?     */
    fun getDiscoveredDevices(): Flow<List<DiscoveredDevice>> {
        return deviceDiscovery.discoveredDevices
    }

    /**
     * 鑾峰彇鎵€鏈夊凡閰嶅璁惧
     */
    fun getAllPairedDevices(): Flow<List<Device>> {
        return deviceRepository.getAllPairedDevices()
    }

    /**
     * 鐢熸垚閰嶅浜岀淮鐮?     */
    fun generatePairQRCode(): String {
        val device = getLocalDevice()
        return devicePairing.generatePairQRCode(device.id)
    }

    /**
     * 鎺ユ敹閰嶅璇锋眰
     */
    fun receivePairRequest(deviceId: String, deviceName: String): String {
        return devicePairing.receivePairRequest(deviceId, deviceName)
    }

    /**
     * 鎺ュ彈閰嶅璇锋眰
     */
    fun acceptPairRequest(requestId: String): Flow<PairResult> {
        return devicePairing.acceptPairRequest(requestId)
    }

    /**
     * 鎷掔粷閰嶅璇锋眰
     */
    fun rejectPairRequest(requestId: String) {
        devicePairing.rejectPairRequest(requestId)
    }

    /**
     * 鑾峰彇寰呭鐞嗙殑閰嶅璇锋眰
     */
    fun getPendingPairRequests(): List<PairRequest> {
        return devicePairing.getPendingRequests()
    }

    /**
     * 娓呯悊璧勬簮
     */
    fun cleanup() {
        deviceDiscovery.cleanup()
        scope.cancel()
    }
}

private object NoopConnectionManager : IConnectionManager {
    override fun connect(
        deviceId: String,
        type: com.smslink.core.model.ConnectionType
    ): Flow<com.smslink.core.model.Connection> = emptyFlow()

    override suspend fun disconnect(deviceId: String) = Unit

    override fun getConnectionState(deviceId: String): Flow<com.smslink.core.model.Connection> = emptyFlow()

    override fun getActiveConnections(): Flow<List<com.smslink.core.model.Connection>> = emptyFlow()

    override suspend fun sendData(deviceId: String, data: ByteArray): Boolean = false

    override fun receiveData(): Flow<Pair<String, ByteArray>> = emptyFlow()
}

private class FixedLazy<T>(private val value: T) : dagger.Lazy<T> {
    override fun get(): T = value
}


