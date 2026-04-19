# SMS-link 项目架构设计

## 项目结构

```
sms/
├── app/                          # Android 应用主模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/smslink/
│   │   │   ├── MainActivity.kt
│   │   │   ├── SmsLinkApplication.kt
│   │   │   └── di/              # 依赖注入配置
│   │   └── res/
│   └── build.gradle.kts
│
├── core/                         # 核心基础模块
│   ├── common/                   # 通用工具和扩展
│   ├── model/                    # 数据模型定义
│   ├── database/                 # 本地数据库
│   └── preferences/              # SharedPreferences 封装
│
├── feature/                      # 功能模块
│   ├── device/                   # 设备管理模块
│   ├── notification/             # 通知同步模块
│   ├── call/                     # 通话功能模块
│   ├── transfer/                 # 文件传输模块
│   └── settings/                 # 设置模块
│
├── network/                      # 网络通信层
│   ├── protocol/                 # 通信协议定义
│   ├── transport/                # 传输层实现 (TCP/UDP/蓝牙)
│   ├── discovery/                # 设备发现 (mDNS/NSD)
│   └── hotspot/                  # 热点管理
│
├── audio/                        # 音频处理模块
│   ├── src/main/
│   │   ├── cpp/                  # Native 代码
│   │   │   ├── opus/             # Opus 编解码
│   │   │   ├── webrtc/           # WebRTC AEC/NS/AGC
│   │   │   └── jni/              # JNI 桥接
│   │   └── java/
│   └── CMakeLists.txt
│
├── ui/                           # UI 组件库
│   ├── components/               # 可复用组件
│   ├── theme/                    # 主题和样式
│   └── navigation/               # 导航配置
│
└── docs/                         # 项目文档
    ├── modules/                  # 各模块接口文档
    ├── protocol/                 # 协议规范文档
    └── development/              # 开发指南
```

## 模块依赖关系

```
app
 ├─> feature/* (所有功能模块)
 ├─> ui
 ├─> core/*
 └─> network

feature/*
 ├─> core/*
 ├─> network
 ├─> ui
 └─> audio (仅 call 模块依赖)

network
 └─> core/model

audio
 └─> core/common

ui
 └─> core/common
```

## 核心原则

1. **单向依赖**: 低层模块不依赖高层模块
2. **接口隔离**: 模块间通过接口通信
3. **独立测试**: 每个模块可独立测试
4. **文档驱动**: 每个模块必须有 API 文档

## 开发阶段规划

### 阶段 0: 项目初始化 (1-2天)
- 创建项目结构
- 配置 Gradle 多模块构建
- 设置 NDK/CMake 构建环境
- 编写各模块 README.md

### 阶段 1: 核心基础层 (3-5天)
**模块**: `core/*`
- `core/common`: 工具类、扩展函数、常量定义
- `core/model`: 数据模型 (Device, Notification, CallState, FileTransfer)
- `core/database`: Room 数据库设计
- `core/preferences`: 配置存储

**交付物**:
- `docs/modules/CORE_API.md` - 核心 API 文档
- 单元测试覆盖率 > 80%

### 阶段 2: 网络通信层 (5-7天)
**模块**: `network/*`
- `network/protocol`: 协议编解码器
- `network/transport`: TCP/UDP/蓝牙传输实现
- `network/discovery`: mDNS 设备发现
- `network/hotspot`: SoftAP 热点管理

**交付物**:
- `docs/protocol/PROTOCOL_SPEC.md` - 协议规范
- `docs/modules/NETWORK_API.md` - 网络 API 文档
- 集成测试 (双设备模拟)

### 阶段 3: 设备管理模块 (3-4天)
**模块**: `feature/device`
- 设备配对流程
- 主副设备角色管理
- 连接状态监控
- 角色切换机制

**交付物**:
- `docs/modules/DEVICE_API.md`
- UI 页面: 设备列表、配对界面

### 阶段 4: 通知同步模块 (4-5天)
**模块**: `feature/notification`
- NotificationListenerService 实现
- 通知序列化/反序列化
- 镜像通知生成
- 通知历史管理

**交付物**:
- `docs/modules/NOTIFICATION_API.md`
- UI 页面: 通知历史列表

### 阶段 5: 音频处理模块 (7-10天)
**模块**: `audio`
- Opus 编解码集成
- WebRTC AEC/NS/AGC 集成
- JNI 桥接层
- 音频采集/播放管道

**交付物**:
- `docs/modules/AUDIO_API.md`
- `audio/README.md` - Native 构建说明
- 音频延迟测试报告

### 阶段 6: 通话功能模块 (5-7天)
**模块**: `feature/call`
- 来电检测
- 通话控制 (接听/挂断/静音)
- 音频桥接实现
- 来电 UI (全屏意图)

**交付物**:
- `docs/modules/CALL_API.md`
- UI 页面: 来电界面、通话控制

### 阶段 7: 文件传输模块 (5-6天)
**模块**: `feature/transfer`
- 文件分片传输
- 断点续传
- 传输进度管理
- 文件接收请求处理

**交付物**:
- `docs/modules/TRANSFER_API.md`
- UI 页面: 文件传输列表、进度详情

### 阶段 8: UI 组件库 (3-4天)
**模块**: `ui`
- Material Design 3 主题
- 通用组件 (状态卡片、进度条等)
- 导航配置
- 深色模式支持

**交付物**:
- `docs/modules/UI_COMPONENTS.md`
- Compose 组件库

### 阶段 9: 设置模块 (2-3天)
**模块**: `feature/settings`
- 权限管理引导
- 应用设置界面
- 关于页面

**交付物**:
- `docs/modules/SETTINGS_API.md`

### 阶段 10: 集成与测试 (5-7天)
- 端到端测试
- 性能优化
- 内存泄漏检测
- 多设备场景测试

### 阶段 11: 文档与发布 (2-3天)
- 用户手册
- API 文档整理
- 发布准备

## 总计开发时间: 45-63 天

## 模块文档规范

每个模块必须包含以下文档:

### 1. README.md (模块根目录)
```markdown
# 模块名称

## 功能概述
简要描述模块职责

## 依赖关系
列出依赖的其他模块

## 构建说明
如何单独构建此模块

## 测试
如何运行测试
```

### 2. API.md (docs/modules/)
```markdown
# 模块 API 文档

## 公开接口
列出所有公开的类、接口、函数

## 使用示例
代码示例

## 回调/监听器
事件通知机制

## 错误处理
异常类型和处理方式
```

### 3. CHANGELOG.md (模块根目录)
记录模块版本变更历史

## 技术栈

- **语言**: Kotlin 1.9+
- **构建**: Gradle 8.7 + Kotlin DSL
- **UI**: Jetpack Compose
- **异步**: Kotlin Coroutines + Flow
- **依赖注入**: Hilt
- **数据库**: Room
- **网络**: OkHttp + 自定义协议
- **音频**: Opus + WebRTC (Native)
- **测试**: JUnit 5 + Mockk + Turbine
