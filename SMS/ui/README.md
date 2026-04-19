# UI Module

## 功能概述
提供可复用的 Compose UI 组件、主题系统和导航配置。

### 核心功能
- Material Design 3 主题
- 可复用 Compose 组件
- 导航配置
- 深色模式支持
- 通用动画效果

## 依赖关系
- `core:common` - 通用工具

## 组件库

```kotlin
// 状态卡片
@Composable
fun StatusCard(
    title: String,
    status: ConnectionStatus,
    onClick: () -> Unit
)

// 进度指示器
@Composable
fun TransferProgressBar(
    progress: Float,
    fileName: String
)

// 设备列表项
@Composable
fun DeviceListItem(
    device: Device,
    onPair: () -> Unit
)
```

## 测试

```bash
./gradlew :ui:test
```
