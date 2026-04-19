AI 代码修改指令集
1. 概览
文件路径: 多文件（feature/device/build.gradle.kts、feature/notification/build.gradle.kts、feature/call/build.gradle.kts、feature/transfer/build.gradle.kts、feature/settings/build.gradle.kts、core/preferences/src/main/java/com/smslink/core/preferences/AppPreferences.kt、ui/src/main/java/com/smslink/ui/viewmodel/AppPreferencesViewModel.kt、ui/src/main/java/com/smslink/ui/viewmodel/SettingsViewModel.kt、ui/src/main/java/com/smslink/ui/bridge/DeviceBridge.kt、feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt、feature/notification/src/main/java/com/smslink/feature/notification/NotificationSyncManager.kt、feature/notification/src/main/java/com/smslink/feature/notification/NotificationDisplayManager.kt、ui/src/main/java/com/smslink/ui/viewmodel/OnboardingViewModel.kt、app/src/main/java/com/smslink/MainActivity.kt、ui/src/main/java/com/smslink/ui/bridge/CallBridge.kt、ui/src/main/java/com/smslink/ui/bridge/TransferBridge.kt、ui/src/main/java/com/smslink/ui/viewmodel/NotificationsViewModel.kt）

主要任务: 修复当前代码中的构建阻断、跨模块接口失配和“文档宣称已完成但实现仍为空壳”的核心问题，使代码实际状态重新与项目目标一致。

技术栈: Android + Kotlin + Jetpack Compose + Hilt + Room + Coroutines + Flow

