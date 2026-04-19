# Core Preferences Module

## 功能概述
使用 DataStore 管理应用配置和用户偏好设置。

### 核心功能
- 设备角色配置
- 连接偏好设置
- 通知过滤规则
- 应用设置

## 依赖关系
无外部模块依赖

## 使用示例

```kotlin
class AppPreferences(context: Context) {
    private val dataStore = context.dataStore
    
    val deviceRole: Flow<DeviceRole> = dataStore.data
        .map { it[DEVICE_ROLE] ?: DeviceRole.PRIMARY }
    
    suspend fun setDeviceRole(role: DeviceRole) {
        dataStore.edit { it[DEVICE_ROLE] = role }
    }
}
```

## 测试

```bash
./gradlew :core:preferences:test
```
