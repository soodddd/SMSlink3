# Feature Call Module

## 功能概述
通话功能模块，负责来电检测、通话控制、音频桥接。

### 核心功能
- 来电检测 (TelephonyManager)
- 通话控制 (接听/挂断/静音)
- 音频桥接 (主设备 ↔ 副设备)
- 来电 UI (全屏意图)
- 通话状态同步

## 依赖关系
- `core:common` - 通用工具
- `core:model` - 数据模型
- `network:protocol` - 协议定义
- `network:transport` - 网络传输
- `audio` - 音频处理
- `ui` - UI 组件

## 权限要求
- `READ_PHONE_STATE`
- `CALL_PHONE`
- `ANSWER_PHONE_CALLS`
- `RECORD_AUDIO`
- `MODIFY_AUDIO_SETTINGS`

## 使用示例

```kotlin
val callManager = CallManager(context)

// 监听来电
callManager.incomingCalls.collect { call ->
    // 显示来电界面
}

// 接听电话
callManager.answerCall(callId)

// 挂断电话
callManager.endCall(callId)
```

## 测试

```bash
./gradlew :feature:call:test
```