2. 修改任务清单
[任务 #001]
定位锚点: `buildFeatures { compose = true }`

当前代码:
```kotlin
android {
    namespace = "com.smslink.feature.device"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}
```
修改方案:
```kotlin
android {
    namespace = "com.smslink.feature.device"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
```
修改逻辑: `feature:device`、`feature:call`、`feature:transfer`、`feature:settings` 都没有 Compose UI 代码，却开启了 Compose 编译器插件，直接触发 `:feature:device:compileDebugKotlin` 的 `IncompatibleComposeRuntimeVersionException`。这些非 UI 模块必须移除 `buildFeatures.compose` 和 `composeOptions`。`feature:notification` 也不应为了 `NotificationCompat` 保留 Compose；同时删除其中无意义的 Compose 依赖，避免未来继续引入编译链污染。

严重程度: 阻断

[任务 #002]
定位锚点: `appPreferences.isFirstLaunch` / `appPreferences.isDarkTheme` / `appPreferences.setFirstLaunch(false)`

当前代码:
```kotlin
val shouldShowOnboarding: StateFlow<Boolean> = appPreferences.isFirstLaunch
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

val isDarkTheme: StateFlow<Boolean> = appPreferences.isDarkTheme
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

fun markOnboardingCompleted() {
    viewModelScope.launch {
        appPreferences.setFirstLaunch(false)
    }
}
```
修改方案:
```kotlin
val shouldShowOnboarding: StateFlow<Boolean> = appPreferences.shouldShowOnboarding
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

val isDarkTheme: StateFlow<Boolean> = appPreferences.themeMode
    .map { mode -> mode.isDarkMode() }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

fun markOnboardingCompleted() {
    viewModelScope.launch {
        appPreferences.markOnboardingCompleted()
    }
}
```
修改逻辑: `AppPreferences` 真实暴露的接口是 `shouldShowOnboarding`、`themeMode`、`markOnboardingCompleted()`，而 `AppPreferencesViewModel` 和 `SettingsViewModel` 却调用了根本不存在的 `isFirstLaunch`、`isDarkTheme`、`setFirstLaunch()`。这是确定性的编译错误，不是业务 bug。必须统一为以 `AppPreferences` 为单一事实来源，并让 `SettingsViewModel` 也从 `themeMode` 派生布尔值，禁止再引用虚构 API。

严重程度: 阻断

[任务 #003]
定位锚点: `deviceManager.enterPairingCode(code)` / `fun sendMessageToPairedDevice(message: Message)`

当前代码:
```kotlin
suspend fun enterPairingCode(code: String) {
    deviceManager.enterPairingCode(code)
}
```

```kotlin
suspend fun sendMessageToPairedDevice(message: Message) {
    tcpClient?.let {
        it.sendMessage(message)
        return
    }
    tcpServer?.getAllConnections()?.firstOrNull()?.sendMessage(message)
        ?: error("No active paired connection")
}
```
修改方案:
```kotlin
private var pendingPairingCode: String? = null

fun enterPairingCode(code: String) {
    pendingPairingCode = code
    pairingManager.startPairing()
}

suspend fun connectToDevice(deviceId: String) {
    val device = repository.getDeviceById(deviceId)
        ?: error("Unknown deviceId: $deviceId")
    connectToDevice(device)

    pendingPairingCode?.let { code ->
        val sent = sendPairingRequest(device, code)
        if (!sent) {
            pairingManager.pairingFailed("发送配对请求失败")
        }
    }
}

suspend fun sendMessage(message: Message) {
    sendMessageToPairedDevice(message)
}
```
修改逻辑: `DeviceBridge` 当前直接调用一个并不存在的 `DeviceManager.enterPairingCode()`，导致配对路径在编译期就断裂。更严重的是，副设备“输入配对码”后没有任何状态保存，也不会在选中目标设备后发送配对请求。需要把“输入的配对码”提升为 `DeviceManager` 的显式状态，并在建立 TCP 连接后调用现有 `sendPairingRequest()`。同时补一个统一的 `sendMessage()` 包装方法，避免调用方继续猜测底层命名。

严重程度: 阻断

[任务 #004]
定位锚点: `deviceManager.getIncomingMessages()` / `deviceManager.sendMessage(message)` / `notification.icon`

当前代码:
```kotlin
private fun observeIncomingMessages() {
    deviceManager.getIncomingMessages()
        .onEach { message ->
            if (message.type == MessageType.NOTIFICATION_SYNC) {
                handleIncomingNotification(message)
            }
        }
        .launchIn(scope)
}

suspend fun syncNotification(notification: NotificationInfo) {
    val message = Message(
        type = MessageType.NOTIFICATION_SYNC,
        payload = payload.toByteArray(Charsets.UTF_8)
    )
    deviceManager.sendMessage(message)
}
```

```kotlin
notification.icon?.let { iconBytes ->
    // TODO: Convert byte array to Bitmap and set as large icon
}

notificationManager.notify(notification.id.toInt(), builder.build())
```
修改方案:
```kotlin
private fun observeIncomingMessages() {
    deviceManager.incomingMessages
        .onEach { message ->
            if (message.type == MessageType.NOTIFICATION_SYNC && message.payload.isNotEmpty()) {
                handleIncomingNotification(message)
            }
        }
        .launchIn(scope)
}

suspend fun syncNotification(notification: NotificationInfo) {
    val message = Message(
        type = MessageType.NOTIFICATION_SYNC,
        messageId = System.currentTimeMillis(),
        payload = buildNotificationPayload(notification).toByteArray(Charsets.UTF_8)
    )
    deviceManager.sendMessage(message)
}
```

```kotlin
fun displayNotification(notification: NotificationInfo) {
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(notification.title)
        .setContentText(notification.text)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    val notificationId = notification.id.hashCode()
    notificationManager.notify(notificationId, builder.build())
}
```
修改逻辑: 这一段同时存在三类确定性故障。第一，`DeviceManager` 只有 `incomingMessages` 属性，没有 `getIncomingMessages()` 方法。第二，`DeviceManager` 只有 `sendMessageToPairedDevice()`，没有 `sendMessage()`。第三，`NotificationInfo` 只有 `iconPath`，没有 `icon` 字段，而且 `notification.id.toInt()` 对 UUID 直接崩溃。这里必须把通知同步层和设备层接口重新对齐，并改用 `hashCode()` 或数据库自增 ID 作为系统通知 ID。否则“通知同步已完成”只是文档假象。

严重程度: 阻断

[任务 #005]
定位锚点: `Message(type = MessageType.NOTIFICATION_SYNC, payload = ByteArray(0))`

当前代码:
```kotlin
suspend fun requestHistorySync() {
    val message = Message(
        type = MessageType.NOTIFICATION_SYNC,
        payload = ByteArray(0)  // 空负载表示请求历史
    )
    deviceManager.sendMessage(message)
}
```
修改方案:
```kotlin
private fun buildControlPayload(action: String): ByteArray {
    return JSONObject()
        .put("action", action)
        .toString()
        .toByteArray(Charsets.UTF_8)
}

suspend fun requestHistorySync() {
    val message = Message(
        type = MessageType.NOTIFICATION_SYNC,
        messageId = System.currentTimeMillis(),
        payload = buildControlPayload("history_request")
    )
    deviceManager.sendMessage(message)
}

private suspend fun handleIncomingNotification(message: Message) {
    val payloadString = String(message.payload, Charsets.UTF_8)
    val json = JSONObject(payloadString)

    when (json.optString("action")) {
        "history_request" -> return
        else -> {
            val notification = parseNotificationPayload(payloadString)
            notificationRepository.saveNotification(notification)
            notificationDisplayManager.displayNotification(notification)
        }
    }
}
```
修改逻辑: 当前把“真实通知数据”和“历史同步请求”都塞进同一个 `NOTIFICATION_SYNC` 消息类型里，还用空负载区分。接收端随后无条件按通知 JSON 解析，遇到空串直接抛异常。这会让历史同步永远失败，并污染日志。必须引入明确的控制字段，至少在现有协议内区分 `history_request` 与真实通知，不允许再依赖“空 payload”这种脆弱约定。

严重程度: 高

[任务 #006]
定位锚点: `selectRole(role: String)` / `currentRoute = Routes.PAIRING`

当前代码:
```kotlin
private val _selectedRole = MutableStateFlow<String?>(null)
val selectedRole: StateFlow<String?> = _selectedRole.asStateFlow()

fun selectRole(role: String) {
    _selectedRole.value = role
    nextStep()
}
```

```kotlin
onRoleSelected = { role ->
    onboardingViewModel.selectRole(role)
},
onComplete = {
    appPreferencesViewModel.markOnboardingCompleted()
    currentRoute = Routes.PAIRING
}
```
修改方案:
```kotlin
private val _selectedRole = MutableStateFlow<DeviceRole?>(null)
val selectedRole: StateFlow<DeviceRole?> = _selectedRole.asStateFlow()

fun selectRole(role: String) {
    val parsedRole = DeviceRole.valueOf(role)
    _selectedRole.value = parsedRole
    deviceBridge.switchRole(parsedRole)
    nextStep()
}
```

```kotlin
onComplete = {
    appPreferencesViewModel.markOnboardingCompleted()
    currentRoute = Routes.PAIRING
}
LaunchedEffect(Unit) {
    onboardingViewModel.selectedRole.collect { role ->
        role ?: return@collect
        deviceBridge.switchRole(role)
    }
}
```
修改逻辑: 当前引导流程只把角色存成字符串，既不初始化 `DeviceManager`，也不持久化角色，结果“主设备/副设备”选择对网络发现、配对、通知同步都没有任何实际影响。项目目标是跨设备协作，而角色初始化是根入口。必须把角色改成 `DeviceRole` 强类型，并在完成引导之前真实驱动 `DeviceManager.initialize/switchRole`，否则后续页面全部只是静态 UI。

严重程度: 高

[任务 #007]
定位锚点: `deviceName = notification.deviceId` / `// TODO: 实现搜索逻辑` / `// TODO: 实现筛选逻辑`

当前代码:
```kotlin
val notificationModels = notifications.map { notification ->
    NotificationUiModel(
        id = notification.id,
        appName = notification.appName,
        title = notification.title,
        text = notification.text,
        deviceName = notification.deviceId,
        timestamp = notification.timestamp,
        timeText = formatTimestamp(notification.timestamp)
    )
}
```

```kotlin
fun search(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    // TODO: 实现搜索逻辑
}

fun filterByDevice(deviceId: String?) {
    _uiState.update { it.copy(selectedDeviceFilter = deviceId) }
    // TODO: 实现筛选逻辑
}
```
修改方案:
```kotlin
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
```
修改逻辑: 项目状态把“通知同步”标记为 100%，但通知页现在仍然把 `deviceId` 当 `deviceName` 展示，且搜索/筛选完全没有实现。这样不仅与目标不符，还会误导测试结果。必须把展示字段修正为 `notification.deviceName`，并把搜索/筛选落到真实的 Flow 组合逻辑里，而不是保留 TODO。

严重程度: 中

[任务 #008]
定位锚点: `FeatureAvailability.Unavailable("通话功能尚未实现")` / `return emptyFlow()`

当前代码:
```kotlin
private val _availability = MutableStateFlow<FeatureAvailability>(
    FeatureAvailability.Unavailable("通话功能尚未实现")
)

suspend fun answerCall(callId: String): Result<Unit> {
    return Result.failure(UnsupportedOperationException("通话功能尚未实现"))
}
```

```kotlin
fun getFileTransfers(): Flow<List<FileTransferInfo>> {
    return emptyFlow()
}

fun getCallHistory(): Flow<List<CallInfo>> {
    return emptyFlow()
}
```
修改方案:
```kotlin
private val _availability = MutableStateFlow<FeatureAvailability>(
    FeatureAvailability.Unavailable("后端模块未接入，当前仅允许展示禁用态")
)
val availability: StateFlow<FeatureAvailability> = _availability

fun getFileTransfers(): Flow<List<FileTransferInfo>> = flowOf(emptyList())
fun getCallHistory(): Flow<List<CallInfo>> = flowOf(emptyList())
```

```kotlin
data class TransferUiState(
    val isLoading: Boolean = false,
    val isFeatureAvailable: Boolean = true,
    val unavailableReason: String? = null,
    val fileTransfers: List<FileTransferUiModel> = emptyList(),
    val callHistory: List<CallHistoryUiModel> = emptyList()
)
```
修改逻辑: `PROJECT_STATUS.md` 明确把 `CallBridge`、`TransferBridge` 写成“已实现桥接”，但源码实际上只有占位壳，所有行为都返回 `UnsupportedOperationException` 或空流。这种写法会让 UI 看起来像“暂无数据”，而不是“功能未实现”，严重误导验收。若短期内不接入真实后端，就必须把桥接层和 ViewModel 改成显式禁用态，并在页面上展示不可用原因，禁止继续伪装成已完成模块。

严重程度: 高

3. 约束规则 (强制执行)
编码规范: 禁止新增不存在于实现层的虚构 API；禁止继续用字符串硬编码业务状态代替强类型枚举；Flow 状态必须保持单一事实来源；非 UI 模块禁止启用 Compose 编译配置；禁止使用 any 类型。

禁止变动: 严禁修改现有 `MessageType`、`DeviceInfo`、`NotificationInfo`、`DeviceEntity`、`NotificationEntity` 的公开字段名和字段含义；严禁改变现有 Room 表名；严禁改变现有对外路由常量值。

依赖限制: 禁止引入新三方包；只能使用项目当前已经存在的 AndroidX、Compose、Hilt、Room、Coroutines、org.json 依赖。

4. 验证标准
[ ] `cmd /c gradlew.bat :app:compileDebugKotlin` 必须通过，不得再出现 Compose Runtime 缺失或接口未解析错误

[ ] `AppPreferencesViewModel`、`SettingsViewModel`、`DeviceBridge`、`NotificationSyncManager`、`NotificationDisplayManager` 不得再引用不存在的属性、方法或字段

[ ] 选择设备角色后，`DeviceManager` 必须被真实初始化，主设备和副设备流程不能再只停留在 UI 字符串状态

[ ] 通知历史页必须正确显示 `deviceName`，并且搜索、设备筛选可以实际影响列表结果

[ ] 通知历史同步请求不得再依赖空 payload；接收端不得因历史同步请求产生 JSON 解析异常

[ ] 通话/文件传输未实现时，UI 必须明确显示“功能不可用”而不是伪装成“暂无数据”
