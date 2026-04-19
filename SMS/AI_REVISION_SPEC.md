AI 代码修改指令集
1. 概览
文件路径: `app/src/main/java/com/smslink/app/MessageRouter.kt`、`feature/transfer/src/main/java/com/smslink/feature/transfer/FileTransferManager.kt`、`feature/transfer/src/main/java/com/smslink/feature/transfer/TransferRepository.kt`、`ui/src/main/java/com/smslink/ui/viewmodel/HomeViewModel.kt`、`app/src/main/java/com/smslink/MainActivity.kt`、`ui/src/main/java/com/smslink/ui/viewmodel/OnboardingViewModel.kt`、`feature/call/src/main/java/com/smslink/feature/call/CallManager.kt`

主要任务: 补齐文件传输接收链路、收敛首页状态流、持久化首启/导航状态，并修正通话模块的生命周期与音频流管理，确保实现与 `PROJECT_STATUS.md` 描述的目标一致。

技术栈: Kotlin + Jetpack Compose + Hilt + Coroutines + Flow + Room

2. 修改任务清单
[任务 #001]
定位锚点: `MessageRouter.registerFileTransferHandlers()`、`FileTransferManager.receiveFileChunk()`

当前代码:

```kotlin
// app/src/main/java/com/smslink/app/MessageRouter.kt
private fun registerFileTransferHandlers() {
    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_REQUEST) { message ->
        val payload = String(message.payload)
        val parts = payload.split("|")
        if (parts.size >= 3) {
            val transferId = parts[0]
            val relativePath = parts[1]
            val fileSize = parts[2].toLongOrNull() ?: 0
            // TODO: 处理文件传输请求
        }
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_DATA) { message ->
        val payload = message.payload
        val firstPipe = payload.indexOf('|'.code.toByte())
        if (firstPipe > 0) {
            val secondPipe = payload.indexOf('|'.code.toByte(), firstPipe + 1)
            if (secondPipe > 0) {
                val thirdPipe = payload.indexOf('|'.code.toByte(), secondPipe + 1)
                if (thirdPipe > 0) {
                    val header = String(payload.copyOfRange(0, thirdPipe))
                    val parts = header.split("|")
                    if (parts.size >= 3) {
                        val transferId = parts[0]
                        val offset = parts[1].toLongOrNull() ?: 0
                        val totalSize = parts[2].toLongOrNull() ?: 0
                        val chunk = payload.copyOfRange(thirdPipe + 1, payload.size)
                        // TODO: 处理文件数据块
                    }
                }
            }
        }
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_PROGRESS) { message ->
        // TODO: 处理文件传输进度更新
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_COMPLETE) { message ->
        // TODO: 处理文件传输完成
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_CANCEL) { message ->
        // TODO: 处理文件传输取消
    }
}

// feature/transfer/src/main/java/com/smslink/feature/transfer/FileTransferManager.kt
suspend fun receiveFileChunk(transferId: String, chunk: ByteArray, offset: Long, totalSize: Long) {
    // TODO: 实现接收逻辑
}
```

修改方案:

```kotlin
// feature/transfer/src/main/java/com/smslink/feature/transfer/TransferRepository.kt
@Singleton
class TransferRepository @Inject constructor(
    private val fileTransferDao: FileTransferDao
) {
    suspend fun insertTransfer(transfer: FileTransferInfo, direction: TransferDirection) {
        fileTransferDao.insert(transfer.toEntity(direction))
    }

    suspend fun updateTransfer(transfer: FileTransferInfo, direction: TransferDirection) {
        fileTransferDao.update(transfer.toEntity(direction))
    }

    private fun FileTransferInfo.toEntity(direction: TransferDirection): FileTransferEntity = ...
}

// feature/transfer/src/main/java/com/smslink/feature/transfer/FileTransferManager.kt
suspend fun receiveFileChunk(transferId: String, chunk: ByteArray, offset: Long, totalSize: Long) {
    val transfer = transferRepository.getTransferById(transferId)
        ?: return

    if (offset != transfer.transferredBytes) {
        transferRepository.updateStatus(transferId, TransferStatus.FAILED, "Offset mismatch")
        return
    }

    val targetDir = File(context.getExternalFilesDir(null), "smslink").apply { mkdirs() }
    val targetFile = File(targetDir, transfer.fileName)

    RandomAccessFile(targetFile, "rw").use { raf ->
        raf.seek(offset)
        raf.write(chunk)
    }

    val nextBytes = offset + chunk.size
    val progress = ((nextBytes * 100) / totalSize).toInt().coerceIn(0, 100)
    val speed = if (nextBytes > 0) ((nextBytes * 1000) / maxOf(1, System.currentTimeMillis() - transfer.timestamp)) else 0L

    transferRepository.updateProgress(transferId, progress, nextBytes, speed)
    if (nextBytes >= totalSize) {
        transferRepository.updateStatus(transferId, TransferStatus.COMPLETED)
    }
}

// app/src/main/java/com/smslink/app/MessageRouter.kt
private fun registerFileTransferHandlers() {
    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_REQUEST) { message ->
        scope.launch {
            fileTransferManager.handleIncomingTransferRequest(message)
        }
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_DATA) { message ->
        scope.launch {
            val parsed = parseTransferChunk(message.payload) ?: return@launch
            fileTransferManager.receiveFileChunk(
                transferId = parsed.transferId,
                chunk = parsed.chunk,
                offset = parsed.offset,
                totalSize = parsed.totalSize
            )
        }
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_COMPLETE) { message ->
        scope.launch { fileTransferManager.completeTransfer(String(message.payload)) }
    }

    deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_CANCEL) { message ->
        scope.launch { fileTransferManager.cancelTransfer(String(message.payload)) }
    }
}
```

修改逻辑: 现在文件传输只有发送路径，接收路径、进度回写、完成态回写全部是空实现，路由器也只做了 payload 解析却没有真正驱动落盘与状态更新。必须把 `MessageRouter` 改成真正的消息分发入口，把 `FileTransferManager` 改成接收端状态机，才能让“文件传输”不只是 UI 上的假数据。

严重程度: 阻断

[任务 #002]
定位锚点: `HomeViewModel.observeDevices()`、`HomeViewModel.refresh()`

当前代码:

```kotlin
private fun observeDevices() {
    viewModelScope.launch {
        deviceBridge.getPairedDevices()
            .catch { e ->
                _uiState.update { it.copy(error = e.message) }
            }
            .collect { devices ->
                val deviceModels = devices.map { device ->
                    DeviceUiModel(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        deviceType = device.deviceType.name,
                        isConnected = false,
                        lastSeenText = formatTimestamp(device.timestamp)
                    )
                }
                _uiState.update { it.copy(pairedDevices = deviceModels, isLoading = false) }
            }
    }
}

fun refresh() {
    _uiState.update { it.copy(isLoading = true) }
    observeDevices()
}
```

修改方案:

```kotlin
private val pairedDevicesFlow = deviceBridge.getPairedDevices()
private val connectionStateFlow = deviceBridge.getConnectionState()
private val roleFlow = deviceBridge.getCurrentRole()

val uiState: StateFlow<HomeUiState> = combine(
    pairedDevicesFlow,
    connectionStateFlow,
    roleFlow
) { devices, connectionState, role ->
    val connectedId = (connectionState as? DeviceConnectionState.Connected)?.deviceId
    HomeUiState(
        isLoading = false,
        connectionState = connectionState.toUiText(),
        currentRole = role.toUiText(),
        pairedDevices = devices.map { device ->
            DeviceUiModel(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                deviceType = device.deviceType.name,
                isConnected = device.deviceId == connectedId,
                lastSeenText = formatTimestamp(device.timestamp)
            )
        }
    )
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = HomeUiState(isLoading = true)
)

fun refresh() {
    viewModelScope.launch {
        deviceBridge.startDiscovery()
    }
}
```

修改逻辑: 当前实现每次刷新都会新增一个 `collect`，会造成重复订阅、状态竞争和内存泄漏；同时 `isConnected` 被硬编码成 `false`，首页永远不可能真实显示连接状态。应将首页状态改成单一 `StateFlow` 派生结果，刷新动作只触发数据源更新，不得再重新启动 collector。

严重程度: 高

[任务 #003]
定位锚点: `MainActivity.SmsLinkApp()`、`OnboardingViewModel.selectRole()`、`AppPreferences`

当前代码:

```kotlin
@Composable
fun SmsLinkApp(
    appPreferencesViewModel: AppPreferencesViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel()
) {
    var currentRoute by remember { mutableStateOf(Routes.HOME) }
    val showOnboarding by appPreferencesViewModel.shouldShowOnboarding.collectAsState()
    val isDarkTheme by appPreferencesViewModel.isDarkTheme.collectAsState()
    val onboardingStep by onboardingViewModel.currentStep.collectAsState()

    OnboardingScreen(
        currentStep = onboardingStep,
        onRoleSelected = { role ->
            onboardingViewModel.selectRole(role)
        },
        onPermissionsGranted = {
            onboardingViewModel.requestNotificationPermissions()
            onboardingViewModel.checkPermissionsAndProceed()
        },
        onComplete = {
            appPreferencesViewModel.markOnboardingCompleted()
            currentRoute = Routes.PAIRING
        }
    )
}

fun selectRole(role: String) {
    val parsedRole = DeviceRole.valueOf(role)
    _selectedRole.value = parsedRole
    viewModelScope.launch {
        deviceBridge.switchRole(parsedRole)
    }
    nextStep()
}
```

修改方案:

```kotlin
@Composable
fun SmsLinkApp(
    appPreferencesViewModel: AppPreferencesViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel()
) {
    val showOnboarding by appPreferencesViewModel.shouldShowOnboarding.collectAsState()
    val isDarkTheme by appPreferencesViewModel.isDarkTheme.collectAsState()
    val onboardingStep by onboardingViewModel.currentStep.collectAsState()
    val startRoute = if (showOnboarding) Routes.ONBOARDING else Routes.HOME
    var currentRoute by rememberSaveable { mutableStateOf(startRoute) }

    when {
        showOnboarding -> OnboardingScreen(
            currentStep = onboardingStep,
            onRoleSelected = { role -> onboardingViewModel.selectRole(role) },
            onPermissionsGranted = { onboardingViewModel.checkPermissionsAndProceed() },
            onComplete = {
                appPreferencesViewModel.markOnboardingCompleted()
                currentRoute = Routes.PAIRING
            }
        )
        else -> MainNavigation(
            currentRoute = currentRoute,
            onNavigate = { route -> currentRoute = route }
        ) { route -> /* existing navigation */ }
    }
}

fun selectRole(role: DeviceRole) {
    _selectedRole.value = role
    viewModelScope.launch {
        deviceBridge.switchRole(role)
    }
    nextStep()
}
```

修改逻辑: 当前首启流程、角色选择和导航路由都只存在于 `remember` 内存态，进程重建或配置变更后会丢失状态，导致“已经完成引导”与“仍在引导中”的表现不一致。应把首启结果、角色选择和最终入口路由都收敛到持久化状态里，同时把 `selectRole` 从字符串改成强类型 `DeviceRole`，避免 UI 层再手动 `valueOf`。

严重程度: 中

[任务 #004]
定位锚点: `CallManager.registerPhoneStateListener()`、`CallManager.startAudioStreaming()`、`CallManager.stopAudioStreaming()`

当前代码:

```kotlin
private fun registerPhoneStateListener() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state, phoneNumber)
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }
}

private fun startAudioStreaming() {
    if (isAudioStreaming) return
    isAudioStreaming = true
    audioPlayer.initialize()
    audioPlayer.startPlaying()
    scope.launch {
        audioCapture.startCapture().collect { audioData ->
            val encodedData = audioCodec.encode(audioData)
            sendAudioData(encodedData)
        }
    }
}

private fun stopAudioStreaming() {
    if (!isAudioStreaming) return
    isAudioStreaming = false
    audioCapture.stopCapture()
    audioPlayer.stopPlaying()
}
```

修改方案:

```kotlin
private var phoneStateCallback: TelephonyCallback? = null
private var phoneStateListener: PhoneStateListener? = null
private var audioStreamJob: Job? = null

private fun registerPhoneStateListener() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        phoneStateCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, phoneStateCallback!!)
    } else {
        @Suppress("DEPRECATION")
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state, phoneNumber)
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }
}

private fun startAudioStreaming() {
    if (isAudioStreaming) return
    isAudioStreaming = true
    audioPlayer.initialize()
    audioPlayer.startPlaying()
    audioStreamJob = scope.launch {
        audioCapture.startCapture().collect { audioData ->
            sendAudioData(audioCodec.encode(audioData))
        }
    }
}

private fun stopAudioStreaming() {
    if (!isAudioStreaming) return
    isAudioStreaming = false
    audioStreamJob?.cancel()
    audioStreamJob = null
    audioCapture.stopCapture()
    audioPlayer.stopPlaying()
}

fun close() {
    stopAudioStreaming()
    phoneStateCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
    phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
    phoneStateCallback = null
    phoneStateListener = null
}
```

修改逻辑: 当前通话模块只注册监听，不保存回调引用，也没有稳定的音频流 Job 句柄，实际上会留下难以回收的系统监听器和悬挂协程；`stopAudioStreaming()` 只停设备，不停采集协程，重复来电时还可能叠加多个流。必须补齐注销和取消逻辑，确保通话生命周期可控，否则长时间运行后会出现资源泄漏和重复发送音频。

严重程度: 高

3. 约束规则 (强制执行)
编码规范: 禁止在 `ui` 模块里继续定义业务域模型；所有状态组合优先使用 `Flow` / `combine` / `stateIn`；禁止用字符串代替 `DeviceRole`、`TransferStatus` 等枚举类型；不得新增第三方依赖。

禁止变动: 严禁修改现有消息协议字段名和顺序；严禁破坏 `DeviceInfo`、`NotificationInfo`、`CallInfo` 的公共字段语义；严禁把后端业务逻辑重新挪回 `ui` 模块；严禁删除现有 Room 表和 DAO 方法的基础能力。

依赖限制: 只允许复用当前仓库里的 `Hilt`、`Room`、`DataStore`、`Compose`、`Coroutines`、`Flow`；如果必须新增类型，只能放进现有 `core:*`、`feature:*` 或 `network:*` 模块中，不允许引入新的状态管理库或网络库。

4. 验证标准
[ ] `.\gradlew.bat :app:assembleDebug` 必须通过

[ ] `.\gradlew.bat test` 必须通过，且不能引入新的单元测试失败

[ ] 文件传输接收侧必须能把数据写入本地文件，并正确回写进度、完成和取消状态

[ ] 首页必须能真实反映连接状态，刷新操作不能产生重复 collector

[ ] 首次启动、角色选择和导引完成状态必须在进程重建后保持一致

[ ] 通话模块必须在结束或销毁时释放电话监听和音频采集资源
