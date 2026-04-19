AI 代码修改指令集

1. 概览
文件路径: 多文件（`ui/build.gradle.kts`、`feature/device/build.gradle.kts`、`feature/call/build.gradle.kts`、`feature/transfer/build.gradle.kts`、`feature/settings/build.gradle.kts`、`ui/src/main/java/com/smslink/ui/bridge/DeviceBridge.kt`、`feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt`、`feature/notification/src/main/java/com/smslink/feature/notification/NotificationSyncManager.kt`、`feature/notification/src/main/java/com/smslink/feature/notification/di/NotificationModule.kt`、`app/src/main/java/com/smslink/MainActivity.kt`、`app/src/main/AndroidManifest.xml`、`ui/src/main/java/com/smslink/ui/bridge/CallBridge.kt`、`ui/src/main/java/com/smslink/ui/bridge/TransferBridge.kt`、`core/preferences/*`）

主要任务: 修复当前源码中阻断编译、依赖注入断裂、通知同步伪实现、设备身份不稳定和 UI 伪完成状态，使实现与 `PROJECT_STATUS.md` 的目标重新对齐。

技术栈: Kotlin + Android 15 + Jetpack Compose + Hilt + Room + DataStore + Coroutines + Flow

2. 修改任务清单

[任务 #001]
定位锚点: `ui/build.gradle.kts` 中 `implementation(project(":feature:device"))`；`feature/device/build.gradle.kts` 中 `implementation(project(":ui"))`

当前代码:
```kts
// ui/build.gradle.kts
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":feature:device"))
}

// feature/device/build.gradle.kts
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:preferences"))
    implementation(project(":network:discovery"))
    implementation(project(":network:transport"))
    implementation(project(":network:protocol"))
    implementation(project(":network:hotspot"))
    implementation(project(":ui"))
}
```

修改方案:
```kts
// ui/build.gradle.kts
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":feature:device"))
}

// feature/device/build.gradle.kts
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:preferences"))
    implementation(project(":network:discovery"))
    implementation(project(":network:transport"))
    implementation(project(":network:protocol"))
    implementation(project(":network:hotspot"))
    // 删除 implementation(project(":ui"))
}

// 同样删除 feature/call、feature/transfer、feature/settings 中所有指向 :ui 的依赖
```

修改逻辑: 现在 `ui -> feature:device -> ui` 形成环依赖，`.\gradlew.bat :app:compileDebugKotlin` 已被证实直接失败。Feature 层不允许反向依赖 UI 层，所有桥接类和 ViewModel 只能单向依赖 feature/core/network。先拆掉反向依赖，再处理接口归属问题，否则项目永远无法进入 Kotlin 编译阶段。

严重程度: 阻断

[任务 #002]
定位锚点: `DeviceBridge` 中 `deviceManager.getPairedDevices()`、`deviceManager.generatePairingCode()`、`deviceManager.connectToDevice(deviceId)`；`DeviceManager` 实际只提供 `connectToDevice(device: DeviceInfo)`

当前代码:
```kotlin
// ui/src/main/java/com/smslink/ui/bridge/DeviceBridge.kt
fun getPairedDevices(): Flow<List<DeviceInfo>> {
    return deviceManager.getPairedDevices()
}

fun getPairingState(): Flow<PairingState> {
    return deviceManager.pairingState
}

suspend fun generatePairingCode(): String {
    return deviceManager.generatePairingCode()
}

suspend fun enterPairingCode(code: String) {
    deviceManager.enterPairingCode(code)
}

fun getDiscoveredDevices(): Flow<List<DeviceInfo>> {
    return deviceManager.getDiscoveredDevices()
}

suspend fun connectToDevice(deviceId: String) {
    deviceManager.connectToDevice(deviceId)
}

// feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt
suspend fun connectToDevice(device: DeviceInfo) {
    ...
}
```

修改方案:
```kotlin
// feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt
val pairingState: StateFlow<PairingState> = pairingManager.pairingState

fun getPairedDevices(): Flow<List<DeviceInfo>> = repository.getPairedDevices()

fun getDiscoveredDevices(): StateFlow<List<DeviceInfo>> = discoveredDevices

fun generatePairingCode(): String = pairingManager.generatePairingCode()

fun cancelPairing() {
    pairingManager.reset()
}

suspend fun connectToDevice(deviceId: String) {
    val device = repository.getDeviceById(deviceId)
        ?: error("Unknown deviceId: $deviceId")
    connectToDevice(device)
}

suspend fun unpairDevice(deviceId: String) {
    repository.updatePairingStatus(deviceId, false)
    if (pairedDevice.value?.deviceId == deviceId) {
        disconnect()
        _pairedDevice.value = null
    }
}

fun disconnectDevice(deviceId: String? = null) {
    if (deviceId == null || pairedDevice.value?.deviceId == deviceId) {
        disconnect()
    }
}

// ui/src/main/java/com/smslink/ui/bridge/DeviceBridge.kt
// 只保留薄适配层，禁止再调用不存在的 DeviceManager API
```

修改逻辑: 当前 `DeviceBridge` 与 `DeviceManager` 的公共契约严重漂移；即使环依赖被拆掉，后续也会因为方法/属性不存在而编译失败。必须把“设备管理对 UI 暴露的接口”收敛成一份单一真源，建议由 `DeviceManager` 公开稳定 API，`DeviceBridge` 只做转发，不再擅自定义一套不存在的方法名。

严重程度: 阻断

[任务 #003]
定位锚点: `NotificationSyncManager.syncNotification()` 中 `val payload = buildNotificationPayload(notification)`、`Message(payload = payload)`、`parseNotificationPayload(message.payload)`、`TODO: 需要在 DeviceManager 中添加发送消息的方法`

当前代码:
```kotlin
class NotificationSyncManager @Inject constructor(
    private val deviceManager: DeviceManager,
    private val notificationRepository: NotificationRepository,
    private val messageCodec: MessageCodec
) {
    suspend fun syncNotification(notification: NotificationInfo) {
        val payload = buildNotificationPayload(notification)
        val message = Message(
            type = MessageType.NOTIFICATION_SYNC,
            payload = payload
        )

        // TODO: 需要在 DeviceManager 中添加发送消息的方法
        Log.d(TAG, "Notification ready to sync: ${notification.appName} - ${notification.title}")
    }

    suspend fun handleIncomingNotification(message: Message) {
        val notification = parseNotificationPayload(message.payload)
        notificationRepository.saveNotification(notification)
    }

    private fun buildNotificationPayload(notification: NotificationInfo): String { ... }
    private fun parseNotificationPayload(payload: String): NotificationInfo { ... }
}
```

修改方案:
```kotlin
class NotificationSyncManager @Inject constructor(
    private val deviceManager: DeviceManager,
    private val notificationRepository: NotificationRepository
) {
    suspend fun syncNotification(notification: NotificationInfo) {
        val message = Message(
            type = MessageType.NOTIFICATION_SYNC,
            flags = Message.FLAG_REQUIRES_ACK.toByte(),
            messageId = System.currentTimeMillis(),
            payload = buildNotificationPayload(notification)
        )
        deviceManager.sendMessageToPairedDevice(message)
    }

    suspend fun handleIncomingNotification(message: Message) {
        val notification = parseNotificationPayload(message.payload)
        notificationRepository.saveNotification(notification)
        showSystemNotification(notification)
    }

    private fun buildNotificationPayload(notification: NotificationInfo): ByteArray {
        return JSONObject()
            .put("id", notification.id)
            .put("appName", notification.appName)
            .put("appPackage", notification.appPackage)
            .put("title", notification.title)
            .put("text", notification.text)
            .put("deviceId", notification.deviceId)
            .put("deviceName", notification.deviceName)
            .put("timestamp", notification.timestamp)
            .put("isRead", notification.isRead)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun parseNotificationPayload(payload: ByteArray): NotificationInfo {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        return NotificationInfo(
            id = json.getString("id"),
            appName = json.getString("appName"),
            appPackage = json.getString("appPackage"),
            title = json.getString("title"),
            text = json.getString("text"),
            deviceId = json.getString("deviceId"),
            deviceName = json.getString("deviceName"),
            timestamp = json.getLong("timestamp"),
            isRead = json.optBoolean("isRead", false),
            iconPath = json.optString("iconPath").takeIf { it.isNotBlank() }
        )
    }
}

// feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt
private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
val incomingMessages: SharedFlow<Message> = _incomingMessages.asSharedFlow()

suspend fun sendMessageToPairedDevice(message: Message) {
    tcpClient?.let { it.sendMessage(message); return }
    tcpServer?.getAllConnections()?.firstOrNull()?.sendMessage(message)
        ?: error("No active paired connection")
}
```

修改逻辑: 这段代码目前既有类型错误（`String` 塞给 `ByteArray`，`ByteArray` 当 `String` 解析），也没有真正发消息，属于“看起来像实现，实际上没有闭环”的伪完成。必须把 payload 改为明确的 UTF-8 `ByteArray`，并在 `DeviceManager` 中补齐统一的消息发送/分发入口，让通知同步从“日志输出”变成真实链路。

严重程度: 阻断

[任务 #004]
定位锚点: `UiModule.provideDeviceBridge(...)`、`NotificationModule.provideNotificationSyncManager(...)`；全项目不存在 `DeviceManager`/`DeviceRepository`/`DeviceDao`/`SmsLinkDatabase` 的 Hilt Provider

当前代码:
```kotlin
// app/src/main/java/com/smslink/di/UiModule.kt
@Provides
@Singleton
fun provideDeviceBridge(
    @ApplicationContext context: Context,
    deviceManager: DeviceManager
): DeviceBridge {
    return DeviceBridge(context, deviceManager)
}

// feature/notification/src/main/java/com/smslink/feature/notification/di/NotificationModule.kt
@Provides
@Singleton
fun provideNotificationSyncManager(
    deviceManager: DeviceManager,
    notificationRepository: NotificationRepository,
    messageCodec: MessageCodec
): NotificationSyncManager {
    return NotificationSyncManager(
        deviceManager,
        notificationRepository,
        messageCodec
    )
}
```

修改方案:
```kotlin
// 新增 feature/device/src/main/java/com/smslink/feature/device/di/DeviceModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmsLinkDatabase =
        SmsLinkDatabase.getInstance(context)

    @Provides
    fun provideDeviceDao(database: SmsLinkDatabase): DeviceDao = database.deviceDao()

    @Provides
    @Singleton
    fun provideDeviceRepository(deviceDao: DeviceDao): DeviceRepository =
        DeviceRepository(deviceDao)

    @Provides
    @Singleton
    fun provideDeviceManager(
        @ApplicationContext context: Context,
        repository: DeviceRepository
    ): DeviceManager = DeviceManager(context, repository)
}

// feature/notification/.../NotificationModule.kt
@Provides
@Singleton
fun provideNotificationSyncManager(
    deviceManager: DeviceManager,
    notificationRepository: NotificationRepository
): NotificationSyncManager {
    return NotificationSyncManager(deviceManager, notificationRepository)
}
```

修改逻辑: 当前 Hilt 图是不完整的。`DeviceBridge`、`NotificationSyncManager` 都要求注入 `DeviceManager`，但全项目没有任何 `DeviceManager` Provider；`NotificationModule` 还额外要求一个没有 Provider 的 `MessageCodec`。这类问题在环依赖拆除后会立刻暴露成 Hilt/KSP 编译失败，必须一次性补齐数据库、DAO、仓库、管理器的 Provider 链。

严重程度: 高

[任务 #005]
定位锚点: `DeviceManager` 中 `private val localDeviceId = UUID.randomUUID().toString()`；服务端配对成功时保存 `ipAddress = null, port = 0`；`SmsLinkNotificationListenerService` 中 `deviceId = "local"`

当前代码:
```kotlin
// feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt
private val localDeviceId = UUID.randomUUID().toString()

val deviceInfo = DeviceInfo(
    deviceId = request.deviceId,
    deviceName = request.deviceName,
    deviceType = DeviceType.PHONE,
    capabilities = emptySet(),
    ipAddress = null,
    port = 0
)
repository.saveDevice(deviceInfo, isPaired = true)

// feature/notification/src/main/java/com/smslink/feature/notification/SmsLinkNotificationListenerService.kt
val notificationInfo = NotificationInfo(
    ...
    deviceId = "local",
    deviceName = "本机",
    ...
)
```

修改方案:
```kotlin
// core/preferences/src/main/java/com/smslink/core/preferences/DevicePreferences.kt
@Singleton
class DevicePreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    suspend fun getOrCreateLocalDeviceId(): String { ... } // DataStore 持久化
    suspend fun getLocalDeviceName(defaultName: String): String { ... }
}

// feature/device/src/main/java/com/smslink/feature/device/DeviceManager.kt
class DeviceManager(
    private val context: Context,
    private val repository: DeviceRepository,
    private val devicePreferences: DevicePreferences
) {
    private val localDeviceId by lazy { runBlocking { devicePreferences.getOrCreateLocalDeviceId() } }

    private suspend fun persistPairedPeer(
        request: PairingRequest,
        connection: Connection?
    ) {
        val peer = DeviceInfo(
            deviceId = request.deviceId,
            deviceName = request.deviceName,
            deviceType = DeviceType.PHONE,
            capabilities = setOf(DeviceCapability.NOTIFICATION),
            ipAddress = connection?.remoteAddress,
            port = connection?.remotePort ?: TcpServer.DEFAULT_PORT
        )
        repository.saveDevice(peer, isPaired = true)
        _pairedDevice.value = peer
    }
}

// SmsLinkNotificationListenerService.kt
val localIdentity = deviceIdentityProvider.getLocalIdentity()
val notificationInfo = NotificationInfo(
    ...
    deviceId = localIdentity.deviceId,
    deviceName = localIdentity.deviceName,
    ...
)
```

修改逻辑: 设备身份每次重启都换一个新的 UUID，会直接破坏配对、通知归属、历史过滤和重连能力；服务端还把已配对设备保存成 `ipAddress = null, port = 0`，意味着重启后无法重新建立连接。通知监听服务又单独写死 `"local"`，进一步制造身份不一致。必须把“本机身份”和“已配对端点信息”持久化，且全项目只允许一个身份来源。

严重程度: 高

[任务 #006]
定位锚点: `MainActivity.SmsLinkApp()` 中 `showOnboarding = false`、`onPermissionsGranted`；`SettingsViewModel.setDarkTheme()` 中 `TODO: 保存到 SharedPreferences`；`AndroidManifest.xml` 中 `<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />`

当前代码:
```kotlin
// app/src/main/java/com/smslink/MainActivity.kt
var showOnboarding by remember { mutableStateOf(false) } // TODO: 从 SharedPreferences 读取

onPermissionsGranted = {
    // TODO: 请求权限
    onboardingStep = OnboardingStep.PAIRING
}

// ui/src/main/java/com/smslink/ui/viewmodel/SettingsViewModel.kt
fun setDarkTheme(enabled: Boolean) {
    _uiState.update { it.copy(isDarkTheme = enabled) }
    // TODO: 保存到 SharedPreferences
}
```

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
```

修改方案:
```kotlin
// app/src/main/java/com/smslink/MainActivity.kt
@Composable
fun SmsLinkApp(
    appPreferences: AppPreferencesViewModel = hiltViewModel()
) {
    val shouldShowOnboarding by appPreferences.shouldShowOnboarding.collectAsState()
    val themeMode by appPreferences.themeMode.collectAsState()

    SmsLinkTheme(darkTheme = themeMode.isDarkMode()) {
        when {
            shouldShowOnboarding -> {
                OnboardingScreen(
                    ...
                    onPermissionsGranted = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onComplete = {
                        appPreferences.markOnboardingCompleted()
                    }
                )
            }
            ...
        }
    }
}

// ui/src/main/java/com/smslink/ui/viewmodel/SettingsViewModel.kt
fun setDarkTheme(enabled: Boolean) {
    viewModelScope.launch {
        preferencesRepository.setDarkTheme(enabled)
    }
}
```

```xml
<!-- app/src/main/AndroidManifest.xml -->
<!-- 删除 uses-permission BIND_NOTIFICATION_LISTENER_SERVICE；
     只保留 service 上的 android:permission 声明 -->
```

修改逻辑: `PROJECT_STATUS.md` 把 UI 和设置流描述得过于完整，但当前实现仍然是“引导页默认不出现、主题不持久化、通知监听权限不请求、Manifest 还错误声明了系统级绑定权限”。这会让首次安装体验与目标完全不符。必须用 DataStore/Preferences 补齐首启状态、主题状态和通知监听权限引导，并删掉无效的 `<uses-permission>`。

严重程度: 中

[任务 #007]
定位锚点: `CallBridge` 中多个 `throw NotImplementedError(...)`；`TransferBridge` 中 `flowOf(emptyList())` 和多个 `throw NotImplementedError(...)`

当前代码:
```kotlin
// ui/src/main/java/com/smslink/ui/bridge/CallBridge.kt
suspend fun answerCall(callId: String) {
    throw NotImplementedError("CallManager not implemented yet")
}

suspend fun rejectCall(callId: String) {
    throw NotImplementedError("Reject call not implemented yet")
}

// ui/src/main/java/com/smslink/ui/bridge/TransferBridge.kt
fun getFileTransfers(): Flow<List<FileTransferInfo>> {
    return flowOf(emptyList())
}

suspend fun sendFile(deviceId: String, file: File) {
    throw NotImplementedError("File transfer not implemented yet")
}
```

修改方案:
```kotlin
sealed interface FeatureAvailability {
    object Ready : FeatureAvailability
    data class Unavailable(val reason: String) : FeatureAvailability
}

class CallBridge @Inject constructor() {
    fun getAvailability(): StateFlow<FeatureAvailability> =
        MutableStateFlow(FeatureAvailability.Unavailable("Call feature not implemented"))

    suspend fun answerCall(callId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Call feature not implemented"))
}

class TransferBridge @Inject constructor() {
    fun getAvailability(): StateFlow<FeatureAvailability> =
        MutableStateFlow(FeatureAvailability.Unavailable("Transfer feature not implemented"))

    fun getFileTransfers(): Flow<List<FileTransferInfo>> = emptyFlow()

    suspend fun sendFile(deviceId: String, file: File): Result<Unit> =
        Result.failure(UnsupportedOperationException("Transfer feature not implemented"))
}

// 对应 ViewModel / Screen：根据 availability 禁用按钮，显示“功能未实现”，禁止触发崩溃
```

修改逻辑: 当前桥接层把“未实现”直接暴露为可点击路径，用户一旦触发就会抛异常；另一部分则静默返回空列表，造成“看起来正常但没有任何数据”的假象。这两种做法都会污染测试结果和验收判断。未完成功能必须显式标记为 unavailable，并让 UI 可感知地禁用，而不是运行时崩溃或悄悄吞掉行为。

严重程度: 中

3. 约束规则 (强制执行)
编码规范: 禁止 feature 层依赖 ui 模块；禁止保留 `TODO` / `NotImplementedError` 作为对外可调用路径；跨模块公共接口必须只有一份真源；网络消息 payload 必须统一使用 UTF-8 `ByteArray`；Flow/StateFlow 只暴露稳定引用，不要返回随内部对象更换而失效的临时流。

禁止变动: 严禁修改 `Message` 协议头布局、现有 `Room` 表名（`devices`、`notifications`）、现有 `Routes` 常量值、`NotificationInfo`/`DeviceInfo` 的核心字段语义；如果需要新增字段，只能向后兼容扩展。

依赖限制: 禁止引入新三方包；只允许使用项目内现有技术栈（AndroidX、Compose、Hilt、Room、DataStore、Coroutines、Flow、org.json）。

4. 验证标准
[ ] 执行 `.\gradlew.bat :app:compileDebugKotlin` 不再出现模块环依赖、缺失 Provider 或 Kotlin 类型错误。

[ ] 执行 `.\gradlew.bat test` 时，现有 `network:protocol`、`network:transport`、`feature:notification` 单元测试可通过；必要时补齐因接口重构而失效的测试。

[ ] 首次启动时必须正确进入引导流程；点击授权步骤后能跳转到通知监听设置页；完成引导后再次启动不应重复展示。

[ ] 主设备与副设备完成一次配对后，重启应用，本机 `deviceId` 不变化，已配对设备仍能被识别且具备可用的 `ipAddress/port`。

[ ] 发送一条本机通知后，`NotificationSyncManager` 会真实发送 `MessageType.NOTIFICATION_SYNC`，远端能落库并触发系统通知展示。

[ ] 通话/传输功能在未实现状态下不会崩溃，UI 只会展示明确的“不可用”状态。
