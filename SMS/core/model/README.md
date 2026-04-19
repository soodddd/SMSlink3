# Core Model Module

## 功能概述
定义应用核心数据模型，所有模块共享的数据结构。

### 核心模型
- `Device` - 设备信息
- `Notification` - 通知数据
- `CallState` - 通话状态
- `FileTransfer` - 文件传输信息
- `Message` - 消息协议

## 依赖关系
无外部模块依赖（基础模块）

## 数据模型

```kotlin
data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val role: DeviceRole
)

enum class DeviceRole {
    PRIMARY,   // 主设备
    SECONDARY  // 副设备
}
```

## 测试

```bash
./gradlew :core:model:test
```
