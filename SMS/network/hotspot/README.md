# Network Hotspot Module

## 功能概述
管理 WiFi 热点创建和连接。

### 核心功能
- 创建 WiFi 热点 (SoftAP)
- 连接到热点
- 热点状态监控
- 自动配置

## 依赖关系
- `core:model` - 数据模型
- `core:common` - 通用工具

## 使用示例

```kotlin
val hotspot = HotspotManager(context)

// 创建热点
hotspot.create(ssid = "SMS-Link", password = "12345678")

// 连接热点
hotspot.connect(ssid = "SMS-Link", password = "12345678")
```

## 权限要求
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `ACCESS_FINE_LOCATION`

## 测试

```bash
./gradlew :network:hotspot:test
```
