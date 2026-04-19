# Core API 文档

## 状态说明

**本文件中的接口定义均为"阶段规划中的目标 API 草案"，当前仓库未提交对应 Kotlin/Java 实现。**

以下内容仅用于约束后续开发方向，不能视为当前可调用接口。

---

## core:common (Planned API)

### Result 类

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
    
    inline fun <R> map(transform: (T) -> R): Result<R>
    inline fun onSuccess(action: (T) -> Unit): Result<T>
    inline fun onError(action: (Exception) -> Unit): Result<T>
}
```

**状态**: 未实现

### 扩展函数 (Planned)

```kotlin
// Flow 扩展
fun <T> Flow<T>.throttleFirst(windowDuration: Long): Flow<T>
fun <T> Flow<T>.retryWithBackoff(times: Int, initialDelay: Long): Flow<T>

// 字节数组扩展
fun ByteArray.toHexString(): String
fun String.hexToByteArray(): ByteArray

// JSON 扩展
fun <T> T.toJson(): String
fun <T> String.fromJson(clazz: Class<T>): T
```

**状态**: 未实现

### Logger (Planned)

```kotlin
object Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
```

**状态**: 未实现

---

## core:model (Planned API)

### Device

```kotlin
data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val role: DeviceRole,
    val lastSeen: Long,
    val capabilities: List<Capability>
)

enum class DeviceType { PHONE, TABLET }
enum class DeviceRole { PRIMARY, SECONDARY }
enum class Capability { NOTIFICATION, CALL, TRANSFER }
```

**状态**: 未实现

### Notification (Planned)

```kotlin
data class Notification(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val icon: ByteArray?,
    val actions: List<NotificationAction>
)

data class NotificationAction(
    val id: String,
    val title: String
)
```

**状态**: 未实现

### CallState (Planned)

```kotlin
data class CallState(
    val callId: String,
    val phoneNumber: String,
    val state: State,
    val startTime: Long
)

enum class State {
    RINGING,
    ACTIVE,
    ENDED
}
```

**状态**: 未实现

### FileTransfer (Planned)

```kotlin
data class FileTransfer(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val progress: Float,
    val state: TransferState
)

enum class TransferState {
    PENDING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

**状态**: 未实现

---

## core:database (Planned API)

### DeviceDao

```kotlin
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAllDevices(): Flow<List<DeviceEntity>>
    
    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getDevice(deviceId: String): DeviceEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)
    
    @Delete
    suspend fun deleteDevice(device: DeviceEntity)
}
```

**状态**: 未实现

### NotificationDao (Planned)

```kotlin
@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentNotifications(limit: Int): Flow<List<NotificationEntity>>
    
    @Insert
    suspend fun insertNotification(notification: NotificationEntity)
    
    @Query("DELETE FROM notifications WHERE timestamp < :timestamp")
    suspend fun deleteOldNotifications(timestamp: Long)
}
```

**状态**: 未实现

---

## core:preferences (Planned API)

### AppPreferences

```kotlin
class AppPreferences(context: Context) {
    val deviceRole: Flow<DeviceRole>
    val pairedDeviceId: Flow<String?>
    val notificationFilterEnabled: Flow<Boolean>
    
    suspend fun setDeviceRole(role: DeviceRole)
    suspend fun setPairedDeviceId(deviceId: String?)
    suspend fun setNotificationFilterEnabled(enabled: Boolean)
}
```

**状态**: 未实现
