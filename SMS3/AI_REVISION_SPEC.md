# AI 代码修改指令集

## 1. 概览
文件路径: `app/src/main/java/com/smslink/core/database/AppDatabase.kt`, `app/src/main/java/com/smslink/ui/connect/ConnectScreen.kt`, `app/src/main/java/com/smslink/ui/navigation/AppNavigation.kt`, `app/src/main/java/com/smslink/file/ui/FileTransferViewModel.kt`

主要任务: 修复真机启动崩溃、互联页文件传输目标丢失，以及文件传输在无目标设备时仍继续执行的问题。

技术栈: Kotlin + Jetpack Compose + Hilt + Room

## 2. 修改任务清单

[任务 #1]
定位锚点: `AppDatabase` / `DATABASE_NAME`

当前代码:
```kotlin
@Database(
    entities = [
        Device::class,
        Message::class,
        AppNotification::class,
        FileTransferEntity::class,
        CallLog::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "smslink_database_v4"
    }
}
```

修改方案:
```kotlin
@Database(
    entities = [
        Device::class,
        Message::class,
        AppNotification::class,
        FileTransferEntity::class,
        CallLog::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "smslink_database_v4"
    }
}
```

修改逻辑: 真机首次启动曾因 Room schema 校验失败直接崩溃。通过抬升数据库版本并切换到新数据库名，强制使用干净数据文件，避免旧库结构与新实体定义冲突。

严重程度: 阻断

[任务 #2]
定位锚点: `ConnectScreen`

当前代码:
```kotlin
@Composable
fun ConnectScreen(
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    val pairedDevices by deviceViewModel.pairedDevices.collectAsState()
    val connectedDevices by deviceViewModel.connectedDevices.collectAsState()
    val resolvedDeviceId = connectedDevices.firstOrNull()?.id
        ?: pairedDevices.firstOrNull()?.id
        ?: ""

    var selectedTabIndex by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTabIndex) {
            1 -> FileTransferScreen(deviceId = resolvedDeviceId)
        }
    }
}
```

修改方案:
```kotlin
@Composable
fun ConnectScreen(
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    val pairedDevices by deviceViewModel.pairedDevices.collectAsState()
    val connectedDevices by deviceViewModel.connectedDevices.collectAsState()
    val resolvedDeviceId = connectedDevices.firstOrNull()?.id
        ?: pairedDevices.firstOrNull()?.id
        ?: ""

    var selectedTabIndex by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTabIndex) {
            1 -> FileTransferScreen(deviceId = resolvedDeviceId)
        }
    }
}
```

修改逻辑: 互联页文件标签不能再把空字符串传给文件传输页。应优先使用当前已连接设备，其次使用已配对设备，保证文件发送和接收至少绑定到一个真实目标。

严重程度: 中

[任务 #3]
定位锚点: `Screen.Files.route` / `sendFile(file: File, targetDeviceId: String)`

当前代码:
```kotlin
composable(Screen.Files.route) {
    FileTransferScreen(deviceId = selectedDeviceId.orEmpty())
}
```

```kotlin
fun sendFile(file: File, targetDeviceId: String) {
    scope.launch {
        if (targetDeviceId.isBlank()) {
            _events.emit(FileTransferEvent.Error("No target device selected"))
            return@launch
        }
        try {
            _uiState.update { it.copy(isLoading = true) }

            fileTransferManager.sendFile(file, targetDeviceId)
                .catch { e ->
                    logger.e("FileTransferVM", "Send file failed: ${e.message}")
                    _events.emit(FileTransferEvent.Error(e.message ?: "Send failed"))
                }
                .collect { transfer ->
                    logger.i("FileTransferVM", "Transfer progress: ${transfer.progress}")
                }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
```

修改方案:
```kotlin
composable(Screen.Files.route) {
    FileTransferScreen(deviceId = selectedDeviceId.orEmpty())
}
```

```kotlin
fun sendFile(file: File, targetDeviceId: String) {
    scope.launch {
        if (targetDeviceId.isBlank()) {
            _events.emit(FileTransferEvent.Error("No target device selected"))
            return@launch
        }
        try {
            _uiState.update { it.copy(isLoading = true) }

            fileTransferManager.sendFile(file, targetDeviceId)
                .catch { e ->
                    logger.e("FileTransferVM", "Send file failed: ${e.message}")
                    _events.emit(FileTransferEvent.Error(e.message ?: "Send failed"))
                }
                .collect { transfer ->
                    logger.i("FileTransferVM", "Transfer progress: ${transfer.progress}")
                }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
```

修改逻辑: 路由层不应把占位文本当成设备 ID 下发，发送层也必须拒绝空目标，避免文件传输在无连接状态下误入错误分支或误导用户。

严重程度: 高

## 3. 约束规则 (强制执行)
编码规范: 禁止继续使用空字符串或“当前连接设备”这类占位值充当真实 `deviceId`；所有文件传输目标必须来自真实设备数据。

禁止变动: 严禁修改现有页面路由结构和底部导航顺序；严禁改变 `FileTransferViewModel.sendFile` 的对外方法签名。

依赖限制: 不引入新的第三方依赖，不新增数据库迁移框架，不替换现有 Room + Hilt + Compose 组合。

## 4. 验证标准
[ ] 修改后必须通过 `:app:assembleDebug` 和 `:app:testDebugUnitTest`

[ ] 真机启动后不能再出现 Room schema 校验崩溃

[ ] 互联页进入文件标签时，传输目标必须来自真实已连接或已配对设备

[ ] 无有效目标设备时，文件发送必须被明确拦截并返回错误提示，不得继续执行传输流程
