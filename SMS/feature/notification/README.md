# Feature Notification Module

## 功能概述
通知同步模块，负责监听、序列化、传输和镜像通知。

### 核心功能
- NotificationListenerService 实现
- 通知捕获和序列化
- 通知传输
- 镜像通知生成
- 通知历史管理

## 依赖关系
- `core:common` - 通用工具
- `core:model` - 数据模型
- `core:database` - 数据持久化
- `network:protocol` - 协议定义
- `network:transport` - 网络传输
- `ui` - UI 组件

## 权限要求
- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`

## 使用示例

```kotlin
// 在 AndroidManifest.xml 中注册服务
<service
    android:name=".NotificationListenerService"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

## 测试

```bash
./gradlew :feature:notification:test
```
