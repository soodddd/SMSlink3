package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.ui.bridge.NotificationBridge
import com.smslink.ui.screens.notifications.NotificationUiModel
import com.smslink.ui.screens.notifications.NotificationsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationBridge: NotificationBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val selectedDevice = MutableStateFlow<String?>(null)

    init {
        combine(
            notificationBridge.getNotificationHistory(),
            searchQuery,
            selectedDevice
        ) { notifications, query, deviceId ->
            notifications
                .asSequence()
                .filter { deviceId == null || it.deviceId == deviceId }
                .filter {
                    query.isBlank() ||
                        it.appName.contains(query, ignoreCase = true) ||
                        it.title.contains(query, ignoreCase = true) ||
                        it.text.contains(query, ignoreCase = true)
                }
                .map { notification ->
                    NotificationUiModel(
                        id = notification.id,
                        appName = notification.appName,
                        title = notification.title,
                        text = notification.text,
                        deviceName = notification.deviceName,
                        timestamp = notification.timestamp,
                        timeText = formatTimestamp(notification.timestamp)
                    )
                }
                .toList()
        }
            .onEach { models ->
                _uiState.update {
                    it.copy(
                        notifications = models,
                        searchQuery = searchQuery.value,
                        selectedDeviceFilter = selectedDevice.value,
                        isLoading = false
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun search(query: String) {
        searchQuery.value = query
    }

    fun filterByDevice(deviceId: String?) {
        selectedDevice.value = deviceId
    }

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000}小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
