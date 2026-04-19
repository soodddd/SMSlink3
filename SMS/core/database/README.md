# Core Database Module

## 功能概述
使用 Room 提供本地数据持久化能力。

### 核心功能
- 设备信息存储
- 通知历史记录
- 文件传输记录
- 数据库迁移管理

## 依赖关系
- `core:model` - 数据模型定义

## 数据库设计

```kotlin
@Database(
    entities = [
        DeviceEntity::class,
        NotificationEntity::class,
        FileTransferEntity::class
    ],
    version = 1
)
abstract class SmsLinkDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun notificationDao(): NotificationDao
    abstract fun fileTransferDao(): FileTransferDao
}
```

## 测试

```bash
./gradlew :core:database:test
```
