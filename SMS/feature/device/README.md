# Feature Device Module

## 功能概述
设备管理模块，负责设备配对、角色管理、连接状态监控。

### 核心功能
- 设备发现和配对
- 主副设备角色管理
- 连接状态监控
- 角色切换机制
- 设备信息持久化

## 依赖关系
- `core:common` - 通用工具
- `core:model` - 数据模型
- `core:database` - 数据持久化
- `core:preferences` - 配置管理
- `network:discovery` - 设备发现
- `network:transport` - 网络传输
- `network:hotspot` - 热点管理
- `ui` - UI 组件

## 使用示例

```kotlin
val deviceManager = DeviceManager(context)

// 发现设备
deviceManager.discoverDevices().collect { devices ->
    // 显示设备列表
}

// 配对设备
deviceManager.pairDevice(deviceId)

// 切换角色
deviceManager.switchRole(DeviceRole.SECONDARY)
```

## 测试

```bash
./gradlew :feature:device:test
```
