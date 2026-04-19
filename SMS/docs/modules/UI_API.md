# UI Components API 文档

## 状态说明

**本文件中的组件定义均为"阶段规划中的目标 API 草案"，当前仓库未提交对应 Compose 实现。**

以下内容仅用于约束后续开发方向，不能视为当前可调用组件。

---

## 主题系统 (Planned)

### SmsLinkTheme

```kotlin
@Composable
fun SmsLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
)
```

**状态**: 未实现

---

## 通用组件 (Planned API)

### StatusCard

```kotlin
@Composable
fun StatusCard(
    title: String,
    status: ConnectionStatus,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
)

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR
}
```

**状态**: 未实现

### DeviceListItem (Planned)

```kotlin
@Composable
fun DeviceListItem(
    device: Device,
    onPair: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier
)
```

**状态**: 未实现

### TransferProgressBar (Planned)

```kotlin
@Composable
fun TransferProgressBar(
    progress: Float,
    fileName: String,
    fileSize: Long,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
)
```

**状态**: 未实现

### NotificationCard (Planned)

```kotlin
@Composable
fun NotificationCard(
    notification: Notification,
    onDismiss: () -> Unit,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
)
```

**状态**: 未实现

### CallScreen (Planned)

```kotlin
@Composable
fun CallScreen(
    callState: CallState,
    onAnswer: () -> Unit,
    onEnd: () -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier
)
```

**状态**: 未实现

---

## 导航 (Planned API)

### NavGraph

```kotlin
@Composable
fun SmsLinkNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "devices"
    ) {
        composable("devices") { DevicesScreen() }
        composable("notifications") { NotificationsScreen() }
        composable("transfers") { TransfersScreen() }
        composable("settings") { SettingsScreen() }
    }
}
```

**状态**: 未实现
