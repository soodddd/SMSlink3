package com.smslink.sms

import androidx.lifecycle.ViewModel
import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.Message
import com.smslink.core.permission.IPermissionManager
import com.smslink.device.IDeviceManager
import com.smslink.sms.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 短信 ViewModel
 */
@HiltViewModel
class SmsViewModel @Inject constructor(
    private val smsManager: SmsManagerImpl,
    private val smsRepository: SmsRepository,
    private val deviceManager: IDeviceManager,
    private val permissionManager: IPermissionManager,
    private val logger: ILogger
) : ViewModel() {

    private val _uiState = MutableStateFlow<SmsUiState>(SmsUiState.Loading)
    val uiState: StateFlow<SmsUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevices: StateFlow<List<Device>> = _connectedDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<Device?>(null)
    val selectedDevice: StateFlow<Device?> = _selectedDevice.asStateFlow()

    private val _selectedSimSlot = MutableStateFlow<Int?>(null)
    val selectedSimSlot: StateFlow<Int?> = _selectedSimSlot.asStateFlow()

    private val _viewMode = MutableStateFlow<ViewMode>(ViewMode.CONVERSATIONS)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        checkPermissionsAndLoadMessages()
        observeNewMessages()
        observeConnectedDevices()
    }

    /**
     * 检查权限并加载消息
     */
    private fun checkPermissionsAndLoadMessages() {
        scope.launch {
            if (!hasRequiredPermissions()) {
                _uiState.value = SmsUiState.PermissionRequired
                return@launch
            }

            loadMessages()
            loadConversations()
        }
    }

    /**
     * 加载消息列表
     */
    fun loadMessages() {
        scope.launch {
            _uiState.value = SmsUiState.Loading

            smsRepository.getAllMessages(100)
                .catch { e ->
                    logger.e(TAG, "Failed to load messages", e)
                    _uiState.value = SmsUiState.Error(e.message ?: "Unknown error")
                }
                .collect { messageList ->
                    _messages.value = messageList
                    _uiState.value = if (messageList.isEmpty()) {
                        SmsUiState.Empty
                    } else {
                        SmsUiState.Success
                    }
                }
        }
    }

    /**
     * 加载会话列表
     */
    fun loadConversations() {
        scope.launch {
            try {
                _syncState.value = SyncState.Syncing
                val conversationList = smsManager.getConversations()
                _conversations.value = conversationList
                _syncState.value = SyncState.Success
                logger.d(TAG, "Loaded ${conversationList.size} conversations")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to load conversations", e)
                _syncState.value = SyncState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 监听已连接设备
     */
    private fun observeConnectedDevices() {
        scope.launch {
            deviceManager.getConnectedDevices()
                .catch { e ->
                    logger.e(TAG, "Failed to observe connected devices", e)
                }
                .collect { devices ->
                    _connectedDevices.value = devices
                    logger.d(TAG, "Connected devices: ${devices.size}")
                }
        }
    }

    /**
     * 监听新消息
     */
    private fun observeNewMessages() {
        scope.launch {
            smsManager.observeNewMessages()
                .catch { e ->
                    logger.e(TAG, "Failed to observe new messages", e)
                }
                .collect { newMessage ->
                    logger.d(TAG, "New message received: ${newMessage.id}")
                    // 刷新消息列表和会话列表
                    loadMessages()
                    loadConversations()
                }
        }
    }

    /**
     * 发送短信
     */
    fun sendMessage(address: String, body: String) {
        if (address.isBlank() || body.isBlank()) {
            _sendState.value = SendState.Error("Address and message body cannot be empty")
            return
        }

        scope.launch {
            _sendState.value = SendState.Sending

            try {
                val success = smsManager.sendMessage(address, body, _selectedSimSlot.value)
                _sendState.value = if (success) {
                    SendState.Success
                } else {
                    SendState.Error("Failed to send message")
                }

                // 刷新列表
                if (success) {
                    loadMessages()
                    loadConversations()
                }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to send message", e)
                _sendState.value = SendState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 选择设备
     */
    fun selectDevice(device: Device?) {
        _selectedDevice.value = device
        logger.d(TAG, "Selected device: ${device?.name}")
    }

    /**
     * 选择 SIM 卡槽
     */
    fun selectSimSlot(slot: Int?) {
        _selectedSimSlot.value = slot
        logger.d(TAG, "Selected SIM slot: $slot")
    }

    /**
     * 切换视图模式
     */
    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        logger.d(TAG, "View mode changed to: $mode")
    }

    /**
     * 标记消息为已读
     */
    fun markAsRead(messageId: String) {
        scope.launch {
            try {
                smsManager.markAsRead(messageId)
                logger.d(TAG, "Message marked as read: $messageId")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to mark message as read", e)
            }
        }
    }

    /**
     * 删除消息
     */
    fun deleteMessage(messageId: String) {
        scope.launch {
            try {
                smsManager.deleteMessage(messageId)
                logger.d(TAG, "Message deleted: $messageId")
                loadMessages()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to delete message", e)
            }
        }
    }

    /**
     * 同步系统短信
     */
    fun syncFromSystem() {
        scope.launch {
            try {
                smsManager.syncFromSystem()
                loadMessages()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to sync from system", e)
            }
        }
    }

    /**
     * 重置发送状态
     */
    fun resetSendState() {
        _sendState.value = SendState.Idle
    }

    /**
     * 检查是否有必需的权限
     */
    private fun hasRequiredPermissions(): Boolean {
        return permissionManager.hasPermission(android.Manifest.permission.READ_SMS) &&
                permissionManager.hasPermission(android.Manifest.permission.SEND_SMS) &&
                permissionManager.hasPermission(android.Manifest.permission.RECEIVE_SMS)
    }

    companion object {
        private const val TAG = "SmsViewModel"
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}

/**
 * 短信 UI 状态
 */
sealed class SmsUiState {
    object Loading : SmsUiState()
    object Success : SmsUiState()
    object Empty : SmsUiState()
    object PermissionRequired : SmsUiState()
    data class Error(val message: String) : SmsUiState()
}

/**
 * 发送状态
 */
sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    object Success : SendState()
    data class Error(val message: String) : SendState()
}

/**
 * 同步状态
 */
sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * 视图模式
 */
enum class ViewMode {
    CONVERSATIONS,  // 会话列表模式
    MESSAGES        // 消息列表模式
}
