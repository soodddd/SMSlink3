# Feature Modules API 文档

## 状态说明

**本文件中的接口定义均为"阶段规划中的目标 API 草案"，当前仓库未提交对应实现。**

以下内容仅用于约束后续开发方向，不能视为当前可调用接口。

---

## feature:device (Planned API)

### DeviceManager

```kotlin
class DeviceManager(
    private val context: Context
) {
    fun discoverDevices(): Flow<List<Device>>
    suspend fun pairDevice(deviceId: String): Result<Device>
    suspend fun unpairDevice(deviceId: String): Result<Unit>
    suspend fun switchRole(role: DeviceRole): Result<Unit>
    
    val pairedDevices: StateFlow<List<Device>>
    val currentRole: StateFlow<DeviceRole>
    val connectionState: StateFlow<ConnectionState>
}
```

**状态**: 未实现

---

## feature:notification (Planned API)

### NotificationSyncService

```kotlin
class NotificationSyncService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification)
    override fun onNotificationRemoved(sbn: StatusBarNotification)
}

class NotificationManager(
    private val context: Context
) {
    suspend fun syncNotification(notification: Notification): Result<Unit>
    suspend fun dismissNotification(notificationId: String): Result<Unit>
    suspend fun executeAction(notificationId: String, actionId: String): Result<Unit>
    
    val notifications: Flow<List<Notification>>
}
```

**状态**: 未实现

---

## feature:call (Planned API)

### CallManager

```kotlin
class CallManager(
    private val context: Context
) {
    suspend fun answerCall(callId: String): Result<Unit>
    suspend fun endCall(callId: String): Result<Unit>
    suspend fun muteCall(callId: String, muted: Boolean): Result<Unit>
    
    val incomingCalls: Flow<CallState>
    val activeCall: StateFlow<CallState?>
}
```

**状态**: 未实现

---

## feature:transfer (Planned API)

### TransferManager

```kotlin
class TransferManager(
    private val context: Context
) {
    suspend fun sendFile(
        fileUri: Uri,
        deviceId: String
    ): Result<String>
    
    suspend fun acceptTransfer(transferId: String): Result<Unit>
    suspend fun rejectTransfer(transferId: String): Result<Unit>
    suspend fun cancelTransfer(transferId: String): Result<Unit>
    
    fun getTransferProgress(transferId: String): Flow<TransferProgress>
    val activeTransfers: StateFlow<List<FileTransfer>>
}

data class TransferProgress(
    val transferId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val progress: Float
)
```

**状态**: 未实现

---

## feature:settings (Planned API)

### SettingsRepository

```kotlin
class SettingsRepository(
    private val preferences: AppPreferences
) {
    val notificationFilterEnabled: Flow<Boolean>
    val autoAnswerEnabled: Flow<Boolean>
    val audioQuality: Flow<AudioQuality>
    
    suspend fun setNotificationFilterEnabled(enabled: Boolean)
    suspend fun setAutoAnswerEnabled(enabled: Boolean)
    suspend fun setAudioQuality(quality: AudioQuality)
}

enum class AudioQuality {
    LOW,      // 16 kbps
    MEDIUM,   // 24 kbps
    HIGH      // 32 kbps
}
```

**状态**: 未实现
