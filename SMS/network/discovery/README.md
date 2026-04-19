# Network Discovery Module

## 功能概述
使用 mDNS/NSD 实现局域网设备发现。

### 核心功能
- mDNS 服务注册
- mDNS 服务发现
- 设备信息广播
- 设备列表维护

## 依赖关系
- `core:model` - 数据模型
- `core:common` - 通用工具

## 使用示例

```kotlin
val discovery = DeviceDiscovery(context)

// 注册服务
discovery.register(deviceInfo)

// 发现设备
discovery.discover().collect { devices ->
    // 处理发现的设备
}
```

## 测试

```bash
./gradlew :network:discovery:test
```
