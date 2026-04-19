# Feature Transfer Module

## 功能概述
文件传输模块，支持分片传输、断点续传、进度管理。

### 核心功能
- 文件分片传输
- 断点续传
- 传输进度管理
- 文件接收请求处理
- 传输历史记录

## 依赖关系
- `core:common` - 通用工具
- `core:model` - 数据模型
- `core:database` - 数据持久化
- `network:protocol` - 协议定义
- `network:transport` - 网络传输
- `ui` - UI 组件

## 权限要求
- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`

## 使用示例

```kotlin
val transferManager = TransferManager(context)

// 发送文件
transferManager.sendFile(fileUri, deviceId)

// 监听传输进度
transferManager.getTransferProgress(transferId).collect { progress ->
    // 更新进度条
}

// 取消传输
transferManager.cancelTransfer(transferId)
```

## 测试

```bash
./gradlew :feature:transfer:test
```
