package com.smslink.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.log.ILogger
import com.smslink.core.model.AppNotification
import com.smslink.core.model.Device
import com.smslink.core.permission.IPermissionManager
import com.smslink.device.IDeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知 ViewModel
 */
@HiltViewModel
class NotificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerImpl,
    private val repository: NotificationRepository,
    private val deviceManager: IDeviceManager,
    private val permissionManager: IPermissionManager,
    private val logger: ILogger
) : ViewModel() {

    private val _uiState = MutableStateFlow<NotificationUiState>(NotificationUiState.Loading)
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _selectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val selectedDevices: StateFlow<Set<String>> = _selectedDevices.asStateFlow()

    private var notificationsLoadJob: Job? = null

    init {
        checkPermissionsAndLoadNotifications()
        observeNewNotifications()
        observeConnectedDevices()
        deviceManager.startDiscovery()
    }

    /**
     * 检查权限并加载通知
     */
    private fun checkPermissionsAndLoadNotifications() {
        viewModelScope.launch {
            if (!hasNotificationPermission()) {
                _uiState.value = NotificationUiState.PermissionRequired
                return@launch
            }

            loadNotifications()
        }
    }

    /**
     * 加载通知列表
     */
    fun loadNotifications() {
        notificationsLoadJob?.cancel()
        notificationsLoadJob = viewModelScope.launch {
            _uiState.value = NotificationUiState.Loading

            try {
                val notificationList = repository.getAllNotifications(100).first()
                _notifications.value = notificationList
                _uiState.value = if (notificationList.isEmpty()) {
                    NotificationUiState.Empty
                } else {
                    NotificationUiState.Success
                }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to load notifications", e)
                _uiState.value = NotificationUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 监听新通知
     */
    private fun observeNewNotifications() {
        viewModelScope.launch {
            notificationManager.getNotifications()
                .catch { e ->
                    logger.e(TAG, "Failed to observe new notifications", e)
                }
                .collect { newNotification ->
                    logger.d(TAG, "New notification received: ${newNotification.appName}")
                    // 刷新通知列表
                    loadNotifications()
                }
        }
    }

    /**
     * 监听已连接设备
     */
    private fun observeConnectedDevices() {
        viewModelScope.launch {
            deviceManager.getConnectedDevices()
                .map { devices -> devices.distinctBy { it.id to it.role } }
                .distinctUntilChangedBy { devices ->
                    devices.map { device -> device.id to device.role }
                }
                .catch { e ->
                    logger.e(TAG, "Failed to observe connected devices", e)
                }
                .collect { devices ->
                    _connectedDevices.value = devices
                    logger.d(TAG, "Connected devices updated: ${devices.size}")
                }
        }
    }

    /**
     * 开始监听通知
     */
    fun startListening() {
        viewModelScope.launch {
            try {
                notificationManager.startListening()
                _isListening.value = true
                logger.i(TAG, "Started listening for notifications")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to start listening", e)
                _uiState.value = NotificationUiState.Error(e.message ?: "Failed to start listening")
            }
        }
    }

    /**
     * 停止监听通知
     */
    fun stopListening() {
        viewModelScope.launch {
            try {
                notificationManager.stopListening()
                _isListening.value = false
                logger.i(TAG, "Stopped listening for notifications")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to stop listening", e)
            }
        }
    }

    /**
     * 清除通知
     */
    fun clearNotification(notificationId: String) {
        viewModelScope.launch {
            try {
                notificationManager.clearNotification(notificationId)
                logger.d(TAG, "Notification cleared: $notificationId")
                loadNotifications()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to clear notification", e)
            }
        }
    }

    /**
     * 同步通知到设备
     */
    fun syncNotification(notification: AppNotification, targetDeviceId: String) {
        viewModelScope.launch {
            try {
                _syncStatus.value = SyncStatus.Syncing
                notificationManager.syncNotification(notification, targetDeviceId)
                _syncStatus.value = SyncStatus.Success
                logger.d(TAG, "Notification synced to device: $targetDeviceId")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to sync notification", e)
                _syncStatus.value = SyncStatus.Error(e.message ?: "Failed to sync")
                _uiState.value = NotificationUiState.Error(e.message ?: "Failed to sync")
            }
        }
    }

    /**
     * 同步通知到选中的设备
     */
    fun syncNotificationToSelectedDevices(notification: AppNotification) {
        viewModelScope.launch {
            try {
                _syncStatus.value = SyncStatus.Syncing
                val devices = _selectedDevices.value

                if (devices.isEmpty()) {
                    _syncStatus.value = SyncStatus.Error("No devices selected")
                    return@launch
                }

                devices.forEach { deviceId ->
                    notificationManager.syncNotification(notification, deviceId)
                }

                _syncStatus.value = SyncStatus.Success
                logger.d(TAG, "Notification synced to ${devices.size} devices")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to sync notification", e)
                _syncStatus.value = SyncStatus.Error(e.message ?: "Failed to sync")
            }
        }
    }

    /**
     * 选择/取消选择设备
     */
    fun toggleDeviceSelection(deviceId: String) {
        val current = _selectedDevices.value.toMutableSet()
        if (current.contains(deviceId)) {
            current.remove(deviceId)
        } else {
            current.add(deviceId)
        }
        _selectedDevices.value = current
    }

    /**
     * 选择所有设备
     */
    fun selectAllDevices() {
        _selectedDevices.value = _connectedDevices.value.map { it.id }.toSet()
    }

    /**
     * 取消选择所有设备
     */
    fun deselectAllDevices() {
        _selectedDevices.value = emptySet()
    }

    /**
     * 获取同步历史
     */
    fun getSyncHistory() {
        viewModelScope.launch {
            try {
                repository.getAllNotifications(100)
                    .catch { e ->
                        logger.e(TAG, "Failed to get sync history", e)
                    }
                    .collect { notifications ->
                        val syncedNotifications = notifications.filter { it.isSynced }
                        logger.d(TAG, "Sync history: ${syncedNotifications.size} synced notifications")
                    }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to get sync history", e)
            }
        }
    }

    /**
     * 启动同步服务
     */
    fun startSyncService() {
        viewModelScope.launch {
            try {
                NotificationSyncService.start(context)
                _isListening.value = true
                logger.i(TAG, "Sync service started")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to start sync service", e)
                _uiState.value = NotificationUiState.Error(e.message ?: "Failed to start service")
            }
        }
    }

    /**
     * 停止同步服务
     */
    fun stopSyncService() {
        viewModelScope.launch {
            try {
                NotificationSyncService.stop(context)
                _isListening.value = false
                logger.i(TAG, "Sync service stopped")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to stop sync service", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        notificationsLoadJob?.cancel()
        deviceManager.stopDiscovery()
    }

    /**
     * 清除所有通知
     */
    fun clearAllNotifications() {
        viewModelScope.launch {
            try {
                repository.clearAll()
                logger.d(TAG, "All notifications cleared")
                loadNotifications()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to clear all notifications", e)
            }
        }
    }

    /**
     * 检查是否有通知访问权限
     */
    private fun hasNotificationPermission(): Boolean {
        // 通知监听权限需要在系统设置中手动授予
        // 这里只是检查基本的通知权限
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionManager.hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "NotificationViewModel"
    }
}

/**
 * 通知 UI 状态
 */
sealed class NotificationUiState {
    object Loading : NotificationUiState()
    object Success : NotificationUiState()
    object Empty : NotificationUiState()
    object PermissionRequired : NotificationUiState()
    data class Error(val message: String) : NotificationUiState()
}

/**
 * 同步状态
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
