# Audio Module

## 当前状态

**本模块仅保留阶段性占位结构**

- Native 源码、第三方音频库源码和 JNI 实现尚未提交
- 在这些文件真实落库前，禁止在 Gradle 中启用 `externalNativeBuild`
- 当前为纯 Kotlin/Java Android Library 占位模块

## 规划功能概述

提供音频采集、编解码、处理和播放功能，用于通话功能模块。

### 规划核心功能
- Opus 音频编解码 (低延迟、高质量) - **未实现**
- WebRTC 音频处理 (AEC 回声消除、NS 噪声抑制、AGC 自动增益) - **未实现**
- OpenSL ES 音频采集和播放 - **未实现**
- JNI 桥接层 - **未实现**

## 依赖关系
- `core:common` - 通用工具类

## 构建说明

当前模块可以作为空 Android Library 构建:

```bash
./gradlew :audio:build
```

### 未来 Native 构建前置要求
- Android NDK 27.2+
- CMake 3.22.1+
- Opus 库源码 (需要手动下载)
- WebRTC 音频处理库源码 (需要手动下载)

## 架构

```
audio/
├── src/main/
│   ├── cpp/
│   │   ├── opus/          # Opus 编解码器
│   │   ├── webrtc/        # WebRTC 音频处理
│   │   └── jni/           # JNI 桥接
│   │       ├── audio_jni.cpp
│   │       ├── opus_codec.cpp
│   │       └── audio_processor.cpp
│   └── java/
│       └── com/smslink/audio/
│           ├── AudioCapture.kt
│           ├── AudioPlayer.kt
│           ├── OpusCodec.kt
│           └── AudioProcessor.kt
└── CMakeLists.txt
```

## 测试

```bash
./gradlew :audio:test
```

## 性能指标
- 编码延迟: < 20ms
- 解码延迟: < 10ms
- 端到端延迟: < 150ms (目标)
- 采样率: 48kHz
- 比特率: 24-32 kbps
